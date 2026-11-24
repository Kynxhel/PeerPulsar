# PeerPulsar

[![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-10B981?style=for-the-badge)](https://www.minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5+-00F0FF?style=for-the-badge)](https://fabricmc.net/)
[![Java 25+](https://img.shields.io/badge/Java-25+-A855F7?style=for-the-badge)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B?style=for-the-badge)](LICENSE)
[![Sponsor](https://img.shields.io/badge/Sponsor-Kynxhel-EA4AAA?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/Kynxhel)

**PeerPulsar** is an open-source peer-to-peer world hosting mod for **Minecraft 26.3 (Fabric)**. Host singleplayer worlds directly from the Pause Menu with zero paid servers or account sign-ins, using opt-in NAT/CGNAT diagnostics, UPnP port forwarding, UDP Hole Punching, and TCP Tunnel Relay.

---

## Quick Start

1. **Install:** Drop `peerpulsar-3.2.0+26.3.jar` and Fabric API (`>=0.165.2+26.3`) into `.minecraft/mods/`.
2. **Host:** Open any singleplayer world, press `Esc`, click **Start P2P**, and copy the Room Code or Discord invite.
3. **Join:** Your friend opens **Join P2P**, pastes the Room Code (or entire Discord invite), and joins immediately.

---

## Features

### 4 Connection Modes (Opt-In Diagnostics & Auto-Selection)
Click **Test** on the **Start P2P** screen (after reviewing the one-time consent prompt) to check your network (Public IP, UPnP port mapping, CGNAT, Cone vs. Symmetric NAT via Dual-STUN, and Relay latency).

| Mode | Best For | Relay Overhead | Works Behind CGNAT? |
| :--- | :--- | :---: | :---: |
| **Direct P2P (UPnP)** | Open NAT routers with UPnP enabled (1-hour auto-renewed lease + cleanup) | Direct | No |
| **UDP Punch** | Cone CGNAT networks (Direct UDP P2P, AES-256-GCM encrypted signaling) | Direct | Yes (Cone NAT) |
| **Tunnel Relay** | Symmetric NAT, strict firewalls, mobile hotspots (hides host IP) | Relayed (`bore.pub`) | Yes (Always) |
| **Local LAN** | Same Wi-Fi / local network / VPN (no external services contacted) | Local | Yes (LAN) |

### Room Key Protection, Join Approval & Player Controls
- **Embedded Room Key (`#KEY`):** Always enabled on internet hosting modes (both Mojang Online and Offline LAN Auth), verified via HMAC-SHA256 over a fresh server nonce. Port scanners and uninvited players without the `#KEY` are silently rejected.
- **Brute-Force Protection:** 5 failed Room Key attempts from the same IP within 30 seconds temporarily blocks that IP.
- **New Code & Kick:** Rotate the `#KEY` mid-session with **New Code** without kicking current players or restarting the tunnel, and kick unwanted players from the live player list.
- **Optional Join Approval (Off by Default):** Enable **Join Approval** on the Host screen to review incoming join requests in-game with `[Accept]`, `[Always]`, or `[Deny]`, matched by both nickname and Ed25519 device fingerprint.
- **Guest Commands Control:** Even when Cheats are enabled for the host, joiners do not receive command/operator permissions unless **Guest Commands** is explicitly enabled in **Advanced → Security**.
- **Optional Offline LAN Auth:** Optional Offline LAN Auth lets players with offline accounts join, protected by an embedded room key, optional join approval, and name locks. When enabled, players with valid Mojang sessions keep their Mojang UUID/skin, while offline players receive a deterministic Ed25519-key UUID with safe backup-first legacy data migration and identity export/import.

### Configurable Network Services (Advanced Menu)
Open **⚙ Advanced** from the Host or Join screen to inspect or customize **Tunnel Relay**, **UDP Signaling**, and **STUN / Public IP Discovery** endpoints:
- Default mode is **Auto (Recommended)** with automatic failover.
- Run **Test All** (opt-in) to check latency, or add custom self-hosted endpoints.
- Custom UDP signaling servers are automatically embedded in the room code so joiners connect without extra setup.

---

## External Services & Privacy Disclosure

PeerPulsar collects **no telemetry, analytics, or user accounts**. External services are contacted **only** after you opt in to **Network Diagnostics** / **Test All** or start/join an internet P2P session (Local LAN mode makes zero external requests):

- **STUN / Public IP Discovery:** `stun.l.google.com:19302`, `stun1.l.google.com:19302`, `stun.cloudflare.com:3478`, `stun.nextcloud.com:443`, and HTTPS IP echo endpoints (`api.ipify.org`, `checkip.amazonaws.com`, `icanhazip.com`).
- **Community Relay & Signaling:** `bore.pub` and `ntfy.sh` are free third-party community services unrelated to the mod author, with no uptime or SLA guarantee. You can add or self-host your own server in the **Advanced** menu (for example, `bore server` from `github.com/ekzhang/bore`).
- **Room Code IP Notice:** Direct P2P (UPnP) and Local LAN room codes encode your IP address and port (XOR/Base64url, not encrypted). Share them only with people you trust, or use **Tunnel Relay** / **UDP Punch** to avoid embedding your IP in the code.

See [PRIVACY.md](PRIVACY.md) for full details.

---

## Building from Source

```bash
git clone https://github.com/Kynxhel/PeerPulsar.git
cd PeerPulsar
./gradlew build test
```

The compiled mod JAR will be located in `build/libs/peerpulsar-3.2.0+26.3.jar`.

---

## License & Support

- **Open Source:** MIT License ([LICENSE](LICENSE))
- **Privacy Policy:** [PRIVACY.md](PRIVACY.md)
- **Bug Reports & Support:** [GitHub Issues](https://github.com/Kynxhel/PeerPulsar/issues)
