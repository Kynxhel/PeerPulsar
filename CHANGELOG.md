# Changelog

## v2.1.2+26.3
- **UDP Hole Punching & RUDP Reliability:** Fixed `DatagramSocket` / `ServerSocket` leaks on signaling or startup errors in `UdpHolePunchService`, and added redundant `TYPE_FIN` transmission on disconnect so peers detect stream closure without waiting for idle timeout.
- **OIDC Verification & Sync Concurrency:** Increased `PlayerIdentityGuard` OIDC verification timeout from 6s to 15s to prevent false kicks during token refresh, and replaced `syncInProgress` with an atomic active-operation counter in `SharedWorldManager` so exit-sync never clears an in-flight manual sync lock.
- **UI Polish & Screen Lifecycle Safety:** Guarded async UDP join callbacks in `P2PJoinScreen` and `SharedWorldsScreen` via `Screen.removed()` so leaving the screen cancels stale auto-connects and cleans up the client proxy, and clamped long toast messages in `NeonRenderUtils` to screen width.

## v2.1.1+26.3
- **IdentityGuard & OIDC Security Hardening:** Fixed MC 26.3 local singleplayer host detection (`isLocalHostPlayer`), blocked world interactions and anchored position during the verification window, added OIDC `aud`/`azp` validation, real JWT `exp` expiry checking on token refresh, and stopped broadcasting Google `id_token` when Shared Worlds is disabled.
- **Networking, RUDP & UPnP Reliability:** Fixed RUDP `TYPE_FIN` drain so server disconnect/kick messages are no longer lost, added client-side UDP IP/session validation and STUN Transaction ID checks, made Local LAN start instantaneously offline without waiting for internet diagnostics, fixed Vanilla LAN auth restoration after stopping Offline P2P, fixed UPnP gateway cache & namespaced XML IP parsing, and fixed `TunnelRelayService` stream tracking and silent-drop detection.
- **Shared Cloud Worlds & Google Drive Stability:** Added concurrent sync locking, snapshot-based SHA-256 hashing/uploads, atomic file downloads, stale/trashed Drive root folder cache recovery, Unicode/Turkish world name preservation with Windows reserved-name protection, and fixed joining live UDP Shared Worlds from inside a singleplayer world.
- **UI, Mixins, Packaging & Tests:** Added screen-bounds handling for TitleScreen and compact host layouts, fixed text overflows and null-safe host labels, filtered `oauth.properties` packaging in `build.gradle`, cleaned unused lang keys, and added JUnit 5 unit tests.

## v2.1.0+26.3
- **Google Account Anti-Spoof Nickname Protection:** Binds each player's username to their Google account (`fabricp2p_identities.json`) so nobody can impersonate another player's nickname in Offline LAN worlds.
- **Automatic Google Drive Auto-Sharing on Join:** When a friend signed into Google joins your Shared Cloud World, they are automatically granted Editor access to the world's Drive folder and auto-linked in their `Shared Worlds` menu — zero manual sharing required!
- **SHA-256 Incremental (Delta) Cloud Sync:** Uploads and downloads only new or modified world files (`manifest.json`), skipping unchanged region files for near-instant sync.

## v2.0.0+26.3
- **Minecraft 26.3 Native Support:** Built natively for Minecraft 26.3 (unobfuscated) with Fabric Loader `>=0.19.5` and Fabric API `0.165.2+26.3`.
- **Neon Control Center UI:** Custom glassmorphism host, join, and shared worlds screens with animated particle background, live signal bars, and toast notifications.
- **Automatic Network & NAT Diagnostics:** Dual-STUN Cone/Symmetric NAT detection, CGNAT (`100.64.0.0/10`) detection, UPnP port-mapping self-test, and tunnel latency check.
- **Right-Side Connection Mode Selector:** 4 selectable modes (`Direct P2P`, `UDP Punch`, `Tunnel Relay`, `Local LAN`) with green/red status dots and automatic `(Recommended)` tagging.
- **UDP Hole Punching Engine:** Direct peer-to-peer Reliable Ordered UDP (RUDP) traversal through Cone CGNAT.
- **Automatic TCP Tunnel Relay:** Built-in `bore.pub` fallback for Symmetric NAT and strict firewalls.
- **Toggleable Shared Cloud Worlds:** Optional rotating-host world sync with Google Drive Sign-In (OAuth 2.0 + PKCE) and local synced folder support.
- **Smart Room Codes & Live Ping:** Auto-extracts room codes from pasted Discord invites and displays live latency/player telemetry.
