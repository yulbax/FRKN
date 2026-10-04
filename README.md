<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="192" alt="FRKN icon" />

# FRKN

**A per-app split VPN for Android and Windows.**

</div>

FRKN tunnels each app independently. Instead of an all-or-nothing VPN switch,
you decide — per app — whether traffic should go through a proxy, through a built-in
DPI-bypass engine, or straight out untouched. All three can run at the same time.

## Download

Get the latest APKs and the Windows installer from
[Releases](https://github.com/yulbax/FRKN/releases/latest).

- **Android** — `FRKN-<version>-arm64-v8a.apk` fits almost every modern phone;
  `universal` works everywhere. Android 10 or newer.
- **Windows** — `FRKN-<version>.msi`, Windows 10/11 x64. The app needs administrator
  rights to create the tunnel and asks for them on launch. To update, close FRKN and
  install the newer MSI over the old one; settings and servers are kept.

## Features

- **Per-app routing.** Assign every app one of three modes:
  - **Direct** — leaves the device normally, with its real IP and no overhead.
  - **VPN** — tunnelled through your proxy server.
  - **ByeDPI** — routed through a built-in engine that defeats DPI-based censorship
    locally, without any remote server.
- **Wide protocol support.** Import servers from `vless`, `vmess`, `trojan`,
  `shadowsocks`, `hysteria2` and `wireguard` share links, AmneziaWG `vpn://` links,
  WireGuard / AmneziaWG `.conf` files, or a subscription URL.
- **Modern transports.** TCP, WebSocket, gRPC, HTTP and HTTPUpgrade, with TLS / Reality
  and configurable uTLS fingerprints.
- **Windows extras.** Apps are matched by process name; an optional mode sends all
  traffic that has no explicit rule through the VPN.
- **Stays out of the way.** No system-wide proxy, no exposed control API, optional
  auto-connect, and a built-in connection health check.

## How it works

FRKN combines two engines behind a single tunnel interface:

- **sing-box** owns the tunnel and dispatches traffic per app — *VPN* apps to the
  upstream server, *ByeDPI* apps to the local bypass proxy, *Direct* apps straight out.
  FRKN uses [amnezia-box](https://github.com/amnezia-vpn/amnezia-box), the Amnezia fork
  of [sing-box](https://github.com/SagerNet/sing-box) that adds AmneziaWG. The same core
  is embedded on both platforms: as a Go Mobile library on Android and as
  `frkn-core.dll` on Windows.
- **[ByeDPI](https://github.com/hufrea/byedpi)** runs as a local proxy that
  desynchronizes packets to slip past Deep Packet Inspection.

## Building

FRKN is fully open-source — no Google services, no proprietary dependencies.

The sing-box core is built from source by `scripts/build-libbox.sh` rather than committed
as a binary. It needs Go 1.25.

### Android

Install the SagerNet `gomobile` tools and point `ANDROID_NDK_HOME` at NDK 28:

```bash
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
export PATH="$(go env GOPATH)/bin:$PATH"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
CORE_TARGETS=android bash scripts/build-libbox.sh
./gradlew assembleRelease
```

Building requires Android SDK 37 and CMake 3.22.1. A physical device is recommended;
Android's VPN APIs are unreliable on emulators.

### Windows

The core DLL is cross-compiled with MinGW (`gcc-mingw-w64-x86-64` on Debian/Ubuntu, or
any C compiler for Windows set in `WINDOWS_CC`):

```bash
CORE_TARGETS=windows bash scripts/build-libbox.sh   # → desktop/libs/windows/frkn-core.dll
```

The installer itself must be built on Windows with JDK 21:

```bash
./gradlew -Pfrkn.desktopOnly :desktop:packageReleaseMsi
```

`-Pfrkn.desktopOnly` skips the Android modules, so no Android SDK is needed there.

## Built with

- [amnezia-box](https://github.com/amnezia-vpn/amnezia-box) /
  [sing-box](https://github.com/SagerNet/sing-box) — the VPN core
- [ByeDPI](https://github.com/hufrea/byedpi) — the DPI-bypass engine
- Kotlin Multiplatform, Compose Multiplatform, Room, Koin, JNA

## License

FRKN is licensed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE).

ByeDPI retains its own license; see `app/src/main/cpp/byedpi/LICENSE`.
