#!/usr/bin/env bash
# Builds the Linux packages (.deb, .rpm, Arch .pkg.tar.zst) with nFPM into desktop/build/linux-packages.
#   SKIP_CORE=1   reuse desktop/libs/linux/frkn-service instead of building it (CI downloads it)
#   FORMATS       subset of "deb rpm archlinux"
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(sed -n 's/^frkn\.versionName=//p' "$REPO_ROOT/gradle.properties" | head -n1)"
FORMATS="${FORMATS:-deb rpm archlinux}"
STAGE="$REPO_ROOT/desktop/build/linux-stage"
OUT="$REPO_ROOT/desktop/build/linux-packages"
NFPM="${NFPM:-$(command -v nfpm || echo "$(go env GOPATH)/bin/nfpm")}"
[ -x "$NFPM" ] || { echo "nfpm not found (go install github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.47.0)" >&2; exit 1; }

if [ "${SKIP_CORE:-0}" != "1" ]; then
  CORE_TARGETS=linux bash "$REPO_ROOT/scripts/build-libbox.sh"
fi
(cd "$REPO_ROOT" && ./gradlew -Pfrkn.desktopOnly :desktop:prepareLinuxBinaries :desktop:createReleaseDistributable)

rm -rf "$STAGE" "$OUT"
mkdir -p "$STAGE" "$OUT"
cp -a "$REPO_ROOT/desktop/build/compose/binaries/main-release/app/FRKN" "$STAGE/FRKN"
mkdir -p "$STAGE/FRKN/lib/app/resources"
cp "$REPO_ROOT/desktop/build/bundled-resources/linux/"* "$STAGE/FRKN/lib/app/resources/"
chmod 755 "$STAGE/FRKN/lib/app/resources/frkn-service" "$STAGE/FRKN/lib/app/resources/ciadpi"

cp -r "$REPO_ROOT/desktop/packaging/linux/." "$STAGE/"
cp "$REPO_ROOT/desktop/src/main/resources/frkn-icon.png" "$STAGE/frkn.png"
cp "$REPO_ROOT/LICENSE" "$STAGE/LICENSE"
sed -e "s|\${FRKN_VERSION}|$VERSION|" -e "s|\${FRKN_APP_DIR}|./FRKN|" \
    -e "s|\${FRKN_ICON}|./frkn.png|" -e "s|\${FRKN_LICENSE}|./LICENSE|" \
    "$REPO_ROOT/desktop/packaging/linux/nfpm.yaml" > "$STAGE/nfpm.yaml"
cd "$STAGE"
for format in $FORMATS; do
  case "$format" in
    deb) file="FRKN-$VERSION-amd64.deb" ;;
    rpm) file="FRKN-$VERSION-x86_64.rpm" ;;
    archlinux) file="FRKN-$VERSION-x86_64.pkg.tar.zst" ;;
    *) echo "unknown format: $format" >&2; exit 1 ;;
  esac
  "$NFPM" package --config nfpm.yaml --packager "$format" --target "$OUT/$file"
done
ls -la "$OUT"
