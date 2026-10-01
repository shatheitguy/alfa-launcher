# ALFA Launcher

[![Website](https://img.shields.io/badge/website-alfa--launcher-ff2d3d)](https://shatheitguy.github.io/alfa-launcher/)
[![Latest release](https://img.shields.io/github/v/release/shatheitguy/alfa-launcher?color=ff2d3d)](../../releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A sci-fi, minimal Android **home screen replacement** with a built-in IT toolkit.

🌐 **Website:** https://shatheitguy.github.io/alfa-launcher/

## Download

**[⬇ alfa-launcher.apk (latest)](../../releases/latest/download/alfa-launcher.apk)**

Every push to `main` is built by GitHub Actions and published under [Releases](../../releases).

## Home screen

- **Orbit dial**: up to 8 favourite apps orbit a central hub. The hub ring shows battery, and the outer arcs show RAM and storage. The tick rings rotate slowly.
- Dark carbon background with accent glow (or switch to your own wallpaper)
- Clock, date, device model, Android version and uptime
- Network readout: connection type and local IP (tap for full network info)
- **Tap the orbit centre** to open **All Apps orbit**: every app on three spinning rings (6 / 12 / 18 per page). Swipe left/right to rotate to the next page, and search to filter. The hub shows the letter range and page.
- **Spin the orbit**: drag around the ring on the home dial or in All Apps and the apps rotate with your finger. Flick for momentum. Long-press the home screen to turn **Orbit spin** on or off, or enable **Idle drift** (a slow automatic rotation).
- Dock (5 apps). **Swipe down** opens notifications, **long-press** empty space opens settings
- Long-press any app to add/remove it from the orbit or dock, see app info, or uninstall
- **Icon styles**:
  - **Neon glyph**: glowing single-colour glyph in a glass orb (uses Android 13 themed icons when available)
  - **Colour orb**: full-colour icon inside the orb
  - **Original**: the stock app icons
- Accent colours: Crimson, Ice blue, Mint, Amber, Violet, Mono. The icons are redrawn in your accent colour.

## ALFA OS Settings

Open it with **⚙** next to your profile, or **long-press** the home screen. Every option lives here, grouped into sections:

| Section | Options |
|---|---|
| Profile | name, logo (custom image or initials) |
| Appearance | accent colour, icon style, background (ALFA carbon / my wallpaper), change wallpaper |
| Orbit & motion | orbit spin, 3D tilt in All Apps, idle drift, vibration |
| Home screen | IT tools bar, network line, reset orbit & dock |
| Updates | check for updates, auto-check |
| System | default home app, IT Tools, Android settings |
| About | version, source & releases |

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
- **Download update**: ALFA downloads the new version (with a progress bar) and installs it itself. You only confirm **Update** on the Android prompt. The first time, Android asks you to allow ALFA to install apps.
- Your orbit, dock, accent and other settings are kept.

## Install

1. Download the APK on your phone and allow "Install unknown apps".
2. Open **ALFA Launcher** and tap **Set as default**. It then replaces the stock launcher, and pressing Home brings you here.

Requires Android 8.0+.

## About the developer

<img src="https://github.com/shatheitguy.png?size=120" width="72" align="left" alt="Sharqan Ahamed" />

**Sharqan Ahamed, Sha The IT Guy**: Senior IT Infrastructure Engineer and Cloud & Cybersecurity Strategist, Dubai, UAE.
I design and run the infrastructure other people take for granted. ALFA is the home screen I wanted on my own phone:
the telemetry I'd check anyway, and the tools I actually reach for, one tap from home.

[shatheitguy.in](https://shatheitguy.in) · [GitHub](https://github.com/shatheitguy) · [LinkedIn](https://ae.linkedin.com/in/sharqan-ahamed-8555b8169) · [YouTube](https://www.youtube.com/@shatheitguy) · [X](https://x.com/Sha_The_IT_Guy)

Also by me: [IT-Vault](https://github.com/shatheitguy/it-vault), a self-hosted IT asset register and helpdesk.

## License

ALFA Launcher is open source under the [MIT License](LICENSE). © 2026 Sharqan Ahamed (Sha The IT Guy).
