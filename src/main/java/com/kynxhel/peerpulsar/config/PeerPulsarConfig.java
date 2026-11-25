package com.kynxhel.peerpulsar.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.client.network.UdpTransportMode;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persistent settings file: {@code config/peerpulsar.json}.
 */
public final class PeerPulsarConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String MODE_AUTO = "auto";
    public static final String MODE_MANUAL = "manual";

    private static PeerPulsarConfig instance;

    /** True once the user agreed to host-side network diagnostics / Test All contacting third-party services. */
    private boolean networkTestConsent = false;
    /** True once the user agreed to join-side UDP Hole Punching contacting STUN and signaling servers. */
    private boolean udpJoinConsent = false;
    private boolean identityInitialized = false;
    private boolean requireRoomKeyOnLan = false;
    private boolean giveJoinersCommands = false;
    private boolean useDefaultPort = false;
    private boolean joinApprovalEnabled = true;
    private String udpTransportMode = UdpTransportMode.AUTO.configValue();
    private boolean autoTransportRecommendations = true;

    private ServiceSection relay = new ServiceSection();
    private ServiceSection signaling = new ServiceSection();
    private ServiceSection stun = new ServiceSection();
    private ServiceSection ipEcho = new ServiceSection();

    private transient Path backingFile;

    public static final class ServiceSection {
        private String mode = MODE_AUTO;
        private String selected = null;
        private List<String> custom = new ArrayList<>();

        public synchronized String getMode() {
            return MODE_MANUAL.equalsIgnoreCase(mode) ? MODE_MANUAL : MODE_AUTO;
        }

        public synchronized void setMode(String mode) {
            this.mode = MODE_MANUAL.equalsIgnoreCase(mode) ? MODE_MANUAL : MODE_AUTO;
        }

        public synchronized String getSelected() {
            return selected;
        }

        public synchronized void setSelected(String selected) {
            this.selected = (selected == null || selected.isBlank()) ? null : selected.trim();
        }

        public synchronized List<String> getCustom() {
            return custom == null ? List.of() : List.copyOf(custom);
        }

        public synchronized void addCustom(String entry) {
            if (entry == null || entry.isBlank()) return;
            if (custom == null) custom = new ArrayList<>();
            String trimmed = entry.trim();
            if (!custom.contains(trimmed)) {
                custom.add(trimmed);
            }
        }

        public synchronized boolean removeCustom(String entry) {
            if (custom == null || entry == null) return false;
            boolean removed = custom.remove(entry);
            if (removed && Objects.equals(selected, entry)) {
                selected = null;
                mode = MODE_AUTO;
            }
            return removed;
        }

        synchronized void normalize() {
            if (!MODE_MANUAL.equalsIgnoreCase(mode)) {
                mode = MODE_AUTO;
            } else {
                mode = MODE_MANUAL;
            }
            if (custom == null) {
                custom = new ArrayList<>();
            } else {
                List<String> cleaned = new ArrayList<>();
                for (String s : custom) {
                    if (s != null && !s.isBlank() && !cleaned.contains(s.trim())) {
                        cleaned.add(s.trim());
                    }
                }
                custom = cleaned;
            }
            if (selected != null && selected.isBlank()) {
                selected = null;
            }
            if (MODE_MANUAL.equals(mode) && selected == null) {
                mode = MODE_AUTO;
            }
        }
    }

    private PeerPulsarConfig() {
        normalize();
    }

    private static Path defaultFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("peerpulsar.json");
    }

    public static synchronized PeerPulsarConfig get() {
        if (instance == null) {
            instance = loadFromFile(defaultFile());
        }
        return instance;
    }

    public static PeerPulsarConfig loadFromFile(Path file) {
        PeerPulsarConfig cfg = null;
        try {
            if (Files.isRegularFile(file)) {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PeerPulsarConfig.class);
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar] Could not read config ({}), using defaults: {}", file, e.getMessage());
        }
        if (cfg == null) {
            cfg = new PeerPulsarConfig();
        }
        cfg.backingFile = file;
        cfg.normalize();
        return cfg;
    }

    private synchronized void normalize() {
        if (relay == null) relay = new ServiceSection();
        if (signaling == null) signaling = new ServiceSection();
        if (stun == null) stun = new ServiceSection();
        if (ipEcho == null) ipEcho = new ServiceSection();
        relay.normalize();
        signaling.normalize();
        stun.normalize();
        ipEcho.normalize();
        udpTransportMode = UdpTransportMode.fromConfigString(udpTransportMode).configValue();
    }

    public synchronized boolean hasNetworkTestConsent() {
        return networkTestConsent;
    }

    public synchronized void setNetworkTestConsent(boolean consent) {
        this.networkTestConsent = consent;
        save();
    }

    public synchronized boolean hasUdpJoinConsent() {
        return udpJoinConsent;
    }

    public synchronized void setUdpJoinConsent(boolean consent) {
        this.udpJoinConsent = consent;
        save();
    }

    public synchronized boolean isIdentityInitialized() {
        return identityInitialized;
    }

    public synchronized void setIdentityInitialized(boolean identityInitialized) {
        this.identityInitialized = identityInitialized;
        save();
    }

    public synchronized boolean isRequireRoomKeyOnLan() {
        return requireRoomKeyOnLan;
    }

    public synchronized void setRequireRoomKeyOnLan(boolean requireRoomKeyOnLan) {
        this.requireRoomKeyOnLan = requireRoomKeyOnLan;
        save();
    }

    public synchronized boolean isGiveJoinersCommands() {
        return giveJoinersCommands;
    }

    public synchronized void setGiveJoinersCommands(boolean giveJoinersCommands) {
        this.giveJoinersCommands = giveJoinersCommands;
        save();
    }

    public synchronized boolean isUseDefaultPort() {
        return useDefaultPort;
    }

    public synchronized void setUseDefaultPort(boolean useDefaultPort) {
        this.useDefaultPort = useDefaultPort;
        save();
    }

    public synchronized boolean isJoinApprovalEnabled() {
        return joinApprovalEnabled;
    }

    public synchronized void setJoinApprovalEnabled(boolean joinApprovalEnabled) {
        this.joinApprovalEnabled = joinApprovalEnabled;
        save();
    }

    public synchronized UdpTransportMode getUdpTransportModeEnum() {
        return UdpTransportMode.fromConfigString(udpTransportMode);
    }

    public synchronized void setUdpTransportMode(UdpTransportMode mode) {
        this.udpTransportMode = (mode != null ? mode : UdpTransportMode.AUTO).configValue();
        save();
    }

    public synchronized boolean isAutoTransportRecommendations() {
        return autoTransportRecommendations;
    }

    public synchronized void setAutoTransportRecommendations(boolean autoTransportRecommendations) {
        this.autoTransportRecommendations = autoTransportRecommendations;
        save();
    }

    public ServiceSection getRelaySection() {
        return relay;
    }

    public ServiceSection getSignalingSection() {
        return signaling;
    }

    public ServiceSection getStunSection() {
        return stun;
    }

    public ServiceSection getIpEchoSection() {
        return ipEcho;
    }

    public synchronized void save() {
        Path target = backingFile != null ? backingFile : defaultFile();
        saveToFile(target);
    }

    public synchronized void saveToFile(Path file) {
        try {
            normalize();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar] Could not save config: {}", e.getMessage());
        }
    }
}
