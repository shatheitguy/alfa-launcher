# ALFA Launcher

A sci-fi, minimal Android **home screen replacement** with a built-in IT toolkit.

## Download

**[⬇ alfa-launcher.apk (latest)](../../releases/latest/download/alfa-launcher.apk)**

Every push to `main` is built by GitHub Actions and published under [Releases](../../releases).

## Home screen

- **Orbit dial**: up to 8 favourite apps orbit a central hub. The hub ring shows battery, and the outer arcs show RAM and storage. The tick rings rotate slowly.
- Dark carbon background with accent glow (or switch to your own wallpaper)
- Clock, date, device model, Android version and uptime
- Network readout: connection type and local IP (tap for full network info)
- **Tap the orbit centre** to open **All Apps orbit**: every app on three spinning rings (6 / 12 / 18 per page). Swipe left/right to rotate to the next page, and search to filter. The hub shows the letter range and page.
- Dock (5 apps). **Swipe down** opens notifications, **long-press** empty space opens settings
- Long-press any app to add/remove it from the orbit or dock, see app info, or uninstall
- **Icon styles**:
  - **Neon glyph**: glowing single-colour glyph in a glass orb (uses Android 13 themed icons when available)
  - **Colour orb**: full-colour icon inside the orb
  - **Original**: the stock app icons
- Accent colours: Crimson, Ice blue, Mint, Amber, Violet, Mono. The icons are redrawn in your accent colour.

## IT Tools (built in)

| Tool | What it does |
|---|---|
| Network info | transport, interface, IPv4/IPv6, gateway, DNS, private DNS, MTU, signal, public IP |
| Ping | ICMP ping any host with live output |
| DNS lookup | A / AAAA records, reverse PTR, timing |
| Port check | TCP connect scan on one host (common / web / 1-1024 presets) |
| Subnet calc | network, broadcast, mask, wildcard, host range, class and type |
| Device info | SoC, CPU, RAM, storage, display, battery health, kernel, patch level, root check |
| Hash / Base64 | MD5, SHA-1, SHA-256, SHA-512, CRC32, Base64 encode/decode |
| Password gen | secure random passwords with entropy estimate |
| System panels | one-tap jumps to Wi-Fi, VPN, developer options, default apps, usage access and more |

## Updating

ALFA updates itself from this repo's Releases:

- It checks automatically every 12 h, and an **▲ UPDATE AVAILABLE** chip appears on the home screen when a new build exists.
- You can also check by hand: long-press the home screen → **Check for updates**, or IT Tools → **App update**.
- The APK downloads in the app and opens the Android installer. The first time, Android asks you to allow ALFA to install apps.
- Your orbit, dock, accent and other settings are kept.

## Install

1. Download the APK on your phone and allow "Install unknown apps".
2. Open **ALFA Launcher** and tap **Set as default**. It then replaces the stock launcher, and pressing Home brings you here.

Requires Android 8.0+.
