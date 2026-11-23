# 🌐 Fabric P2P World

[![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-10B981?style=for-the-badge)](https://www.minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5+-00F0FF?style=for-the-badge)](https://fabricmc.net/)
[![Java 25+](https://img.shields.io/badge/Java-25+-A855F7?style=for-the-badge)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B?style=for-the-badge)](LICENSE)
[![Sponsor](https://img.shields.io/badge/Sponsor-Kynxhel-EA4AAA?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/Kynxhel)

**Fabric P2P World** is a lightweight, open-source **Peer-to-Peer World Hosting** mod for **Minecraft 26.3 (Fabric)**. Host your singleplayer worlds directly from the Pause Menu with zero paid servers, automatic NAT/CGNAT detection, **UDP Hole Punching**, automatic **TCP Tunnel Relay**, smart room codes, and optional **Shared Cloud Worlds**.

---

## ✨ Features

### 🚀 4 Smart Connection Modes (Auto-Tested & Ranked)
When you open the **Start P2P** screen, the mod automatically tests your network (Public IP, UPnP port mapping, CGNAT, Cone vs. Symmetric NAT via Dual-STUN, and Relay latency) and shows a live right-side selector with **green/red status dots** and a **`(Recommended)`** tag on the fastest supported option:

| Mode | Best For | Overhead | Works Behind CGNAT? |
| :--- | :--- | :---: | :---: |
| **Direct P2P (UPnP)** | Open NAT routers with UPnP enabled | `0 ms` | ❌ |
| **UDP Punch** | Cone CGNAT networks (Direct UDP P2P) | `0 ms` | ✅ (Cone NAT) |
| **Tunnel Relay** | Symmetric NAT, strict firewalls, mobile hotspots | Low (`bore.pub`) | ✅ (Always) |
| **Local LAN** | Same Wi-Fi / local network / VPN | `<1 ms` | ✅ (LAN) |

### 🎨 Modern Neon Control Center UI
- **Pause Menu Integration:** Seamless `Start P2P` button right beside vanilla *Open to LAN*.
- **Title & Multiplayer Screen Integration:** 1-click `Join P2P` and `Shared Worlds` buttons.
- **Smart Clipboard & Discord Invites:** Generates clean `P2P-XXXX-XXXX-XXXX` / `UDP-XXXX-XXXX-XXXX` codes and rich Discord invites. Pasting an entire Discord invite into the Join screen auto-extracts the code!
- **Live Server Ping & Signal Meter:** Check latency (ms), player count, and MOTD before joining.
- **Configurable World Controls:** Toggle Game Mode, Cheats, Default/Random Port, and **Offline LAN Auth** mode for local/offline LAN sessions.

### ☁️ Optional Shared Cloud Worlds (Toggleable)
- **Disabled by default** — enable anytime from the `☁ Shared Worlds` screen.
- Supports **Sign in with Google** (direct Google Drive API v3 sync to your own `FabricP2P_SharedWorlds` folder) OR any local synced folder (Google Drive Desktop, OneDrive, Dropbox, Syncthing).
- **Rotating Host Lock (`session.lock.json`):** Whoever starts the world first becomes the Host; friends automatically see `● LIVE` and join with 1 click. When the Host exits, the updated world auto-syncs so anyone else can host next!

---

## 📥 Installation

1. Install **Minecraft 26.3** with **Fabric Loader `>= 0.19.5`**.
2. Download and install **Fabric API** for Minecraft `26.3`.
3. Drop `fabric-p2p-world-26.3-2.1.1+26.3.jar` into your `.minecraft/mods/` folder.
4. Launch the game — open any world, press `Esc`, and click **`Start P2P`**!

---

## 🛠️ Building from Source

```bash
git clone https://github.com/Kynxhel/fabric-p2p-world.git
cd fabric-p2p-world
./gradlew build
```

The compiled mod JAR will be located in `build/libs/`.

---

## 🔒 Privacy & Transparency

- **Open Source:** 100% auditable Java source code under the [MIT License](LICENSE).
- **Privacy Policy:** Read [PRIVACY.md](PRIVACY.md) for full details on P2P networking and the optional Google Drive integration.
- **Bug Reports & Suggestions:** Open an issue on [GitHub Issues](https://github.com/Kynxhel/fabric-p2p-world/issues).
