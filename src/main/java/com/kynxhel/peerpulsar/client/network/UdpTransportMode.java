package com.kynxhel.peerpulsar.client.network;

/**
 * UDP P2P transport engine selection modes.
 */
public enum UdpTransportMode {
    AUTO((byte) 0, "auto", "Auto"),
    RUDP((byte) 1, "rudp", "RUDP"),
    KCP((byte) 2, "kcp", "KCP"),
    QUIC((byte) 3, "quic", "QUIC");

    private final byte wireId;
    private final String configValue;
    private final String displayName;

    UdpTransportMode(byte wireId, String configValue, String displayName) {
        this.wireId = wireId;
        this.configValue = configValue;
        this.displayName = displayName;
    }

    public byte wireId() {
        return wireId;
    }

    public String configValue() {
        return configValue;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isConcrete() {
        return this != AUTO;
    }

    public static UdpTransportMode fromWireId(byte id) {
        for (UdpTransportMode m : values()) {
            if (m.wireId == id) return m;
        }
        return RUDP;
    }

    public static UdpTransportMode fromConfigString(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        String norm = raw.trim().toLowerCase();
        for (UdpTransportMode m : values()) {
            if (m.configValue.equals(norm) || m.name().equalsIgnoreCase(norm)) {
                return m;
            }
        }
        return AUTO;
    }
}
