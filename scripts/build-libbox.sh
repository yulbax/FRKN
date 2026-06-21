#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Builds sing-box's experimental/libbox (Go Mobile binding) into app/libs/libbox.aar,
# pinned to a sing-box tag, with the Tailscale native-shell import stubbed out so
# libgojni.so doesn't drag in the whole Tailscale stack (~18 MB).
#
# Runs both locally and in CI. The toolchain is expected to be on PATH / in the
# environment (the CI job installs it; locally you provide it):
#   - go (matching sing-box's go.mod), gomobile + gobind (sagernet fork @v0.1.12)
#   - a valid Android NDK (ANDROID_NDK_HOME) and SDK (ANDROID_HOME)
#
# Knobs (env vars):
#   SING_BOX_TAG    sing-box git tag to build            (default below)
#   SING_BOX_REPO   clone URL
#   LIBBOX_WORK_DIR scratch dir for the sing-box checkout (default <repo>/.libbox-build)
#   FORCE_REBUILD=1 build even if app/libs/libbox.aar already exists
#
# On a sing-box bump the only thing that can break is the stub below: if upstream
# changes the ShellSession interface / OpenNative*Session signatures, the build (or
# the Tailscale leak check) fails loudly — update the heredoc and re-run.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SING_BOX_TAG="${SING_BOX_TAG:-v1.13.13}"
SING_BOX_REPO="${SING_BOX_REPO:-https://github.com/SagerNet/sing-box.git}"
WORK_DIR="${LIBBOX_WORK_DIR:-${REPO_ROOT}/.libbox-build}"
SRC="${WORK_DIR}/sing-box"
OUT_AAR="${REPO_ROOT}/app/libs/libbox.aar"

# FULL upstream Android libbox feature set (cmd/internal/build_libbox sharedTags)
# minus only with_tailscale. Every protocol sing-box ships on Android is compiled
# in — adding a new one is a Kotlin-side LinkParser change, not a core rebuild.
# Do NOT trim these; keep the core fully featured. Tailscale is dropped via the
# native_shell_session.go stub below (an import strip, not a tag).
TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_TAG} -s -w -buildid= -checklinkname=0"

say() { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m!!! %s\033[0m\n' "$*" >&2; exit 1; }

# Diagnostics: on ANY failing command print the exact line, command and exit code,
# so CI logs pinpoint the real failure instead of just the preceding log line.
trap 'rc=$?; printf "\033[1;31m!!! build-libbox.sh FAILED: line %s, exit %s, cmd: %s\033[0m\n" "$LINENO" "$rc" "$BASH_COMMAND" >&2' ERR

if [ "${FORCE_REBUILD:-0}" != "1" ] && [ -f "$OUT_AAR" ]; then
  say "libbox.aar already present ($OUT_AAR) — skipping build (FORCE_REBUILD=1 to override)."
  exit 0
fi

command -v go        >/dev/null || die "go not in PATH"
command -v gomobile  >/dev/null || die "gomobile not in PATH (go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12)"
command -v gobind    >/dev/null || die "gobind not in PATH (go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12)"
[ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ] || die "ANDROID_NDK_HOME unset or missing"

# ── 1. Fetch sing-box at the pinned tag. ────────────────────────────────────────
if [ ! -d "$SRC/.git" ]; then
  say "Cloning sing-box $SING_BOX_TAG"
  git clone --depth 1 --branch "$SING_BOX_TAG" "$SING_BOX_REPO" "$SRC"
else
  say "Reusing checkout; fetching $SING_BOX_TAG"
  git -C "$SRC" fetch --tags --depth 1 --quiet origin "$SING_BOX_TAG"
  git -C "$SRC" checkout -f --quiet "FETCH_HEAD"
fi
cd "$SRC"
say "HEAD: $(git describe --tags 2>/dev/null || git rev-parse --short HEAD)"

# ── 2. Re-apply the Tailscale-strip stub (only where it's needed). ──────────────
# Newer sing-box (≈v1.14+) ships experimental/libbox/native_shell_session.go which
# unconditionally imports tailscale's tailssh, dragging the whole stack in even with
# with_tailscale off — we overwrite it with a stub. Older tags (e.g. v1.13.x) have
# no such file and gate Tailscale behind the tag, so the stub is unneeded (and would
# not compile against the older API) — skip it there. The leak check below verifies.
STUB="experimental/libbox/native_shell_session.go"
if [ ! -f "$STUB" ]; then
  say "No $STUB in this tag — Tailscale stub not needed."
else
  say "Writing Tailscale-free stub: $STUB"
  cat > "$STUB" <<'EOF'
//go:build linux || android || (darwin && !ios)

package libbox

import "os"

// FRKN patch: upstream wires the native PTY shell to tailscale's `tailssh`,
// which unconditionally drags the entire Tailscale stack into the binary even
// when `with_tailscale` is off. FRKN does not use the native shell session, so
// these are stubbed to drop the import (and ~Tailscale) from libgojni.so.

type nativeShellSession struct{}

func OpenNativeShellSession(
	shell, cwd string,
	args, environ StringIterator,
	term string,
	rows, cols, uid, gid int32,
	groups Int32Iterator,
) (ShellSession, error) {
	return nil, os.ErrInvalid
}

func OpenNativePipeSession(
	shell, cwd string,
	args, environ StringIterator,
	uid, gid int32,
	groups Int32Iterator,
) (ShellSession, error) {
	return nil, os.ErrInvalid
}

func (s *nativeShellSession) MasterFD() int32              { return -1 }
func (s *nativeShellSession) Resize(rows, cols int32) error { return os.ErrInvalid }
func (s *nativeShellSession) Signal(sig int32) error        { return os.ErrInvalid }
func (s *nativeShellSession) WaitExit() (int32, error)      { return 0, os.ErrInvalid }
func (s *nativeShellSession) Close() error                  { return nil }
EOF
fi

# ── 2b. FRKN patch: re-seed the "randomized" uTLS fingerprint on every resolve. ──
# Upstream sets randomizedFingerprint.Seed once in init(), so a process keeps ONE
# synthetic ClientHello for its whole life — a bad seed never self-heals short of a
# full process restart. We re-seed on each uTLSClientHelloID("randomized") call, so a
# fresh engine start yields a fresh fingerprint (the curve-safe Weights are kept).
FP_FILE="common/tls/utls_client.go"
if grep -q "FRKN patch: re-seed randomized" "$FP_FILE" 2>/dev/null; then
  say "uTLS re-seed patch already present."
elif grep -q "return randomizedFingerprint, nil" "$FP_FILE" 2>/dev/null; then
  say "Patching $FP_FILE (re-seed randomized per call)"
  # The `return randomizedFingerprint, nil` line is unique in the file, so a single-line
  # GNU sed substitution is enough — no perl/multiline needed (keeps CI dependency-free).
  sed -i 's|return randomizedFingerprint, nil|frknFP := randomizedFingerprint\n\t\tfrknFP.Seed, _ = utls.NewPRNGSeed()\n\t\treturn frknFP, nil // FRKN patch: re-seed randomized|' "$FP_FILE"
  grep -q "FRKN patch: re-seed randomized" "$FP_FILE" || die "uTLS re-seed patch did not apply — update it for the new upstream source."
  say "uTLS re-seed patch applied OK."
else
  die "Cannot find randomized fingerprint case in $FP_FILE — upstream changed; update the FRKN uTLS patch."
fi

# ── 3. Pre-flight: assert Tailscale is gone from the dependency graph. ──────────
say "Checking dependency graph for Tailscale leakage"
TS_DEPS="$(go list -deps -tags "$TAGS" ./experimental/libbox 2>/dev/null | grep -c -i tailscale || true)"
[ "$TS_DEPS" -eq 0 ] || die "Tailscale still pulled in ($TS_DEPS pkgs) — the stub didn't take; update it for the new upstream signatures."
say "Dependency graph is Tailscale-free."

# ── 4. Build. ───────────────────────────────────────────────────────────────────
say "go: $(go version) | gomobile: $(command -v gomobile)"
say "tags: $TAGS"
say "Running gomobile bind (android/arm64 + android/amd64 + android/arm) — takes a few minutes…"
mkdir -p "$(dirname "$OUT_AAR")"
gomobile bind -v \
  -o "$OUT_AAR" \
  -target=android/arm64,android/amd64,android/arm \
  -androidapi 24 \
  -trimpath -buildvcs=false \
  -ldflags "$LDFLAGS" \
  -tags "$TAGS" \
  ./experimental/libbox

# ── 5. Size sanity. The dep-graph check above is the authoritative Tailscale guard;
# this is just a backstop. Three ABIs (arm64+amd64+arm) clean ≈ 53 MB; a Tailscale
# leak adds ~9 MB per ABI (~27 MB), so >65 MB is suspicious.
SIZE_MB=$(( $(stat -c%s "$OUT_AAR") / 1024 / 1024 ))
say "Built libbox.aar: ${SIZE_MB} MB"
[ "$SIZE_MB" -le 65 ] || printf '\033[1;33m!!! WARNING: %s MB larger than expected — Tailscale may have leaked back in.\033[0m\n' "$SIZE_MB"
ls -la "$OUT_AAR"
say "DONE."
