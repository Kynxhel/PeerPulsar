package com.kynxhel.peerpulsar.client.gui.screen;

import com.kynxhel.peerpulsar.client.gui.fx.NeonRenderUtils;
import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.client.gui.fx.ParticleBackground;
import com.kynxhel.peerpulsar.client.gui.widget.NeonButton;
import com.kynxhel.peerpulsar.client.gui.widget.NeonToggleCard;
import com.kynxhel.peerpulsar.client.network.NetworkEndpoints;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import com.kynxhel.peerpulsar.security.ApprovedPlayerStore;
import com.kynxhel.peerpulsar.security.ClientIdentity;
import com.kynxhel.peerpulsar.security.IdentityMapStore;
import com.kynxhel.peerpulsar.security.NameLockStore;
import com.kynxhel.peerpulsar.security.PlayerDataMigrator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Advanced configuration screen:
 * - Network Services (Tunnel Relay, UDP Signaling, STUN / Public IP) with Auto failover, Test All, and custom endpoints
 * - Security (LAN room key, guest commands, port selection, Approved/Known Players + Transfer/Remove)
 * - Identity (Ed25519 key status, Export/Import backup)
 */
public class P2PAdvancedScreen extends Screen {
    public enum Tab {
        NETWORK,
        SECURITY,
        IDENTITY
    }

    public enum NetworkSection {
        RELAY,
        SIGNALING,
        STUN_IP
    }

    private record ListItem(String label, String endpoint, boolean isCustom, boolean isAuto, boolean isIpEcho) {}

    private record PlayerRow(String name, String keyFingerprint, String effectiveUuid, long lastSeenMs, boolean approved) {}

    private record TransferCandidate(UUID uuid, String label) {}

    private final Screen parent;
    private final ParticleBackground particleBg = new ParticleBackground();
    private Tab activeTab;
    private NetworkSection activeSection = NetworkSection.RELAY;

    private final Map<String, Long> latencyCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> testingCache = new ConcurrentHashMap<>();

    private NeonButton tabNetworkBtn;
    private NeonButton tabSecurityBtn;
    private NeonButton tabIdentityBtn;

    private NeonButton secRelayBtn;
    private NeonButton secSignalBtn;
    private NeonButton secStunBtn;
    private NeonButton testAllBtn;

    private EditBox customInput;
    private NeonButton customTestBtn;
    private NeonButton customAddBtn;
    private String customValidationError = null;
    private String customTestStatus = null;

    private NeonToggleCard lanKeyCard;
    private NeonToggleCard guestCmdCard;
    private NeonToggleCard portModeCard;

    private NeonButton exportIdBtn;
    private NeonButton importIdBtn;
    private NeonButton recreateIdBtn;
    private NeonButton doneBtn;

    private int networkScroll = 0;
    private int playersScroll = 0;
    private PlayerRow transferTargetRow = null;
    private List<TransferCandidate> transferCandidates = List.of();

    private String toastMessage = null;
    private int toastAccent = NeonTheme.CYAN;
    private long toastStartMs = 0L;

    private int panelLeft;
    private int panelW;
    private int topY;
    private int bodyY;
    private int bodyH;

    public P2PAdvancedScreen(Screen parent) {
        this(parent, Tab.NETWORK);
    }

    public P2PAdvancedScreen(Screen parent, Tab initialTab) {
        super(Component.translatable("screen.peerpulsar.advanced.title"));
        this.parent = parent;
        this.activeTab = initialTab != null ? initialTab : Tab.NETWORK;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void computeLayout() {
        this.panelW = Math.min(440, this.width - 16);
        this.panelLeft = (this.width - this.panelW) / 2;
        int totalH = Math.min(286, this.height - 16);
        this.topY = Math.max(8, (this.height - totalH) / 2);
        this.bodyY = topY + 52;
        this.bodyH = Math.max(150, totalH - 82);
    }

    @Override
    protected void init() {
        computeLayout();
        this.clearWidgets();

        int tabY = topY + 26;
        int tabGap = 6;
        int tabW = (panelW - tabGap * 2) / 3;

        this.tabNetworkBtn = new NeonButton(panelLeft, tabY, tabW, 20,
                Component.translatable("screen.peerpulsar.advanced.tab_network"),
                NeonTheme.CYAN, () -> switchTab(Tab.NETWORK));
        this.tabSecurityBtn = new NeonButton(panelLeft + tabW + tabGap, tabY, tabW, 20,
                Component.translatable("screen.peerpulsar.advanced.tab_security"),
                NeonTheme.EMERALD, () -> switchTab(Tab.SECURITY));
        this.tabIdentityBtn = new NeonButton(panelLeft + (tabW + tabGap) * 2, tabY, tabW, 20,
                Component.translatable("screen.peerpulsar.advanced.tab_identity"),
                NeonTheme.VIOLET, () -> switchTab(Tab.IDENTITY));
        this.addRenderableWidget(tabNetworkBtn);
        this.addRenderableWidget(tabSecurityBtn);
        this.addRenderableWidget(tabIdentityBtn);

        if (activeTab == Tab.NETWORK) {
            initNetworkTab();
        } else if (activeTab == Tab.SECURITY) {
            initSecurityTab();
        } else {
            initIdentityTab();
        }

        int doneY = bodyY + bodyH + 6;
        this.doneBtn = new NeonButton(this.width / 2 - 75, doneY, 150, 20,
                Component.literal("← ").append(Component.translatable("gui.done")),
                NeonTheme.SLATE,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(this.parent);
                    }
                });
        this.addRenderableWidget(doneBtn);
        syncTabButtons();
    }

    private void initNetworkTab() {
        int secY = bodyY + 8;
        int testW = 66;
        int availW = panelW - 16 - testW - 6;
        int secGap = 4;
        int secW = (availW - secGap * 2) / 3;

        this.secRelayBtn = new NeonButton(panelLeft + 8, secY, secW, 18,
                Component.translatable("screen.peerpulsar.advanced.sec_relay"),
                NeonTheme.VIOLET, () -> switchSection(NetworkSection.RELAY));
        this.secSignalBtn = new NeonButton(panelLeft + 8 + secW + secGap, secY, secW, 18,
                Component.translatable("screen.peerpulsar.advanced.sec_signaling"),
                NeonTheme.CYAN, () -> switchSection(NetworkSection.SIGNALING));
        this.secStunBtn = new NeonButton(panelLeft + 8 + (secW + secGap) * 2, secY, secW, 18,
                Component.translatable("screen.peerpulsar.advanced.sec_stun"),
                NeonTheme.EMERALD, () -> switchSection(NetworkSection.STUN_IP));
        this.testAllBtn = new NeonButton(panelLeft + panelW - 8 - testW, secY, testW, 18,
                Component.translatable("screen.peerpulsar.advanced.btn_test_all"),
                NeonTheme.AMBER, () -> withNetworkConsent(this::runTestAllInSection));

        this.addRenderableWidget(secRelayBtn);
        this.addRenderableWidget(secSignalBtn);
        this.addRenderableWidget(secStunBtn);
        this.addRenderableWidget(testAllBtn);

        int inputY = bodyY + bodyH - 34;
        int btnW = 46;
        int boxW = panelW - 16 - (btnW * 2) - 10;
        String prevText = this.customInput != null ? this.customInput.getValue() : "";
        this.customInput = new EditBox(this.font, panelLeft + 12, inputY + 4, boxW - 8, 12, Component.literal("Custom Endpoint"));
        this.customInput.setMaxLength(160);
        this.customInput.setBordered(false);
        this.customInput.setTextColor(NeonTheme.TEXT_PRIMARY);
        this.customInput.setHint(Component.translatable(
                activeSection == NetworkSection.SIGNALING
                        ? "screen.peerpulsar.advanced.hint_https"
                        : (activeSection == NetworkSection.RELAY
                        ? "screen.peerpulsar.advanced.hint_hostport"
                        : "screen.peerpulsar.advanced.hint_stun_or_ip")
        ).withStyle(ChatFormatting.DARK_GRAY));
        this.customInput.setValue(prevText);
        this.customInput.setResponder(text -> {
            this.customTestStatus = null;
            validateCustomInput(text);
        });
        this.addRenderableWidget(customInput);

        this.customTestBtn = new NeonButton(panelLeft + 8 + boxW + 4, inputY, btnW, 18,
                Component.translatable("screen.peerpulsar.advanced.btn_test"),
                NeonTheme.CYAN, () -> withNetworkConsent(this::testCustomEndpoint));
        this.customAddBtn = new NeonButton(panelLeft + 8 + boxW + 4 + btnW + 4, inputY, btnW, 18,
                Component.translatable("screen.peerpulsar.advanced.btn_add"),
                NeonTheme.EMERALD, this::addCustomEndpoint);
        this.addRenderableWidget(customTestBtn);
        this.addRenderableWidget(customAddBtn);

        validateCustomInput(prevText);
        syncSectionButtons();
    }

    private void initSecurityTab() {
        PeerPulsarConfig cfg = PeerPulsarConfig.get();
        P2PNetworkManager mgr = P2PNetworkManager.getInstance();
        int cardGap = 6;
        int cardW = (panelW - 16 - cardGap * 2) / 3;
        int cardY = bodyY + 8;
        int cardH = 32;

        this.lanKeyCard = new NeonToggleCard(
                panelLeft + 8, cardY, cardW, cardH,
                tr("screen.peerpulsar.advanced.card_lan_key"),
                () -> cfg.isRequireRoomKeyOnLan() ? "ON" : "OFF",
                cfg::isRequireRoomKeyOnLan,
                () -> cfg.isRequireRoomKeyOnLan() ? NeonTheme.EMERALD : NeonTheme.SLATE,
                () -> cfg.setRequireRoomKeyOnLan(!cfg.isRequireRoomKeyOnLan())
        );
        this.guestCmdCard = new NeonToggleCard(
                panelLeft + 8 + cardW + cardGap, cardY, cardW, cardH,
                tr("screen.peerpulsar.advanced.card_guest_cmd"),
                () -> cfg.isGiveJoinersCommands() ? "ON" : "OFF",
                cfg::isGiveJoinersCommands,
                () -> cfg.isGiveJoinersCommands() ? NeonTheme.AMBER : NeonTheme.SLATE,
                () -> {
                    boolean next = !cfg.isGiveJoinersCommands();
                    cfg.setGiveJoinersCommands(next);
                    if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
                        IntegratedServer srv = this.minecraft.getSingleplayerServer();
                        srv.execute(() -> srv.setGuestCommandAccess(next));
                    }
                }
        );
        this.portModeCard = new NeonToggleCard(
                panelLeft + 8 + (cardW + cardGap) * 2, cardY, cardW, cardH,
                tr("screen.peerpulsar.host.card_port_title"),
                () -> mgr.isUseDefaultPort()
                        ? tr("screen.peerpulsar.host.card_port_default")
                        : tr("screen.peerpulsar.host.card_port_random"),
                mgr::isUseDefaultPort,
                () -> mgr.isUseDefaultPort() ? NeonTheme.CYAN : NeonTheme.VIOLET,
                () -> {
                    if (!mgr.isP2PActive() && !mgr.isStarting()) {
                        mgr.setUseDefaultPort(!mgr.isUseDefaultPort());
                    } else {
                        showToast(tr("screen.peerpulsar.advanced.toast_stop_to_change_port"), NeonTheme.AMBER);
                    }
                }
        );
        this.portModeCard.active = !mgr.isP2PActive() && !mgr.isStarting();

        this.addRenderableWidget(lanKeyCard);
        this.addRenderableWidget(guestCmdCard);
        this.addRenderableWidget(portModeCard);
    }

    private void initIdentityTab() {
        int btnW = Math.min(180, panelW - 32);
        int btnX = this.width / 2 - btnW / 2;
        int startY = bodyY + 66;

        this.exportIdBtn = new NeonButton(btnX, startY, btnW, 22,
                Component.translatable("screen.peerpulsar.identity.btn_export"),
                NeonTheme.CYAN, this::exportIdentity);
        this.importIdBtn = new NeonButton(btnX, startY + 28, btnW, 22,
                Component.translatable("screen.peerpulsar.identity.btn_import"),
                NeonTheme.VIOLET, this::importIdentity);
        this.recreateIdBtn = new NeonButton(btnX, startY + 56, btnW, 20,
                Component.translatable("screen.peerpulsar.identity.btn_create_new"),
                NeonTheme.AMBER, () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new ConfirmScreen(
                                ok -> {
                                    if (ok) {
                                        ClientIdentity.recreateAndReplace();
                                        showToast(tr("screen.peerpulsar.identity.toast_recreated"), NeonTheme.AMBER);
                                    }
                                    if (this.minecraft != null) {
                                        this.minecraft.setScreenAndShow(this);
                                    }
                                },
                                Component.translatable("screen.peerpulsar.identity.recovery_title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                                Component.translatable("screen.peerpulsar.identity.confirm_recreate"),
                                Component.translatable("screen.peerpulsar.identity.btn_create_new"),
                                Component.translatable("gui.cancel")
                        ));
                    }
                });

        this.addRenderableWidget(exportIdBtn);
        this.addRenderableWidget(importIdBtn);
        this.addRenderableWidget(recreateIdBtn);
    }

    private void switchTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.transferTargetRow = null;
        this.init();
    }

    private void switchSection(NetworkSection sec) {
        if (this.activeSection == sec) return;
        this.activeSection = sec;
        this.networkScroll = 0;
        this.customValidationError = null;
        this.customTestStatus = null;
        this.init();
    }

    private void syncTabButtons() {
        if (tabNetworkBtn != null) tabNetworkBtn.setPulsingBeacon(activeTab == Tab.NETWORK);
        if (tabSecurityBtn != null) tabSecurityBtn.setPulsingBeacon(activeTab == Tab.SECURITY);
        if (tabIdentityBtn != null) tabIdentityBtn.setPulsingBeacon(activeTab == Tab.IDENTITY);
    }

    private void syncSectionButtons() {
        if (secRelayBtn != null) secRelayBtn.setPulsingBeacon(activeSection == NetworkSection.RELAY);
        if (secSignalBtn != null) secSignalBtn.setPulsingBeacon(activeSection == NetworkSection.SIGNALING);
        if (secStunBtn != null) secStunBtn.setPulsingBeacon(activeSection == NetworkSection.STUN_IP);
    }

    private void withNetworkConsent(Runnable action) {
        if (PeerPulsarConfig.get().hasNetworkTestConsent()) {
            action.run();
            return;
        }
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) {
                            PeerPulsarConfig.get().setNetworkTestConsent(true);
                        }
                        if (this.minecraft != null) {
                            this.minecraft.setScreenAndShow(this);
                        }
                        if (confirmed) {
                            action.run();
                        }
                    },
                    Component.translatable("screen.peerpulsar.host.consent_diag_title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.translatable("screen.peerpulsar.host.consent_diag_body"),
                    Component.translatable("screen.peerpulsar.host.consent_diag_confirm"),
                    Component.translatable("gui.cancel")
            ));
        }
    }

    private void validateCustomInput(String raw) {
        if (raw == null || raw.isBlank()) {
            this.customValidationError = null;
            if (customAddBtn != null) customAddBtn.active = false;
            if (customTestBtn != null) customTestBtn.active = false;
            return;
        }
        boolean valid = switch (activeSection) {
            case RELAY -> NetworkEndpoints.isValidPublicHostPort(raw);
            case SIGNALING -> NetworkEndpoints.normalizePublicHttpsUrl(raw) != null;
            case STUN_IP -> NetworkEndpoints.isValidPublicHostPort(raw) || NetworkEndpoints.normalizePublicHttpsUrl(raw) != null;
        };
        if (!valid) {
            this.customValidationError = switch (activeSection) {
                case RELAY -> tr("screen.peerpulsar.advanced.err_hostport");
                case SIGNALING -> tr("screen.peerpulsar.advanced.err_https");
                case STUN_IP -> tr("screen.peerpulsar.advanced.err_stun_or_ip");
            };
        } else {
            this.customValidationError = null;
        }
        if (customAddBtn != null) customAddBtn.active = valid;
        if (customTestBtn != null) customTestBtn.active = valid;
    }

    private void testCustomEndpoint() {
        if (customInput == null) return;
        String raw = customInput.getValue().trim();
        validateCustomInput(raw);
        if (customValidationError != null || raw.isEmpty()) return;

        this.customTestStatus = tr("screen.peerpulsar.advanced.testing_short");
        pingEndpointAsync(raw, activeSection).whenComplete((ms, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                long latency = (err == null && ms != null) ? ms : -1L;
                this.latencyCache.put(rawNormalized(raw, activeSection), latency);
                this.customTestStatus = latency >= 0
                        ? tr("screen.peerpulsar.advanced.test_ok", latency)
                        : tr("screen.peerpulsar.advanced.test_fail");
            });
        });
    }

    private void addCustomEndpoint() {
        if (customInput == null) return;
        String raw = customInput.getValue().trim();
        validateCustomInput(raw);
        if (customValidationError != null || raw.isEmpty()) return;

        PeerPulsarConfig cfg = PeerPulsarConfig.get();
        if (activeSection == NetworkSection.RELAY) {
            NetworkEndpoints.HostPort hp = NetworkEndpoints.parsePublicHostPort(raw);
            if (hp != null) {
                cfg.getRelaySection().addCustom(hp.formatted());
                cfg.save();
            }
        } else if (activeSection == NetworkSection.SIGNALING) {
            String url = NetworkEndpoints.normalizePublicHttpsUrl(raw);
            if (url != null) {
                cfg.getSignalingSection().addCustom(url);
                cfg.save();
            }
        } else {
            String url = NetworkEndpoints.normalizePublicHttpsUrl(raw);
            if (url != null) {
                cfg.getIpEchoSection().addCustom(url);
                cfg.save();
            } else {
                NetworkEndpoints.HostPort hp = NetworkEndpoints.parsePublicHostPort(raw);
                if (hp != null) {
                    cfg.getStunSection().addCustom(hp.formatted());
                    cfg.save();
                }
            }
        }
        this.customInput.setValue("");
        this.customTestStatus = null;
        showToast(tr("screen.peerpulsar.advanced.toast_added"), NeonTheme.EMERALD);
    }

    private String rawNormalized(String raw, NetworkSection section) {
        if (section == NetworkSection.SIGNALING) {
            String n = NetworkEndpoints.normalizePublicHttpsUrl(raw);
            return n != null ? n : raw;
        }
        String n = NetworkEndpoints.normalizePublicHttpsUrl(raw);
        if (n != null) return n;
        NetworkEndpoints.HostPort hp = NetworkEndpoints.parsePublicHostPort(raw);
        return hp != null ? hp.formatted() : raw;
    }

    private void runTestAllInSection() {
        for (ListItem item : buildNetworkItems()) {
            if (item.isAuto()) continue;
            String ep = item.endpoint();
            testingCache.put(ep, true);
            CompletableFuture<Long> fut = item.isIpEcho()
                    ? NetworkEndpoints.pingIpEchoAsync(ep)
                    : pingEndpointAsync(ep, activeSection);
            fut.whenComplete((ms, err) -> {
                testingCache.remove(ep);
                latencyCache.put(ep, (err == null && ms != null) ? ms : -1L);
            });
        }
    }

    private CompletableFuture<Long> pingEndpointAsync(String endpoint, NetworkSection section) {
        return switch (section) {
            case RELAY -> NetworkEndpoints.pingRelayAsync(endpoint);
            case SIGNALING -> NetworkEndpoints.pingSignalingAsync(endpoint);
            case STUN_IP -> NetworkEndpoints.isValidHttpsUrl(endpoint)
                    ? NetworkEndpoints.pingIpEchoAsync(endpoint)
                    : NetworkEndpoints.pingStunAsync(endpoint);
        };
    }

    private List<ListItem> buildNetworkItems() {
        PeerPulsarConfig cfg = PeerPulsarConfig.get();
        List<ListItem> items = new ArrayList<>();
        items.add(new ListItem(tr("screen.peerpulsar.advanced.auto_recommended"), null, false, true, false));

        if (activeSection == NetworkSection.RELAY) {
            for (String s : NetworkEndpoints.DEFAULT_RELAYS) {
                items.add(new ListItem(s, s, false, false, false));
            }
            for (String c : cfg.getRelaySection().getCustom()) {
                items.add(new ListItem(c, c, true, false, false));
            }
        } else if (activeSection == NetworkSection.SIGNALING) {
            for (String s : NetworkEndpoints.DEFAULT_SIGNALING) {
                items.add(new ListItem(s, s, false, false, false));
            }
            for (String c : cfg.getSignalingSection().getCustom()) {
                items.add(new ListItem(c, c, true, false, false));
            }
        } else {
            for (String s : NetworkEndpoints.DEFAULT_STUN) {
                items.add(new ListItem("STUN: " + s, s, false, false, false));
            }
            for (String c : cfg.getStunSection().getCustom()) {
                items.add(new ListItem("STUN: " + c, c, true, false, false));
            }
            for (String s : NetworkEndpoints.DEFAULT_IP_ECHO) {
                items.add(new ListItem("IP: " + s, s, false, false, true));
            }
            for (String c : cfg.getIpEchoSection().getCustom()) {
                items.add(new ListItem("IP: " + c, c, true, false, true));
            }
        }
        return items;
    }

    private boolean isItemSelected(ListItem item) {
        PeerPulsarConfig cfg = PeerPulsarConfig.get();
        if (activeSection == NetworkSection.RELAY) {
            PeerPulsarConfig.ServiceSection s = cfg.getRelaySection();
            return item.isAuto() ? PeerPulsarConfig.MODE_AUTO.equals(s.getMode()) : (PeerPulsarConfig.MODE_MANUAL.equals(s.getMode()) && item.endpoint().equalsIgnoreCase(s.getSelected()));
        }
        if (activeSection == NetworkSection.SIGNALING) {
            PeerPulsarConfig.ServiceSection s = cfg.getSignalingSection();
            return item.isAuto() ? PeerPulsarConfig.MODE_AUTO.equals(s.getMode()) : (PeerPulsarConfig.MODE_MANUAL.equals(s.getMode()) && item.endpoint().equalsIgnoreCase(s.getSelected()));
        }
        if (item.isAuto()) {
            return PeerPulsarConfig.MODE_AUTO.equals(cfg.getStunSection().getMode())
                    && PeerPulsarConfig.MODE_AUTO.equals(cfg.getIpEchoSection().getMode());
        }
        PeerPulsarConfig.ServiceSection targetSec = item.isIpEcho() ? cfg.getIpEchoSection() : cfg.getStunSection();
        return PeerPulsarConfig.MODE_MANUAL.equals(targetSec.getMode())
                && item.endpoint().equalsIgnoreCase(targetSec.getSelected());
    }

    private void selectItem(ListItem item) {
        PeerPulsarConfig cfg = PeerPulsarConfig.get();
        if (activeSection == NetworkSection.RELAY) {
            if (item.isAuto()) cfg.getRelaySection().setMode(PeerPulsarConfig.MODE_AUTO);
            else {
                cfg.getRelaySection().setMode(PeerPulsarConfig.MODE_MANUAL);
                cfg.getRelaySection().setSelected(item.endpoint());
            }
        } else if (activeSection == NetworkSection.SIGNALING) {
            if (item.isAuto()) cfg.getSignalingSection().setMode(PeerPulsarConfig.MODE_AUTO);
            else {
                cfg.getSignalingSection().setMode(PeerPulsarConfig.MODE_MANUAL);
                cfg.getSignalingSection().setSelected(item.endpoint());
            }
        } else {
            if (item.isAuto()) {
                cfg.getStunSection().setMode(PeerPulsarConfig.MODE_AUTO);
                cfg.getIpEchoSection().setMode(PeerPulsarConfig.MODE_AUTO);
            } else if (item.isIpEcho()) {
                cfg.getIpEchoSection().setMode(PeerPulsarConfig.MODE_MANUAL);
                cfg.getIpEchoSection().setSelected(item.endpoint());
            } else {
                cfg.getStunSection().setMode(PeerPulsarConfig.MODE_MANUAL);
                cfg.getStunSection().setSelected(item.endpoint());
            }
        }
        cfg.save();
    }

    private void deleteCustomItem(ListItem item) {
        PeerPulsarConfig cfg = PeerPulsarConfig.get();
        if (activeSection == NetworkSection.RELAY) {
            cfg.getRelaySection().removeCustom(item.endpoint());
        } else if (activeSection == NetworkSection.SIGNALING) {
            cfg.getSignalingSection().removeCustom(item.endpoint());
        } else if (item.isIpEcho()) {
            cfg.getIpEchoSection().removeCustom(item.endpoint());
        } else {
            cfg.getStunSection().removeCustom(item.endpoint());
        }
        cfg.save();
    }

    private Path currentWorldDir() {
        if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) return null;
        return this.minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
    }

    private List<PlayerRow> loadPlayerRows() {
        Path worldDir = currentWorldDir();
        if (worldDir == null) return List.of();
        Map<String, PlayerRow> byFp = new LinkedHashMap<>();
        for (ApprovedPlayerStore.ApprovedEntry e : ApprovedPlayerStore.list(worldDir)) {
            if (e == null || e.keyFingerprint() == null) continue;
            byFp.put(e.keyFingerprint().toLowerCase(), new PlayerRow(
                    e.name(), e.keyFingerprint(), e.effectiveUuid(), e.lastSeenMs(), true
            ));
        }
        for (IdentityMapStore.IdentityRecord r : IdentityMapStore.list(worldDir)) {
            if (r == null || r.keyFingerprint() == null) continue;
            String key = r.keyFingerprint().toLowerCase();
            if (!byFp.containsKey(key)) {
                byFp.put(key, new PlayerRow(
                        r.lastName(), r.keyFingerprint(), r.effectiveUuid(), r.lastSeenMs(), false
                ));
            }
        }
        return new ArrayList<>(byFp.values());
    }

    private List<TransferCandidate> loadTransferCandidates(UUID targetUuid) {
        Path worldDir = currentWorldDir();
        if (worldDir == null) return List.of();
        Path playerData = worldDir.resolve("playerdata");
        if (!Files.isDirectory(playerData)) return List.of();

        Map<UUID, String> nameHints = new LinkedHashMap<>();
        for (IdentityMapStore.IdentityRecord r : IdentityMapStore.list(worldDir)) {
            try {
                if (r.effectiveUuid() != null && r.lastName() != null) {
                    nameHints.put(UUID.fromString(r.effectiveUuid()), r.lastName() + " (key)");
                }
                if (r.migratedFromUuid() != null && r.lastName() != null) {
                    nameHints.putIfAbsent(UUID.fromString(r.migratedFromUuid()), r.lastName() + " (legacy)");
                }
            } catch (Exception ignored) {}
        }

        List<TransferCandidate> list = new ArrayList<>();
        try (Stream<Path> stream = Files.list(playerData)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".dat"))
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        String uuidStr = fn.substring(0, fn.length() - 4);
                        try {
                            UUID u = UUID.fromString(uuidStr);
                            if (u.equals(targetUuid)) return;
                            String label = nameHints.getOrDefault(u, "UUID " + uuidStr.substring(0, 8) + "...");
                            list.add(new TransferCandidate(u, label));
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
        return list;
    }

    private void exportIdentity() {
        CompletableFuture.runAsync(() -> {
            try {
                Path target = chooseSavePath();
                if (target == null) return;
                ClientIdentity.exportTo(target);
                String displayTarget = formatBackupPath(target);
                runOnClient(() -> showToast(tr("screen.peerpulsar.identity.toast_exported", displayTarget), NeonTheme.EMERALD));
            } catch (Exception e) {
                runOnClient(() -> showToast("⚠ " + e.getMessage(), NeonTheme.CRIMSON));
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private static String formatBackupPath(Path target) {
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path homePath = Path.of(home);
            if (target.startsWith(homePath)) {
                return "~/" + homePath.relativize(target).toString().replace('\\', '/');
            }
        }
        return target.toString();
    }

    private void importIdentity() {
        CompletableFuture.runAsync(() -> {
            try {
                Path source = chooseOpenPath();
                if (source == null) {
                    runOnClient(() -> showToast(tr("screen.peerpulsar.identity.toast_import_not_found"), NeonTheme.AMBER));
                    return;
                }
                ClientIdentity imported = ClientIdentity.importFrom(source);
                runOnClient(() -> showToast(tr("screen.peerpulsar.identity.toast_imported", imported.shortFingerprint()), NeonTheme.EMERALD));
            } catch (Exception e) {
                runOnClient(() -> showToast("⚠ " + e.getMessage(), NeonTheme.CRIMSON));
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private Path chooseSavePath() {
        return ClientIdentity.defaultBackupFile();
    }

    private Path chooseOpenPath() {
        Path defaultBackup = ClientIdentity.defaultBackupFile();
        if (Files.isRegularFile(defaultBackup)) {
            return defaultBackup;
        }
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path backup = gameDir.resolve("peerpulsar-identity-backup.json");
        if (Files.isRegularFile(backup)) {
            return backup;
        }
        Path direct = gameDir.resolve("peerpulsar-identity.json");
        return Files.isRegularFile(direct) ? direct : null;
    }

    private void runOnClient(Runnable r) {
        if (this.minecraft != null) {
            this.minecraft.execute(r);
        }
    }

    private void showToast(String msg, int accent) {
        this.toastMessage = msg;
        this.toastAccent = accent;
        this.toastStartMs = System.currentTimeMillis();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        double timeSec = NeonTheme.nowSec();
        graphics.blurBeforeThisStratum();
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, P2PNetworkManager.getInstance().isP2PActive());

        NeonRenderUtils.drawModLogo(graphics, panelLeft + 2, topY, 20, NeonTheme.CYAN, timeSec);
        graphics.text(this.font, Component.translatable("screen.peerpulsar.advanced.header").withStyle(ChatFormatting.BOLD),
                panelLeft + 28, topY + 6, NeonTheme.TEXT_PRIMARY, true);

        int mainAccent = activeTab == Tab.NETWORK ? NeonTheme.CYAN : (activeTab == Tab.SECURITY ? NeonTheme.EMERALD : NeonTheme.VIOLET);
        NeonRenderUtils.drawGlassCard(graphics, panelLeft, bodyY, panelW, bodyH, mainAccent, false, timeSec);

        if (activeTab == Tab.NETWORK) {
            renderNetworkTab(graphics, mouseX, mouseY);
        } else if (activeTab == Tab.SECURITY) {
            renderSecurityTab(graphics, mouseX, mouseY);
        } else {
            renderIdentityTab(graphics);
        }
    }

    private void renderNetworkTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int listX = panelLeft + 8;
        int listW = panelW - 16;
        int listY = bodyY + 32;
        int noteReserve = activeSection == NetworkSection.RELAY ? 14 : 0;
        int listH = Math.max(46, bodyH - 72 - noteReserve);

        graphics.fill(listX, listY, listX + listW, listY + listH, 0x88070B16);
        graphics.outline(listX, listY, listW, listH, NeonTheme.withAlpha(NeonTheme.SLATE, 70));

        List<ListItem> items = buildNetworkItems();
        int rowH = 18;
        int maxRows = Math.max(1, listH / rowH);
        int maxScroll = Math.max(0, items.size() - maxRows);
        networkScroll = Math.max(0, Math.min(networkScroll, maxScroll));

        for (int i = 0; i < maxRows && (i + networkScroll) < items.size(); i++) {
            ListItem item = items.get(i + networkScroll);
            int ry = listY + i * rowH;
            boolean selected = isItemSelected(item);
            boolean hovered = mouseX >= listX && mouseX <= listX + listW && mouseY >= ry && mouseY < ry + rowH;

            if (selected) {
                graphics.fill(listX + 1, ry + 1, listX + listW - 1, ry + rowH - 1, NeonTheme.withAlpha(NeonTheme.CYAN, 42));
            } else if (hovered) {
                graphics.fill(listX + 1, ry + 1, listX + listW - 1, ry + rowH - 1, 0x33FFFFFF);
            }

            // Radio circle
            int rx = listX + 7;
            int rcy = ry + rowH / 2;
            graphics.outline(rx, rcy - 4, 8, 8, NeonTheme.withAlpha(selected ? NeonTheme.CYAN : NeonTheme.SLATE, 220));
            if (selected) {
                graphics.fill(rx + 2, rcy - 2, rx + 6, rcy + 2, NeonTheme.withAlpha(NeonTheme.CYAN, 255));
            }

            if (item.isAuto()) {
                graphics.text(this.font, item.label(), listX + 20, ry + 5,
                        selected ? NeonTheme.TEXT_PRIMARY : NeonTheme.TEXT_SECONDARY, selected);
                continue;
            }

            // Status dot + latency
            String ep = item.endpoint();
            boolean testing = testingCache.getOrDefault(ep, false);
            Long ms = latencyCache.get(ep);
            int dotColor = testing ? NeonTheme.AMBER
                    : (ms == null ? NeonTheme.SLATE : (ms >= 0 ? NeonTheme.EMERALD : NeonTheme.CRIMSON));
            graphics.fill(listX + 19, rcy - 2, listX + 23, rcy + 2, NeonTheme.withAlpha(dotColor, 255));

            int rightEdge = listX + listW - 6;
            if (item.isCustom()) {
                int delX = rightEdge - 14;
                boolean delHover = mouseX >= delX && mouseX <= delX + 14 && mouseY >= ry + 2 && mouseY < ry + rowH - 2;
                graphics.fill(delX, ry + 2, delX + 14, ry + rowH - 2, NeonTheme.withAlpha(NeonTheme.CRIMSON, delHover ? 140 : 50));
                graphics.centeredText(this.font, "×", delX + 7, ry + 5, NeonTheme.TEXT_PRIMARY);
                rightEdge = delX - 6;
            }

            String latStr = testing ? "..." : (ms == null ? "--" : (ms >= 0 ? (ms + " ms") : "DOWN"));
            int latColor = testing ? NeonTheme.AMBER : (ms == null ? NeonTheme.TEXT_MUTED : (ms >= 0 ? NeonTheme.withAlpha(NeonTheme.EMERALD, 255) : NeonTheme.withAlpha(NeonTheme.CRIMSON, 255)));
            graphics.text(this.font, latStr, rightEdge - this.font.width(latStr), ry + 5, latColor, false);

            int maxLabelW = Math.max(40, (rightEdge - this.font.width(latStr) - 8) - (listX + 28));
            String label = item.label();
            if (this.font.width(label) > maxLabelW) {
                label = this.font.plainSubstrByWidth(label, maxLabelW - 8) + "...";
            }
            graphics.text(this.font, label, listX + 28, ry + 5, NeonTheme.TEXT_PRIMARY, false);
        }

        if (activeSection == NetworkSection.RELAY) {
            graphics.text(this.font, tr("screen.peerpulsar.advanced.note_selfhost_bore"),
                    listX + 2, listY + listH + 3, NeonTheme.TEXT_SECONDARY, false);
        }

        int inputY = bodyY + bodyH - 34;
        int btnW = 46;
        int boxW = panelW - 16 - (btnW * 2) - 10;
        graphics.fill(panelLeft + 8, inputY, panelLeft + 8 + boxW, inputY + 18, NeonTheme.INPUT_BG);
        graphics.outline(panelLeft + 8, inputY, boxW, 18, NeonTheme.withAlpha(
                customValidationError != null ? NeonTheme.CRIMSON : NeonTheme.SLATE, 175));

        if (customValidationError != null) {
            graphics.text(this.font, customValidationError, panelLeft + 8, inputY + 21, NeonTheme.withAlpha(NeonTheme.CRIMSON, 255), false);
        } else if (customTestStatus != null) {
            graphics.text(this.font, customTestStatus, panelLeft + 8, inputY + 21, NeonTheme.TEXT_SECONDARY, false);
        }
    }

    private void renderSecurityTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int listX = panelLeft + 8;
        int listW = panelW - 16;
        int listY = bodyY + 48;
        int listH = bodyH - 56;

        graphics.fill(listX, listY, listX + listW, listY + listH, 0x88070B16);
        graphics.outline(listX, listY, listW, listH, NeonTheme.withAlpha(NeonTheme.SLATE, 70));

        if (transferTargetRow != null) {
            graphics.text(this.font, tr("screen.peerpulsar.advanced.transfer_pick", transferTargetRow.name()),
                    listX + 8, listY + 6, NeonTheme.withAlpha(NeonTheme.AMBER, 255), true);
            int cancelX = listX + listW - 54;
            graphics.fill(cancelX, listY + 4, cancelX + 48, listY + 16, NeonTheme.withAlpha(NeonTheme.SLATE, 85));
            graphics.centeredText(this.font, tr("gui.cancel"), cancelX + 24, listY + 6, NeonTheme.TEXT_PRIMARY);

            int rowY = listY + 22;
            if (transferCandidates.isEmpty()) {
                graphics.text(this.font, tr("screen.peerpulsar.advanced.transfer_none"),
                        listX + 8, rowY + 6, NeonTheme.TEXT_MUTED, false);
            } else {
                for (int i = 0; i < transferCandidates.size() && rowY + 16 <= listY + listH - 4; i++) {
                    TransferCandidate cand = transferCandidates.get(i);
                    boolean hov = mouseX >= listX + 6 && mouseX <= listX + listW - 6 && mouseY >= rowY && mouseY < rowY + 16;
                    graphics.fill(listX + 6, rowY, listX + listW - 6, rowY + 15, NeonTheme.withAlpha(NeonTheme.CYAN, hov ? 65 : 22));
                    graphics.text(this.font, cand.label(), listX + 10, rowY + 4, NeonTheme.TEXT_PRIMARY, false);
                    rowY += 17;
                }
            }
            return;
        }

        graphics.text(this.font, Component.translatable("screen.peerpulsar.advanced.approved_header").withStyle(ChatFormatting.BOLD),
                listX + 8, listY + 6, NeonTheme.TEXT_PRIMARY, true);

        Path worldDir = currentWorldDir();
        if (worldDir == null) {
            graphics.text(this.font, tr("screen.peerpulsar.advanced.approved_no_world"),
                    listX + 8, listY + 26, NeonTheme.TEXT_MUTED, false);
            return;
        }

        List<PlayerRow> rows = loadPlayerRows();
        if (rows.isEmpty()) {
            graphics.text(this.font, tr("screen.peerpulsar.advanced.approved_empty"),
                    listX + 8, listY + 26, NeonTheme.TEXT_MUTED, false);
            return;
        }

        int rowH = 18;
        int startY = listY + 20;
        int maxRows = Math.max(1, (listH - 24) / rowH);
        playersScroll = Math.max(0, Math.min(playersScroll, Math.max(0, rows.size() - maxRows)));
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");

        for (int i = 0; i < maxRows && (i + playersScroll) < rows.size(); i++) {
            PlayerRow row = rows.get(i + playersScroll);
            int ry = startY + i * rowH;
            String shortFp = row.keyFingerprint().length() >= 8 ? row.keyFingerprint().substring(0, 8) : row.keyFingerprint();
            String dateStr = row.lastSeenMs() > 0 ? fmt.format(new Date(row.lastSeenMs())) : "--";

            int remX = listX + listW - 50;
            int trX = remX - 54;
            boolean trHov = row.effectiveUuid() != null && mouseX >= trX && mouseX <= trX + 50 && mouseY >= ry + 1 && mouseY < ry + 16;
            boolean remHov = mouseX >= remX && mouseX <= remX + 44 && mouseY >= ry + 1 && mouseY < ry + 16;

            if (row.effectiveUuid() != null) {
                graphics.fill(trX, ry + 1, trX + 50, ry + 16, NeonTheme.withAlpha(NeonTheme.CYAN, trHov ? 110 : 40));
                graphics.centeredText(this.font, tr("screen.peerpulsar.advanced.btn_transfer"), trX + 25, ry + 5, NeonTheme.TEXT_PRIMARY);
            }
            graphics.fill(remX, ry + 1, remX + 44, ry + 16, NeonTheme.withAlpha(NeonTheme.CRIMSON, remHov ? 120 : 45));
            graphics.centeredText(this.font, tr("screen.peerpulsar.advanced.btn_remove"), remX + 22, ry + 5, NeonTheme.TEXT_PRIMARY);

            String label = row.name() + " • " + shortFp + " • " + dateStr + (row.approved() ? "" : " (known)");
            int maxW = Math.max(40, trX - (listX + 10));
            if (this.font.width(label) > maxW) {
                label = this.font.plainSubstrByWidth(label, maxW - 8) + "...";
            }
            graphics.text(this.font, label, listX + 8, ry + 5, NeonTheme.TEXT_PRIMARY, false);
        }
    }

    private void renderIdentityTab(GuiGraphicsExtractor graphics) {
        ClientIdentity.Status st = ClientIdentity.inspectStatus();
        String statusText;
        int statusColor;
        String fpText = "---";

        if (st == ClientIdentity.Status.OK) {
            ClientIdentity id = ClientIdentity.getIfHealthyOrFirstTime();
            statusText = tr("screen.peerpulsar.identity.status_ok");
            statusColor = NeonTheme.EMERALD;
            if (id != null) {
                fpText = id.shortFingerprint() + " (" + id.fingerprintHex().substring(0, 16) + "...)";
            }
        } else if (st == ClientIdentity.Status.NEEDS_FIRST_CREATE) {
            statusText = tr("screen.peerpulsar.identity.status_new");
            statusColor = NeonTheme.CYAN;
        } else if (st == ClientIdentity.Status.MISSING_AFTER_INIT) {
            statusText = tr("screen.peerpulsar.identity.status_missing");
            statusColor = NeonTheme.AMBER;
        } else {
            statusText = tr("screen.peerpulsar.identity.status_corrupt");
            statusColor = NeonTheme.CRIMSON;
        }

        int cx = this.width / 2;
        graphics.centeredText(this.font, tr("screen.peerpulsar.identity.label_status", statusText), cx, bodyY + 16, NeonTheme.withAlpha(statusColor, 255));
        graphics.centeredText(this.font, tr("screen.peerpulsar.identity.label_fingerprint", fpText), cx, bodyY + 30, NeonTheme.TEXT_PRIMARY);
        graphics.centeredText(this.font, tr("screen.peerpulsar.identity.warning_private"), cx, bodyY + 46, NeonTheme.withAlpha(NeonTheme.AMBER, 230));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) return false;
        double mx = event.x();
        double my = event.y();

        if (activeTab == Tab.NETWORK) {
            return handleNetworkClick(mx, my);
        }
        if (activeTab == Tab.SECURITY) {
            return handleSecurityClick(mx, my);
        }
        return false;
    }

    private boolean handleNetworkClick(double mx, double my) {
        int listX = panelLeft + 8;
        int listW = panelW - 16;
        int listY = bodyY + 32;
        int noteReserve = activeSection == NetworkSection.RELAY ? 14 : 0;
        int listH = Math.max(46, bodyH - 72 - noteReserve);
        if (mx < listX || mx > listX + listW || my < listY || my > listY + listH) {
            return false;
        }

        List<ListItem> items = buildNetworkItems();
        int rowH = 18;
        int idx = (int) ((my - listY) / rowH) + networkScroll;
        if (idx < 0 || idx >= items.size()) return false;

        ListItem item = items.get(idx);
        if (item.isCustom()) {
            int delX = listX + listW - 20;
            if (mx >= delX && mx <= delX + 14) {
                deleteCustomItem(item);
                return true;
            }
        }
        selectItem(item);
        return true;
    }

    private boolean handleSecurityClick(double mx, double my) {
        Path worldDir = currentWorldDir();
        if (worldDir == null) return false;
        int listX = panelLeft + 8;
        int listW = panelW - 16;
        int listY = bodyY + 48;
        int listH = bodyH - 56;
        if (mx < listX || mx > listX + listW || my < listY || my > listY + listH) {
            return false;
        }

        if (transferTargetRow != null) {
            int cancelX = listX + listW - 54;
            if (mx >= cancelX && mx <= cancelX + 48 && my >= listY + 4 && my <= listY + 16) {
                transferTargetRow = null;
                return true;
            }
            int rowY = listY + 22;
            for (int i = 0; i < transferCandidates.size() && rowY + 16 <= listY + listH - 4; i++) {
                if (my >= rowY && my < rowY + 16) {
                    TransferCandidate cand = transferCandidates.get(i);
                    try {
                        UUID dst = UUID.fromString(transferTargetRow.effectiveUuid());
                        PlayerDataMigrator.TransferResult res = PlayerDataMigrator.transferPlayerData(worldDir, cand.uuid(), dst);
                        showToast(res.success() ? tr("screen.peerpulsar.advanced.toast_transferred") : res.message(),
                                res.success() ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
                    } catch (Exception e) {
                        showToast("⚠ " + e.getMessage(), NeonTheme.CRIMSON);
                    }
                    transferTargetRow = null;
                    return true;
                }
                rowY += 17;
            }
            return false;
        }

        List<PlayerRow> rows = loadPlayerRows();
        int rowH = 18;
        int startY = listY + 20;
        int idx = (int) ((my - startY) / rowH) + playersScroll;
        if (idx < 0 || idx >= rows.size() || my < startY) return false;

        PlayerRow row = rows.get(idx);
        int remX = listX + listW - 50;
        int trX = remX - 54;
        if (row.effectiveUuid() != null && mx >= trX && mx <= trX + 50) {
            try {
                UUID dst = UUID.fromString(row.effectiveUuid());
                this.transferTargetRow = row;
                this.transferCandidates = loadTransferCandidates(dst);
            } catch (Exception ignored) {}
            return true;
        }
        if (mx >= remX && mx <= remX + 44) {
            ApprovedPlayerStore.remove(worldDir, row.keyFingerprint(), row.name());
            NameLockStore.removeByName(worldDir, row.name());
            showToast(tr("screen.peerpulsar.advanced.toast_removed", row.name()), NeonTheme.CRIMSON);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == Tab.NETWORK) {
            networkScroll += (scrollY < 0 ? 1 : -1);
            return true;
        }
        if (activeTab == Tab.SECURITY && transferTargetRow == null) {
            playersScroll += (scrollY < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        NeonRenderUtils.drawToast(graphics, this.font, this.width, this.height, toastMessage, toastAccent, toastStartMs, 3200);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
