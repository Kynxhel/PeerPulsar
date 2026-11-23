# Privacy Policy — Fabric P2P World

**Last Updated:** November 2026

**Fabric P2P World** is an open-source, peer-to-peer Minecraft Fabric mod. We respect your privacy and do not operate any central data-collection servers.

## 1. Google Account & Google Drive Access (Optional Feature)
The **Shared Cloud Worlds** feature is **disabled by default** and is strictly opt-in. If you choose to sign in with Google:
- **Scopes Requested:** `https://www.googleapis.com/auth/drive`, `openid`, `email`, and `profile`.
- **What we access:** The `drive` scope is required by Google Drive API v3 so invited friends can read and sync shared world folders created by another player. The mod only accesses its own `FabricP2P_SharedWorlds` folder and explicitly shared Fabric P2P world subfolders; it never reads, modifies, or deletes any unrelated personal files in your Google Drive.
- **Identity Verification:** When joining a P2P world with Google Sign-In active, the mod sends your Google-signed OIDC `id_token` to the Host, which verifies it directly against `https://oauth2.googleapis.com/tokeninfo` and stores only a SHA-256 hash in the world's `fabricp2p_identities.json` (never plaintext emails).
- **Token Storage:** Your OAuth access token, refresh token, ID token, and email address are stored **locally on your own computer** in `.minecraft/config/fabricp2p_shared_worlds.json`. They are never sent to the mod author or any third-party analytics server.
- **Revoking Access:** You can click **Sign Out** inside the mod at any time to clear local tokens, or revoke access from your [Google Account Permissions](https://myaccount.google.com/permissions).

## 2. Peer-to-Peer Networking
- To establish direct P2P or NAT-traversal connections, the mod may query public STUN servers (`stun.l.google.com`, `stun.cloudflare.com`), a public signaling channel (`ntfy.sh`), or an open-source TCP relay (`bore.pub`) depending on the connection mode you select.
- No personal data, Minecraft credentials, or world files are collected by the mod author.

## 3. Contact
For questions or privacy inquiries, please open an issue on the [GitHub Repository](https://github.com/Kynxhel/fabric-p2p-world/issues).
