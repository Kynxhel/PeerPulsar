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
- Host-side **Network Diagnostics** consent (`networkTestConsent`) and join-side **UDP P2P Connection Consent** (`udpJoinConsent`) are tracked independently in `config/peerpulsar.json`. Both prompts explicitly name `bore.pub` (fallback TCP relay), `ntfy.sh` (AES-256-GCM encrypted UDP signaling), and public STUN / IP echo servers (`stun.l.google.com`, `stun.cloudflare.com`, etc.) as optional free third-party defaults changeable in **⚙ Advanced**. Trusting a one-off custom signaling invite never silently grants host diagnostics or default UDP consent.
- PeerPulsar disables HTTP redirects and enforces public-only validation plus DNS-rebinding pinned resolution (blocking loopback, link-local, RFC1918, CGNAT, and ULA targets) across custom HTTPS signaling/IP-echo URLs as well as custom Tunnel Relay and STUN `host:port` endpoints. Joining a UDP room code with a custom signaling server always prompts you with an explicit confirmation naming both the custom signaling domain and configured STUN servers before connecting.
- PeerPulsar never writes public IP addresses, peer IP addresses, signaling topic IDs, or `#KEY` room secrets to `latest.log` or host chat logs.
- During the opt-in **Network Test**, PeerPulsar sends local SSDP multicast packets and briefly tests a temporary 60-second UPnP port mapping (immediately deleted after verification). UPnP SSDP/SOAP requests are strictly restricted to the responding local gateway IP.

### Zero Session Token Access & End-to-End Encrypted Offline LAN Auth
- **Zero Session Token Access:** PeerPulsar **never** reads `User.getAccessToken()` or contacts Mojang session servers on behalf of clients.
- **End-to-End Encrypted Offline LAN Auth:** **Mojang Online Auth is enabled by default.** When Offline LAN Auth is explicitly enabled by the host, PeerPulsar keeps Minecraft's native RSA + AES-CFB8 transport encryption active and channel-bound via SHA-256 host key hash into the `#KEY` HMAC and Ed25519 signature — protecting gameplay traffic against MITM and eavesdropping even over `bore.pub` or UDP Hole Punch. (Under RUDP/KCP, lightweight UDP transport headers remain unencrypted for NAT routing while the Minecraft stream inside is encrypted; under **QUIC (Kwik)**, transport packets are additionally encrypted with TLS 1.3 using an ephemeral P-256 certificate pinned via AES-256-GCM signaling.)

## 2. Room Codes & IP Address Disclosure
- **Direct P2P (UPnP)** and **Local LAN** room codes (`P2P-...`) encode the host's IP address and port (XOR/Base64url encoded, not encrypted) so peers can connect directly. Share Direct P2P codes only with people you trust, or use **Tunnel Relay** if you prefer to hide your IP address.
- **Tunnel Relay** room codes encode the relay server address rather than your home IP address.
- **UDP Hole Punch** room codes (`UDP-...`) do not contain your IP address; peer endpoints are exchanged directly inside AES-256-GCM encrypted signaling messages.

## 3. Local Files Stored on Your Device
PeerPulsar stores only local files on your machine — nothing is uploaded to the mod author:
- `config/peerpulsar.json`: Stores your independent opt-in consent flags (`networkTestConsent`, `udpJoinConsent`), Advanced endpoint settings, and security toggles.
- `config/peerpulsar/identity.json`: A locally generated Ed25519 key pair (saved with owner-only `0600` permissions on POSIX) used for device verification, name locks, and key-based offline UUIDs. Exporting a backup from **Advanced → Identity** writes to `~/.peerpulsar/peerpulsar-identity-backup.json` (outside `.minecraft` so modpack exports never bundle your private key).
- `<world>/peerpulsar_name_locks.json`: Stores SHA-256 fingerprints of public keys bound to player names when Offline LAN Auth is used.
- `<world>/peerpulsar_approved.json`: Stores host-approved players (name, device key fingerprint, and Mojang UUID if applicable) when Join Approval "Always" is used.
- `<world>/peerpulsar_identity_map.json` & `<world>/peerpulsar_backups/`: Records one-time legacy playerdata migrations and stores automatic backups before any migration or host-initiated data transfer.

## 4. Contact
For questions or privacy inquiries, please open an issue on the [GitHub Repository](https://github.com/Kynxhel/PeerPulsar/issues).
