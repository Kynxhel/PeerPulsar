# 🌐 PeerPulsar

[![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-10B981?style=for-the-badge)](https://www.minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5+-00F0FF?style=for-the-badge)](https://fabricmc.net/)
[![Java 25+](https://img.shields.io/badge/Java-25+-A855F7?style=for-the-badge)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B?style=for-the-badge)](LICENSE)
[![Sponsor](https://img.shields.io/badge/Sponsor-Kynxhel-EA4AAA?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/Kynxhel)

**PeerPulsar** *(formerly Atarca / Fabric P2P World)* is a lightweight, open-source **Peer-to-Peer World Hosting** mod for **Minecraft 26.3 (Fabric)**. Host your singleplayer worlds directly from the Pause Menu with zero paid servers, zero account sign-ins, automatic NAT/CGNAT detection, **UDP Hole Punching**, automatic **TCP Tunnel Relay**, and smart room codes.

---

## ✨ Features

### 🚀 4 Smart Connection Modes (Auto-Tested & Ranked)
When you open the **Start P2P** screen, PeerPulsar automatically tests your network (Public IP, UPnP port mapping, CGNAT, Cone vs. Symmetric NAT via Dual-STUN, and Relay latency) and shows a live right-side selector with **green/red status dots** and a **`(Recommended)`** tag on the fastest supported option:

| Mode | Best For | Overhead | Works Behind CGNAT? |
| :--- | :--- | :---: | :---: |
| **Direct P2P (UPnP)** | Open NAT routers with UPnP enabled | `0 ms` | ❌ |
| **UDP Punch** | Cone CGNAT networks (Direct UDP P2P) | `0 ms` | ✅ (Cone NAT) |
| **Tunnel Relay** | Symmetric NAT, strict firewalls, mobile hotspots | Low (`bore.pub`) | ✅ (Always) |
| **Local LAN** | Same Wi-Fi / local network / VPN | `<1 ms` | ✅ (LAN) |

### 🎨 Modern Neon Control Center UI
- **Pause Menu Integration:** Seamless `Start P2P` button right beside vanilla *Open to LAN*.
- **Title & Multiplayer Screen Integration:** 1-click `Join P2P` button.
- **Smart Clipboard & Discord Invites:** Generates clean `P2P-XXXX-XXXX-XXXX` / `UDP-XXXX-XXXX-XXXX` codes and rich Discord invites. Pasting an entire Discord invite into the Join screen auto-extracts the code!
- **Live Server Ping & Signal Meter:** Check latency (ms), player count, and MOTD before joining.
- **Configurable World Controls:** Toggle Game Mode, Cheats, Default/Random Port, and **Offline LAN Auth** mode for local/offline LAN sessions.

---

## ℹ️ Why Was Cloud World Sync Removed?

In `v3.0.0`, the experimental **Shared Cloud Worlds (Google Drive Sync)** feature was intentionally removed:

- Google Drive API's restricted `drive` scope caps unverified apps at **100 total users**, shows a frightening unverified-app full-Drive warning screen, and requires an expensive annual CASA security audit to lift for public open-source mods.
- Google's non-restricted `drive.file` scope isolates files strictly per-user, meaning invited friends **cannot** read shared world files (`manifest.json`, `session.lock.json`, region files) created by another player via the API.

Rather than shipping a capped or intrusive cloud integration, **PeerPulsar** focuses 100% on fast, unlimited, zero-account peer-to-peer hosting.

---

## 📥 Installation

1. Install **Minecraft 26.3** with **Fabric Loader `>= 0.19.5`**.
2. Download and install **Fabric API** for Minecraft `26.3`.
3. Drop `peerpulsar-26.3-3.1.0+26.3.jar` into your `.minecraft/mods/` folder.
4. Launch the game — open any world, press `Esc`, and click **`Start P2P`**!

---

## 🛠️ Building from Source

```bash
git clone https://github.com/Kynxhel/Atarca.git
cd Atarca
./gradlew build
```

The compiled mod JAR will be located in `build/libs/`.

---

## 🔒 Privacy & Transparency

- **Open Source:** 100% auditable Java source code under the [MIT License](LICENSE).
- **Zero Accounts / Zero Telemetry:** No login, no Google OAuth, and no central data-collection servers.
- **Privacy Policy:** Read [PRIVACY.md](PRIVACY.md) for full details on P2P networking.
- **Bug Reports & Suggestions:** Open an issue on [GitHub Issues](https://github.com/Kynxhel/Atarca/issues).
