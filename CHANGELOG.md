# Changelog

## v2.0.0+26.3
- **Minecraft 26.3 Native Support:** Built natively for Minecraft 26.3 (unobfuscated) with Fabric Loader `>=0.19.5` and Fabric API `0.165.2+26.3`.
- **Neon Control Center UI:** Custom glassmorphism host, join, and shared worlds screens with animated particle background, live signal bars, and toast notifications.
- **Automatic Network & NAT Diagnostics:** Dual-STUN Cone/Symmetric NAT detection, CGNAT (`100.64.0.0/10`) detection, UPnP port-mapping self-test, and tunnel latency check.
- **Right-Side Connection Mode Selector:** 4 selectable modes (`Direct P2P`, `UDP Punch`, `Tunnel Relay`, `Local LAN`) with green/red status dots and automatic `(Recommended)` tagging.
- **UDP Hole Punching Engine:** Direct peer-to-peer Reliable Ordered UDP (RUDP) traversal through Cone CGNAT.
- **Automatic TCP Tunnel Relay:** Built-in `bore.pub` fallback for Symmetric NAT and strict firewalls.
- **Toggleable Shared Cloud Worlds:** Optional rotating-host world sync with Google Drive Sign-In (OAuth 2.0 + PKCE) and local synced folder support.
- **Smart Room Codes & Live Ping:** Auto-extracts room codes from pasted Discord invites and displays live latency/player telemetry.
