# 🌐 Fabric P2P World (Minecraft 26.3)

An open-source, zero-server-cost **Peer-to-Peer World Hosting** mod for **Minecraft 26.3 (Fabric)** with a Neon Control Center UI, automatic NAT/CGNAT diagnostics, Essential-style **UDP Hole Punching**, automatic **TCP Tunnel Relay**, **Offline/Online (Crack + Premium)** compatibility, and optional **Shared Cloud Worlds** (Google Drive / OneDrive / Syncthing rotating host).

---

## ✨ Key Features

- **4 Selectable Connection Modes** (with live Green/Red status dots & automatic `(Recommended)` fastest-mode selection):
  - **Direct P2P (UPnP):** Automatic router port-forwarding (`0ms` overhead) for Open NAT networks.
  - **UDP Hole Punch:** Essential-style direct UDP P2P (`0ms` relay overhead) using Dual-STUN + Reliable Ordered UDP — works right through **Cone CGNAT**!
  - **Tunnel Relay (`bore.pub`):** Automatic TCP tunnel fallback that works behind **Symmetric NAT**, university/mobile networks, and strict firewalls.
  - **Local LAN:** Instant same-network / VPN hosting.
- **Automatic Network & CGNAT Diagnostics:**
  - Tests public IP, UPnP port mapping, CGNAT (`100.64.0.0/10`), Cone vs. Symmetric NAT (Dual-STUN), and relay latency automatically.
- **Offline & Online Account Support:**
  - Toggleable `Offline LAN Auth` allows both original Microsoft/Mojang and offline accounts to play together seamlessly.
- **Smart Room Codes & Discord Invites:**
  - Share clean `P2P-XXXX-XXXX-XXXX` or `UDP-XXXX-XXXX-XXXX` room codes, or paste an entire Discord invite block into the Join screen (auto-extracted!).
- **Toggleable Shared Cloud Worlds (`☁ Shared Worlds`):**
  - Optional feature to sync a world via **Google Drive** (built-in Sign in with Google) or any local synced folder (Google Drive Desktop, OneDrive, Dropbox, Syncthing).
  - Rotating Host Lockfile (`session.lock.json`): whoever launches first becomes Host; friends automatically see **`● LIVE`** and join with 1 click!

---

## 🛠️ Requirements & Build

- **Minecraft:** `26.3`
- **Fabric Loader:** `>= 0.19.5`
- **Fabric API:** `0.165.2+26.3`
- **Java:** `Java 21+`

```bash
./gradlew build
```

The compiled JAR is generated in `build/libs/`.

---

## 🔒 Privacy Policy

See [PRIVACY.md](PRIVACY.md) for details on the optional Google Drive integration and P2P networking.

## 📄 License

Licensed under the [MIT License](LICENSE).
