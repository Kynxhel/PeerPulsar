# PeerPulsar

[![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-10B981?style=for-the-badge)](https://www.minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5+-00F0FF?style=for-the-badge)](https://fabricmc.net/)
[![Java 25+](https://img.shields.io/badge/Java-25+-A855F7?style=for-the-badge)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B?style=for-the-badge)](LICENSE)
[![Sponsor](https://img.shields.io/badge/Sponsor-Kynxhel-EA4AAA?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/Kynxhel)

**PeerPulsar** is an open-source peer-to-peer world hosting mod for **Minecraft 26.3 (Fabric)**. Host singleplayer worlds directly from the Pause Menu with zero paid servers or account sign-ins, using opt-in NAT/CGNAT diagnostics, UPnP port forwarding, UDP Hole Punching, and TCP Tunnel Relay.

---

## Quick Start

1. **Install (Both Host & Joiners):** **Both the host and joining players need PeerPulsar installed.** Drop `peerpulsar-*.jar` and Fabric API (`>=0.165.2+26.3`) into `.minecraft/mods/`.
2. **Host:** Open any singleplayer world, press `Esc`, click **Start P2P**, and copy the Room Code or Discord invite.
3. **Join:** Your friend opens **Join P2P**, pastes the Room Code (or entire Discord invite), and joins immediately.

---

## Features

### 4 Connection Modes (Opt-In Diagnostics & Auto-Selection)
Click **Test** on the **Start P2P** screen (after reviewing the one-time consent prompt) to check your network (Public IP, UPnP port mapping, CGNAT, Cone vs. Symmetric NAT via Dual-STUN, and Relay latency).

| Mode | Best For | Relay Overhead | Works Behind CGNAT? |
| :--- | :--- | :---: | :---: |
| **Direct P2P (UPnP)** | Open NAT routers with UPnP enabled (1-hour auto-renewed lease + cleanup) | Direct | No |
| **UDP Punch** | Cone CGNAT networks (Direct UDP P2P: **Auto** picks **RUDP**, **KCP**, or **QUIC**) | Direct | Yes (Cone NAT) |
| **Tunnel Relay** | Symmetric NAT, strict firewalls, mobile hotspots (hides host IP) | Relayed (`bore.pub`) | Yes (Always) |
| **Local LAN** | Same Wi-Fi / local network / VPN (no external services contacted) | Local | Yes (LAN) |

### 3-Engine UDP P2P Transport (`RUDP` / `KCP` / `QUIC`) & Smart Switch Hints
- **Auto Engine Probing:** Immediately after NAT hole punching, **UDP Punch** runs a ~150 ms probe to pick the best engine for your link: **RUDP** (lightweight/minimal CPU for clean `<55 ms` links), **KCP** (`10 ms` tick, SACK, fast-resend for jittery PvP or light packet loss), or **QUIC** (`tech.kwik`, TLS 1.3 encrypted transport + congestion control for high ping/loss/mobile networks).
- **In-Game Switch Popup:** If your ping or packet loss changes noticeably for ~30 seconds, a bottom-right Neon hint (plus chat shortcut) offers to switch engines automatically with a clean ~1s reconnect, or you can lock an engine in **⚙ Advanced → Network Services → UDP Engine**.

### Room Key Protection, Join Approval & Player Controls
- **Embedded Room Key (`#KEY`):** Always enabled on internet hosting modes (both Mojang Online and Offline LAN Auth), verified via HMAC-SHA256 over a fresh server nonce. Port scanners and uninvited players without the `#KEY` are silently rejected.
- **Brute-Force Protection:** 5 failed Room Key attempts from the same IP within 30 seconds temporarily blocks that IP.
- **New Code, OP & Kick:** Rotate the `#KEY` mid-session with **New Code** without kicking current players or restarting the tunnel, and grant/revoke **OP** or **Kick** unwanted players from the live connected players list.
- **Join Approval (On by Default):** **Join Approval** prompts you in-game with `[Accept]`, `[Always]`, or `[Deny]`, matched by both nickname and Ed25519 device fingerprint (toggleable on the Host screen).
- **Guest Commands Control:** Even when Cheats are enabled for the host, joiners do not receive command/operator permissions unless **Guest Commands** is explicitly enabled in **Advanced → Security** (or granted per player via **OP**).
- **End-to-End Encrypted Offline LAN Auth:** Optional Offline LAN Auth disables Mojang account verification while still enforcing Minecraft's native RSA + AES-CFB8 transport encryption (`shouldAuthenticate = false`), channel-bound via SHA-256 host key hash into the `#KEY` HMAC and Ed25519 signature—protecting gameplay traffic against MITM and eavesdropping even over `bore.pub` or UDP Hole Punch (and when **QUIC** is selected, Kwik adds TLS 1.3 transport encryption with an ephemeral P-256 certificate pinned via encrypted signaling). Players receive deterministic Ed25519-key UUIDs with Ed25519 name locks, host name reservation, backup-first legacy data migration, and identity export/import.
- **Zero Session Token Access:** PeerPulsar never reads `User.getAccessToken()` or contacts Mojang session servers on behalf of clients.

### Configurable Network Services (Advanced Menu)
Open **⚙ Advanced** from the Host or Join screen to inspect or customize **Tunnel Relay**, **UDP Signaling**, and **STUN / Public IP Discovery** endpoints:
- Default mode is **Auto (Recommended)** with automatic failover.
- Run **Test All** (opt-in) to check latency, or replace the optional suggested community defaults with custom self-hosted endpoints.
- Custom UDP signaling servers are automatically embedded in the room code; joining a room with a custom signaling server disables HTTP redirects, enforces DNS-rebinding pinned public HTTPS validation, and prompts the joiner for explicit confirmation naming both the custom signaling domain and configured STUN servers.

---

## External Services & Privacy Disclosure

PeerPulsar collects **no telemetry, analytics, or user accounts**. External services are contacted **only** after you opt in to **Network Diagnostics** / **Test All** or start/join an internet P2P session (**Local LAN** mode makes zero external lookups):

- **Named First-Use Consent & Optional Suggested Defaults (`bore.pub`, `ntfy.sh`, STUN & IP Echo):** Host diagnostics consent (`networkTestConsent`) and join-side UDP consent (`udpJoinConsent`) are tracked independently. Both prompts explicitly name `bore.pub` (fallback TCP relay), `ntfy.sh` (AES-256-GCM encrypted UDP signaling), and public STUN / HTTPS IP echo endpoints (`stun.l.google.com:19302`, `stun1.l.google.com:19302`, `stun.cloudflare.com:3478`, `stun.nextcloud.com:443`, `api.ipify.org`, `checkip.amazonaws.com`, `icanhazip.com`). These are **optional suggested community defaults** unrelated to the mod author, with no uptime or SLA guarantee—you can freely change or self-host any of them in **⚙ Advanced → Network Services** (for example, `bore server` from `github.com/ekzhang/bore`).
- **SSRF, UPnP, Log & Token Safety:** HTTP redirects are disabled, public-only DNS-rebinding pinned checks are enforced across custom HTTPS endpoints and custom Tunnel Relay / STUN `host:port` endpoints, UPnP SSDP/SOAP requests are restricted to the responding local gateway IP, public/peer IPs and `#KEY` secrets are redacted from `latest.log`, identity backups export outside `.minecraft` (`~/.peerpulsar/peerpulsar-identity-backup.json` with `0600` POSIX permissions), and PeerPulsar never reads `User.getAccessToken()` or sends Mojang session tokens to hosts or third-party servers.
- **Room Code IP Notice:** Direct P2P (UPnP) and Local LAN room codes encode your IP address and port (XOR/Base64url, not encrypted). Share them only with people you trust, or use **Tunnel Relay** / **UDP Punch** to avoid embedding your IP in the code.

See [PRIVACY.md](PRIVACY.md) for full details.

---

## Building from Source

```bash
git clone https://github.com/Kynxhel/PeerPulsar.git
cd PeerPulsar
./gradlew build test
```

The compiled mod JAR will be located in `build/libs/peerpulsar-*.jar`.

---

## License & Support

- **Open Source:** MIT License ([LICENSE](LICENSE))
- **Privacy Policy:** [PRIVACY.md](PRIVACY.md)
- **Bug Reports & Support:** [GitHub Issues](https://github.com/Kynxhel/PeerPulsar/issues)
