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

- **Profile**: your name and logo with a time-aware greeting, plus a **⚙** button for ALFA OS Settings
- **Clock** with date. Beside it: device model, Android version, uptime, **RAM** and **STO** (storage) usage
- **Orbit dial**: up to 8 favourite apps orbit a battery hub. Drag around the ring to spin it, and flick for momentum (optional idle drift)
- **Tap the orbit centre** to open the **All Apps orbit**: every app on three rings (6 / 12 / 18 per page)
  - circle-drag spins the rings, and the orbit tilts in 3D while you drag
  - a straight swipe in any direction changes page, and pinch zooms
  - search filters, and the hub shows the letter range and page
- **IT Tools** button and **search**, then a **dock** for 5 apps
- **Swipe down** opens notifications, and **long-press** empty space opens ALFA OS Settings
- Long-press any app: add it to or remove it from the orbit or dock, App info, Uninstall
- **Icon styles**: Neon glyph (glowing glyph in a glass orb), Colour orb, or Original
- **Accents**: Crimson, Ice blue, Mint, Amber, Violet, Mono. Icons and wallpapers re-colour to match
- **Dual / clone and work-profile apps** show with a badge. System clutter from clone profiles is hidden (optional)

## Wallpapers

One wallpaper everywhere: the ALFA style you choose is set as the **real system wallpaper**, so the home screen, Recents and app switching all match.

| Group | Styles |
|---|---|
| Fluid glass | **Liquid**, **Aurora** |
| 3D depth | **Prism** (lit low-poly facets), **Layers** (paper-cut waves with rim light) |
| Matte minimal | **Dots**, **Beam**, **Eclipse** |
| Your photos | add your own images, with optional **Darken** and **Tint with accent** |

The lock screen is optional.

## ALFA OS Settings

Open with **⚙** next to your profile, or long-press the home screen.

| Section | Options |
|---|---|
| Profile | name, logo (custom image or initials) |
| Appearance | accent, icon style, wallpaper style, your photos, lock screen |
| Orbit & motion | orbit spin, 3D tilt in All Apps, idle drift, vibration |
| Home screen | IT Tools button, all apps in clone profile, reset orbit & dock |
| System | default home app, IT Tools, Android settings |
| Updates | check for updates, auto-check |
| About | ALFA OS version (opens the website), source & releases |

## IT Tools (built in)

| Tool | What it does |
|---|---|
| Network info | transport, interface, IPv4/IPv6, gateway, DNS, private DNS, MTU, signal, public IP |
| Speed test | download, upload, ping and jitter against Cloudflare's speed servers, with a verdict |
| Wi-Fi details | live signal meter, band, channel, link speed, Wi-Fi standard |
| SSL checker | certificate status, expiry countdown, issuer, covered domains, TLS version, chain |
| Wake-on-LAN | save PCs and servers by MAC address and wake them with one tap |
| QR generator | Wi-Fi (scan to join), link or text to QR; save as PNG or share |
| Ping | ICMP ping any host with live output |
| DNS lookup | A / AAAA records, reverse PTR, timing |
| Port check | TCP connect check on one host you own or are authorised to test |
| Subnet calc | network, broadcast, mask, wildcard, host range, class and type |
| Device info | SoC, CPU, RAM, storage, display, battery health, kernel, patch level |
| Hash / Base64 | MD5, SHA-1, SHA-256, SHA-512, CRC32, Base64 encode/decode |
| Password gen | secure random passwords with entropy estimate |
| System panels | one-tap jumps to Wi-Fi, VPN, developer options, default apps and more |
| App update | check, download and install the latest ALFA build |

Each tool window stays open in Recents while you switch apps.

## Updating

- ALFA checks this repo's Releases every 12 h, and an **▲ UPDATE AVAILABLE** chip appears on the home screen when a new build exists.
- Check by hand: ⚙ → **Updates** → **Check for updates**.
- **Download update** downloads with a progress bar and installs. You confirm **Update** on the Android prompt. The first time, Android asks you to allow ALFA to install apps.
- Your orbit, dock, profile, accent and wallpaper are kept.

## Install

1. Download the APK on your phone and allow "Install unknown apps".
2. Open **ALFA Launcher** and tap **Set as default**. Pressing Home now brings you to ALFA.

Google Play Protect may warn about an unrecognised app installed from outside the Play Store. ALFA is open source: every line is in this repo.

Requires Android 8.0+.

## About the developer

<img src="https://avatars.githubusercontent.com/u/61654902?v=4&s=120" width="72" align="left" alt="Sharqan Ahamed" />

**Sharqan Ahamed, Sha The IT Guy**: Senior IT Infrastructure Engineer and Cloud & Cybersecurity Strategist, Dubai, UAE.
I design and run the infrastructure other people take for granted. ALFA is the home screen I wanted on my own phone:
the telemetry I'd check anyway, and the tools I actually reach for, one tap from home.

[shatheitguy.in](https://shatheitguy.in) · [GitHub](https://github.com/shatheitguy) · [LinkedIn](https://ae.linkedin.com/in/sharqan-ahamed-8555b8169) · [YouTube](https://www.youtube.com/@shatheitguy) · [X](https://x.com/Sha_The_IT_Guy)

Also by me: [IT-Vault](https://github.com/shatheitguy/it-vault), a self-hosted IT asset register and helpdesk.

## License

ALFA Launcher is open source under the [MIT License](LICENSE). © 2026 Sharqan Ahamed (Sha The IT Guy).
