# Privacy Policy — PeerPulsar

**Last Updated:** November 2026

**PeerPulsar** is an open-source, peer-to-peer Minecraft Fabric mod. It does **not** require accounts, does **not** include telemetry or analytics, and does **not** operate any central data-collection servers.

## 1. When External Services Are Contacted
PeerPulsar **never** contacts external servers on game startup, on the title screen, or when hosting in **Local LAN** mode (**Local LAN makes zero external lookups**). External services are contacted **only** after you explicitly opt in to **Network Diagnostics / Test All** (saved in `config/peerpulsar.json`) or start/join an internet P2P session:

| Service | Purpose | When Contacted |
| :--- | :--- | :--- |
| `stun.l.google.com:19302`, `stun1.l.google.com:19302`, `stun.cloudflare.com:3478`, `stun.nextcloud.com:443` | Dual-STUN public IP and NAT type (Cone vs. Symmetric) detection | Opt-in Network Test / Test All; UDP Hole Punch host/join |
| `api.ipify.org`, `checkip.amazonaws.com`, `icanhazip.com` | HTTPS public IP lookup fallback if STUN/UPnP WAN IP is unavailable | Opt-in Network Test / Test All; Direct P2P (UPnP) fallback |
| `ntfy.sh` (or custom HTTPS ntfy server) | Peer endpoint signaling for UDP Hole Punching. Topic names are SHA-256 hashed from the room code and all messages are end-to-end encrypted with AES-256-GCM. | Opt-in Test All; hosting or joining via **UDP Hole Punch** |
| `bore.pub:7835` (or custom bore server) | TCP tunnel relay fallback for Symmetric NAT / strict firewalls | Opt-in Network Test / Test All; **Tunnel Relay** hosting |

`bore.pub`, `ntfy.sh`, and the listed STUN / IP echo endpoints are **optional suggested community defaults** unrelated to the mod author, with no uptime or SLA guarantee. You can freely change, disable, or replace any of them with self-hosted or custom servers in **Advanced → Network Services**.

### Named First-Use Consent, SSRF & UPnP Hardening
- Both the host-side **Network Diagnostics** consent prompt and the join-side **UDP P2P Connection Consent** prompt explicitly name `bore.pub` (fallback TCP relay), `ntfy.sh` (AES-256-GCM encrypted UDP signaling), and public STUN / IP echo servers (`stun.l.google.com`, `stun.cloudflare.com`, etc.) as optional free third-party defaults changeable in **⚙ Advanced**.
- If a UDP room code specifies a custom signaling server URL, PeerPulsar disables HTTP redirects, enforces DNS-rebinding pinned public HTTPS checks (blocking loopback, link-local, and private/internal addresses), and prompts you with an explicit confirmation naming both the custom signaling domain and configured STUN servers before connecting.
- During the opt-in **Network Test**, PeerPulsar sends local SSDP multicast packets and briefly tests a temporary 60-second UPnP port mapping (immediately deleted after verification). UPnP SSDP/SOAP requests are strictly restricted to the responding local gateway IP.

### Zero Session Token Access & End-to-End Encrypted Offline LAN Auth
- **Zero Session Token Access:** PeerPulsar **never** reads `User.getAccessToken()` or contacts Mojang session servers on behalf of clients.
- **End-to-End Encrypted Offline LAN Auth:** When Offline LAN Auth is enabled, PeerPulsar uses Minecraft's native RSA + AES-CFB8 transport encryption (`shouldAuthenticate = false`) channel-bound via SHA-256 host key hash into the `#KEY` HMAC and Ed25519 signature — protecting gameplay traffic against MITM and eavesdropping even over `bore.pub` or UDP Hole Punch. (Note: UDP RUDP transport headers remain unencrypted so peers/NAT can route packets, while the Minecraft stream carried inside is encrypted.)

## 2. Room Codes & IP Address Disclosure
- **Direct P2P (UPnP)** and **Local LAN** room codes (`P2P-...`) encode the host's IP address and port (XOR/Base64url encoded, not encrypted) so peers can connect directly. Share Direct P2P codes only with people you trust, or use **Tunnel Relay** if you prefer to hide your IP address.
- **Tunnel Relay** room codes encode the relay server address rather than your home IP address.
- **UDP Hole Punch** room codes (`UDP-...`) do not contain your IP address; peer endpoints are exchanged directly inside AES-256-GCM encrypted signaling messages.

## 3. Local Files Stored on Your Device
PeerPulsar stores only local files on your machine — nothing is uploaded to the mod author:
- `config/peerpulsar.json`: Stores your opt-in network test consent, Advanced endpoint settings, and security toggles.
- `config/peerpulsar/identity.json`: A locally generated Ed25519 key pair used for device verification, name locks, and key-based offline UUIDs. You can export/import a backup from **Advanced → Identity**.
- `<world>/peerpulsar_name_locks.json`: Stores SHA-256 fingerprints of public keys bound to player names when Offline LAN Auth is used.
- `<world>/peerpulsar_approved.json`: Stores host-approved players (name, device key fingerprint, and Mojang UUID if applicable) when Join Approval "Always" is used.
- `<world>/peerpulsar_identity_map.json` & `<world>/peerpulsar_backups/`: Records one-time legacy playerdata migrations and stores automatic backups before any migration or host-initiated data transfer.

## 4. Contact
For questions or privacy inquiries, please open an issue on the [GitHub Repository](https://github.com/Kynxhel/PeerPulsar/issues).
