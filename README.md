# NoCap OS

[![Latest release](https://img.shields.io/github/v/release/shatheitguy/alfa-launcher?color=ff2a2a)](../../releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**NoCap OS** is the Android side of [NoCapOS](https://github.com/shatheitguy/nocapos): a sci-fi home screen that links your phone to your own NoCap server. Your server's apps sit one tap from home, next to an orbit dial, a 3D app orbit and a built-in IT toolkit.

## Download

**[⬇ Latest APK](../../releases/latest/download/alfa-launcher.apk)**

Every push to `main` is built by GitHub Actions and published under [Releases](../../releases). Pull requests and branches get a debug-signed `nocapos-launcher.apk` as a workflow artifact.

## Install

1. Download the APK on your phone and allow "Install unknown apps" for your browser or file manager.
2. Open **NoCap OS** and tap **Set as default**. Pressing Home now lands you on NoCap OS.
3. Link your NoCap server (below), or tap **Skip for now** and do it later.

Google Play Protect may warn about an app installed from outside the Play Store. NoCap OS is open source: every line is in this repo.

Requires Android 8.0+.

## Link your NoCap server

1. On the server, open **Settings → Devices → Link a device**. It shows a 6-digit code and a QR.
2. On the phone, NoCap OS opens **Link your NoCap server** on first run. You can also get there from **⚙ → NoCap Cloud** or by tapping the NoCap Cloud panel on the home screen.
3. Either tap **Scan QR** (the camera permission is asked for only then), or type the server address and the code. The address defaults to `https://nocap.local`; if that doesn't resolve on your network, use the IP and port the server shows, e.g. `https://192.168.1.20:8443`.
4. NoCap OS shows the server name, its NoCap ID and a short certificate fingerprint. Tap **Link it**.

Tapping a `nocap://pair?server=…&code=…&id=…` link opens the same screen with everything filled in.

### How the link is secured

- NoCap servers use self-signed HTTPS. On first contact NoCap OS records the SHA-256 of the server's certificate and shows you a short fingerprint. After you link, it accepts **only that exact certificate** for that server. Nothing is trusted globally.
- The device token, server address and pinned fingerprint are stored in encrypted preferences backed by the Android Keystore.
- If the server's certificate changes, NoCap OS refuses to connect. Unlink and relink if the change is legit.

### What the link does

- **NoCap Cloud panel** on the home screen: server name, online dot, your username, and the server's apps as tiles. Tap a tile to open it in a Custom Tab. It refreshes every time you come home.
- **Heartbeat** every 15 minutes (battery level and app version) so the server knows the phone is around.
- If the server removes the phone, NoCap OS notices on the next refresh or heartbeat and goes back to "not linked".
- **⚙ → NoCap Cloud**: linked server, username, pinned fingerprint, link a different server, **Unlink**, and a switch to hide the panel.

## Home screen

- **Profile**: your name and logo with a time-aware greeting, plus **⚙** for NoCap OS Settings
- **Clock** with date. Beside it: device model, Android version, uptime, RAM and storage usage
- **NoCap Cloud**: your linked server and its apps
- **Orbit dial**: up to 8 favourite apps orbit a battery hub. Drag around the ring to spin it, flick for momentum
- **Tap the orbit centre** for the **All Apps orbit**: every app on three rings, 3D tilt while dragging, swipe to page, pinch to zoom, and search
- **IT Tools** button, **search**, and a **dock** for 5 apps
- **Swipe down** for notifications, **long-press** empty space for Settings
- Long-press any app: add to or remove from the orbit or dock, App info, Uninstall
- **Icon styles**: Neon glyph, Colour orb, or Original. **Accents**: NoCap red, Ice blue, Mint, Amber, Violet, Mono
- **Dual / clone and work-profile apps** show with a badge

## Wallpapers

One wallpaper everywhere: the style you pick is set as the real system wallpaper.

| Group | Styles |
|---|---|
| Fluid glass | **Liquid**, **Aurora** |
| 3D depth | **Prism**, **Layers** |
| Matte minimal | **Dots**, **Beam**, **Eclipse** |
| Your photos | your own images, with optional **Darken** and **Tint with accent** |

## NoCap Assistant

Tap **✦** on the home screen to chat, long-press it to talk. It opens apps, changes NoCap OS settings, sets timers and alarms, and runs quick IT checks. It runs on an on-device model or your own local AI server.

## IT Tools (built in)

| Tool | What it does |
|---|---|
| Network info | transport, interface, IPv4/IPv6, gateway, DNS, private DNS, MTU, signal, public IP |
| Speed test | download, upload, ping and jitter, with a verdict |
| Wi-Fi details | live signal meter, band, channel, link speed, Wi-Fi standard |
| SSL checker | certificate status, expiry countdown, issuer, covered domains, TLS version, chain |
| Wake-on-LAN | save PCs and servers by MAC address and wake them with one tap |
| QR generator | Wi-Fi, link or text to QR; save as PNG or share |
| Ping / DNS lookup / Port check | live ping, A / AAAA / PTR records, TCP connect check on hosts you own |
| Subnet calc | network, broadcast, mask, wildcard, host range |
| Device info | SoC, CPU, RAM, storage, display, battery health, kernel, patch level |
| Hash / Base64 / Password gen | hashes, encode/decode, secure random passwords |
| System panels | one-tap jumps to Wi-Fi, VPN, developer options, default apps and more |
| App update | check, download and install the latest NoCap OS build |

## Updating

NoCap OS checks this repo's Releases every 12 h and shows an **▲ UPDATE AVAILABLE** chip on the home screen when a new build exists. Your orbit, dock, profile, accent, wallpaper and server link are kept.

## Build it yourself

```
./gradlew assembleDebug
```

JDK 17 and the Android SDK (platform 34) are needed. Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file (`sdk.dir=...`). The APK lands in `app/build/outputs/apk/debug/`.

## About the developer

<img src="https://avatars.githubusercontent.com/u/61654902?v=4&s=120" width="72" align="left" alt="Sharqan Ahamed" />

**Sharqan Ahamed, Sha The IT Guy**: Senior IT Infrastructure Engineer and Cloud & Cybersecurity Strategist, Dubai, UAE.

[shatheitguy.in](https://shatheitguy.in) · [GitHub](https://github.com/shatheitguy) · [LinkedIn](https://ae.linkedin.com/in/sharqan-ahamed-8555b8169) · [YouTube](https://www.youtube.com/@shatheitguy) · [X](https://x.com/Sha_The_IT_Guy)

Also by me: [NoCapOS](https://github.com/shatheitguy/nocapos), the server this app links to, and [IT-Vault](https://github.com/shatheitguy/it-vault), a self-hosted IT asset register and helpdesk.

## License

NoCap OS is open source under the [MIT License](LICENSE). © 2026 Sharqan Ahamed (Sha The IT Guy).
