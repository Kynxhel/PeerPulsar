package com.kynxhel.peerpulsar.client.gui.screen;

import com.kynxhel.peerpulsar.client.gui.fx.NeonRenderUtils;
import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.client.gui.fx.ParticleBackground;
import com.kynxhel.peerpulsar.client.gui.widget.NeonButton;
import com.kynxhel.peerpulsar.client.gui.widget.NeonModeOptionCard;
import com.kynxhel.peerpulsar.client.gui.widget.NeonToggleCard;
import com.kynxhel.peerpulsar.client.network.NetworkDiagnosticsService;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager.ActiveHostingMode;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager.ConnectionMode;
import com.kynxhel.peerpulsar.client.network.RoomCodeHelper;
import com.kynxhel.peerpulsar.client.network.UPnPService;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class P2PHostScreen extends Screen {
    private record ConnectedPlayerEntry(UUID uuid, String name, int pingMs, boolean isHost) {}

    private final Screen parent;
    private final P2PNetworkManager networkManager;
    private final ParticleBackground particleBg = new ParticleBackground();
    private final long openTimeMs = System.currentTimeMillis();

    private NeonToggleCard offlineAuthCard;
    private NeonToggleCard gameModeCard;
    private NeonToggleCard cheatsCard;
    private NeonToggleCard joinApprovalCard;

    private NeonModeOptionCard directModeCard;
    private NeonModeOptionCard udpPunchModeCard;
    private NeonModeOptionCard tunnelModeCard;
    private NeonModeOptionCard lanModeCard;

    private NeonButton advancedButton;
    private NeonButton startStopButton;
    private NeonButton copyDiscordButton;
    private NeonButton copyCodeQuickButton;
    private NeonButton newCodeButton;
    private NeonButton retestButton;
    private NeonButton backButton;

    private String toastMessage = null;
    private int toastAccent = NeonTheme.EMERALD;
    private long toastStartMs = 0;
    private static final long TOAST_DURATION_MS = 3400;

    private boolean lastSyncedP2PActive;
    private boolean lastSyncedStarting;
    private boolean lastSyncedDiagTesting;
    private int playersScroll = 0;

    private int panelW;
    private int sideW;
    private int sideGap;
    private int panelLeft;
    private int sideLeft;
    private int topY;
    private boolean compact;

    public P2PHostScreen(Screen parent) {
        super(Component.translatable("screen.peerpulsar.host.title"));
        this.parent = parent;
        this.networkManager = P2PNetworkManager.getInstance();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void computeLayout() {
        this.compact = this.height < 284;
        this.sideGap = (this.width < 420) ? 6 : 8;
        if (this.width < 420) {
            int maxTotalW = Math.max(180, this.width - 12);
            int avail = maxTotalW - this.sideGap;
            this.sideW = Math.max(90, Math.min(156, (avail * 39) / 100));
            this.panelW = Math.max(90, avail - this.sideW);
        } else {
            this.sideW = (this.width >= 520) ? 174 : 160;
            this.panelW = Math.min(316, Math.max(244, this.width - this.sideW - this.sideGap - 16));
        }
        int totalW = this.panelW + this.sideGap + this.sideW;
        this.panelLeft = Math.max(6, (this.width - totalW) / 2);
        this.sideLeft = this.panelLeft + this.panelW + this.sideGap;
        int totalH = compact ? 242 : 272;
        this.topY = Math.max(6, (this.height - totalH) / 2);
    }

    @Override
    protected void init() {
        computeLayout();

        int heroY = topY + (compact ? 28 : 34);
        int heroH = compact ? 80 : 88;
        int totalRight = sideLeft + sideW;

        // Top-right Advanced button
        this.advancedButton = new NeonButton(
                totalRight - 66, topY + (compact ? 3 : 5), 66, 16,
                Component.translatable("screen.peerpulsar.btn_advanced"),
                NeonTheme.VIOLET,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new P2PAdvancedScreen(this));
                    }
                }
        );
        this.addRenderableWidget(advancedButton);

        // Quick Re-Test Internet & NAT Diagnostics button
        this.retestButton = new NeonButton(
                panelLeft + panelW - 56, heroY + 5, 46, 14,
                Component.translatable("screen.peerpulsar.host.btn_retest"),
                NeonTheme.AMBER,
                () -> requestNetworkCheck(true)
        );
        this.addRenderableWidget(retestButton);

        int codeBoxY = heroY + heroH - 34;
        this.copyCodeQuickButton = new NeonButton(
                panelLeft + panelW - 64, codeBoxY, 54, 18,
                Component.translatable("screen.peerpulsar.host.btn_copy_raw"),
                NeonTheme.CYAN,
                () -> {
                    String code = networkManager.getHostedRoomCode();
                    if (code != null && this.minecraft != null) {
                        this.minecraft.keyboardHandler.setClipboard(code);
                        particleBg.spawnBurst(copyCodeQuickButton.getX() + 27, copyCodeQuickButton.getY() + 9, NeonTheme.CYAN, 22);
                        boolean exposesIp = isCodeExposingIp(networkManager.getActiveHostingMode());
                        showToast(tr(exposesIp
                                        ? "screen.peerpulsar.host.toast_code_copied_ip"
                                        : "screen.peerpulsar.host.toast_code_copied", code),
                                exposesIp ? NeonTheme.AMBER : NeonTheme.CYAN);
                    }
                }
        );
        this.addRenderableWidget(copyCodeQuickButton);

        this.newCodeButton = new NeonButton(
                panelLeft + panelW - 134, codeBoxY, 66, 18,
                Component.translatable("screen.peerpulsar.host.btn_new_code"),
                NeonTheme.AMBER,
                () -> {
                    String newCode = networkManager.rotateRoomKey();
                    if (newCode != null && this.minecraft != null) {
                        this.minecraft.keyboardHandler.setClipboard(newCode);
                        particleBg.spawnBurst(newCodeButton.getX() + 33, newCodeButton.getY() + 9, NeonTheme.AMBER, 24);
                        showToast(tr("screen.peerpulsar.host.toast_new_code"), NeonTheme.AMBER);
                    }
                }
        );
        this.addRenderableWidget(newCodeButton);

        // 2x2 Neon Toggle Card Grid (left main panel)
        int gridTop = heroY + heroH + (compact ? 5 : 8);
        int gapX = 8;
        int gapY = compact ? 5 : 7;
        int cardW = (panelW - gapX) / 2;
        int cardH = compact ? 30 : 34;

        this.offlineAuthCard = new NeonToggleCard(
                panelLeft, gridTop, cardW, cardH,
                tr("screen.peerpulsar.host.card_offline_title"),
                () -> networkManager.isOfflineModeEnabled()
                        ? tr("screen.peerpulsar.host.card_offline_on")
                        : tr("screen.peerpulsar.host.card_offline_off"),
                networkManager::isOfflineModeEnabled,
                () -> networkManager.isOfflineModeEnabled() ? NeonTheme.EMERALD : NeonTheme.CRIMSON,
                () -> {
                    if (networkManager.isOfflineModeEnabled()) {
                        networkManager.setOfflineModeEnabled(false);
                        particleBg.spawnBurst(offlineAuthCard.getX() + cardW - 20, offlineAuthCard.getY() + cardH / 2,
                                NeonTheme.CRIMSON, 14);
                        return;
                    }
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new ConfirmScreen(
                                confirmed -> {
                                    if (confirmed) {
                                        networkManager.setOfflineModeEnabled(true);
                                        showToast(tr("screen.peerpulsar.host.toast_offline_enabled"), NeonTheme.AMBER);
                                    }
                                    if (this.minecraft != null) {
                                        this.minecraft.setScreenAndShow(this);
                                    }
                                },
                                Component.translatable("screen.peerpulsar.host.warn_offline_title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                                Component.translatable("screen.peerpulsar.host.warn_offline_body"),
                                Component.translatable("screen.peerpulsar.host.warn_offline_confirm"),
                                Component.translatable("gui.cancel")
                        ));
                    }
                }
        );
        this.addRenderableWidget(offlineAuthCard);

        this.gameModeCard = new NeonToggleCard(
                panelLeft + cardW + gapX, gridTop, cardW, cardH,
                tr("screen.peerpulsar.host.card_gamemode_title"),
                () -> networkManager.getSelectedGameType().getName().toUpperCase(),
                null,
                this::getGameModeAccent,
                () -> {
                    GameType current = networkManager.getSelectedGameType();
                    GameType next;
                    if (current == GameType.SURVIVAL) next = GameType.CREATIVE;
                    else if (current == GameType.CREATIVE) next = GameType.ADVENTURE;
                    else if (current == GameType.ADVENTURE) next = GameType.SPECTATOR;
                    else next = GameType.SURVIVAL;
                    networkManager.setSelectedGameType(next);
                    particleBg.spawnBurst(gameModeCard.getX() + cardW - 14, gameModeCard.getY() + cardH / 2,
                            getGameModeAccent(), 14);
                }
        );
        this.addRenderableWidget(gameModeCard);

        this.cheatsCard = new NeonToggleCard(
                panelLeft, gridTop + cardH + gapY, cardW, cardH,
                tr("screen.peerpulsar.host.card_cheats_title"),
                () -> networkManager.isAllowCheats()
                        ? tr("screen.peerpulsar.host.card_cheats_on")
                        : tr("screen.peerpulsar.host.card_cheats_off"),
                networkManager::isAllowCheats,
                () -> networkManager.isAllowCheats() ? NeonTheme.AMBER : NeonTheme.SLATE,
                () -> {
                    boolean next = !networkManager.isAllowCheats();
                    networkManager.setAllowCheats(next);
                    particleBg.spawnBurst(cheatsCard.getX() + cardW - 20, cheatsCard.getY() + cardH / 2,
                            next ? NeonTheme.AMBER : NeonTheme.SLATE, 14);
                }
        );
        this.addRenderableWidget(cheatsCard);

        this.joinApprovalCard = new NeonToggleCard(
                panelLeft + cardW + gapX, gridTop + cardH + gapY, cardW, cardH,
                tr("screen.peerpulsar.host.card_approval_title"),
                () -> networkManager.isJoinApprovalEnabled()
                        ? tr("screen.peerpulsar.host.card_approval_on")
                        : tr("screen.peerpulsar.host.card_approval_off"),
                networkManager::isJoinApprovalEnabled,
                () -> networkManager.isJoinApprovalEnabled() ? NeonTheme.EMERALD : NeonTheme.SLATE,
                () -> {
                    boolean next = !networkManager.isJoinApprovalEnabled();
                    networkManager.setJoinApprovalEnabled(next);
                    particleBg.spawnBurst(joinApprovalCard.getX() + cardW - 20, joinApprovalCard.getY() + cardH / 2,
                            next ? NeonTheme.EMERALD : NeonTheme.SLATE, 14);
                    showToast(tr(next ? "screen.peerpulsar.host.toast_approval_on" : "screen.peerpulsar.host.toast_approval_off"),
                            next ? NeonTheme.EMERALD : NeonTheme.SLATE);
                }
        );
        this.addRenderableWidget(joinApprovalCard);

        // Right-Side Connection Mode Selector Cards (shown while standby)
        int sideCardX = sideLeft + 8;
        int sideCardW = sideW - 16;
        int sideCardH = compact ? 28 : 31;
        int sideStartY = heroY + 24;
        int sideCardGap = compact ? 4 : 5;

        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();

        this.directModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY, sideCardW, sideCardH,
                tr("screen.peerpulsar.host.mode_direct_title"),
                () -> {
                    if (diag.isTesting()) return tr("screen.peerpulsar.host.mode_testing");
                    NetworkDiagnosticsService.DiagnosticReport r = diag.getLastReport();
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.DIRECT_SUPPORTED) {
                        return tr("screen.peerpulsar.host.mode_direct_ok");
                    }
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.CGNAT_DETECTED) {
                        return tr("screen.peerpulsar.host.mode_direct_cgnat");
                    }
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.UPNP_UNAVAILABLE) {
                        return tr("screen.peerpulsar.host.mode_direct_upnp");
                    }
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET) {
                        return tr("screen.peerpulsar.host.mode_no_internet");
                    }
                    return tr("screen.peerpulsar.host.mode_untested");
                },
                () -> networkManager.isModeSupported(ConnectionMode.DIRECT_UPNP, diag.getLastReport()),
                diag::isTesting,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.DIRECT_UPNP,
                () -> networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.DIRECT_UPNP,
                NeonTheme.EMERALD,
                () -> selectMode(ConnectionMode.DIRECT_UPNP, directModeCard, NeonTheme.EMERALD)
        );
        this.addRenderableWidget(directModeCard);

        this.udpPunchModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY + (sideCardH + sideCardGap), sideCardW, sideCardH,
                tr("screen.peerpulsar.host.mode_udp_title"),
                () -> {
                    if (diag.isTesting()) return tr("screen.peerpulsar.host.mode_testing");
                    NetworkDiagnosticsService.DiagnosticReport r = diag.getLastReport();
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.IDLE) {
                        return tr("screen.peerpulsar.host.mode_untested");
                    }
                    if (r.udpHolePunchSupported()) {
                        return tr("screen.peerpulsar.host.mode_udp_ok");
                    }
                    return r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET
                            ? tr("screen.peerpulsar.host.mode_no_internet")
                            : tr("screen.peerpulsar.host.mode_udp_fail");
                },
                () -> networkManager.isModeSupported(ConnectionMode.UDP_HOLE_PUNCH, diag.getLastReport()),
                diag::isTesting,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.UDP_HOLE_PUNCH,
                () -> networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.UDP_HOLE_PUNCH,
                NeonTheme.CYAN,
                () -> selectMode(ConnectionMode.UDP_HOLE_PUNCH, udpPunchModeCard, NeonTheme.CYAN)
        );
        this.addRenderableWidget(udpPunchModeCard);

        this.tunnelModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY + (sideCardH + sideCardGap) * 2, sideCardW, sideCardH,
                tr("screen.peerpulsar.host.mode_tunnel_title"),
                () -> {
                    if (diag.isTesting()) return tr("screen.peerpulsar.host.mode_testing");
                    NetworkDiagnosticsService.DiagnosticReport r = diag.getLastReport();
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.IDLE) {
                        return tr("screen.peerpulsar.host.mode_untested");
                    }
                    if (r.tunnelAvailable()) {
                        return tr("screen.peerpulsar.host.mode_tunnel_ok", r.tunnelLatencyMs());
                    }
                    return r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET
                            ? tr("screen.peerpulsar.host.mode_no_internet")
                            : tr("screen.peerpulsar.host.mode_tunnel_fail");
                },
                () -> networkManager.isModeSupported(ConnectionMode.TUNNEL_RELAY, diag.getLastReport()),
                diag::isTesting,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.TUNNEL_RELAY,
                () -> networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.TUNNEL_RELAY,
                NeonTheme.VIOLET,
                () -> selectMode(ConnectionMode.TUNNEL_RELAY, tunnelModeCard, NeonTheme.VIOLET)
        );
        this.addRenderableWidget(tunnelModeCard);

        this.lanModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY + (sideCardH + sideCardGap) * 3, sideCardW, sideCardH,
                tr("screen.peerpulsar.host.mode_lan_title"),
                () -> tr("screen.peerpulsar.host.mode_lan_ok"),
                () -> true,
                () -> false,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.LOCAL_LAN,
                () -> !diag.isTesting() && networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.LOCAL_LAN,
                NeonTheme.EMERALD,
                () -> selectMode(ConnectionMode.LOCAL_LAN, lanModeCard, NeonTheme.EMERALD)
        );
        this.addRenderableWidget(lanModeCard);

        // Primary Action Bar (left main panel)
        int actionY = gridTop + (cardH + gapY) * 2 + (compact ? 4 : 6);
        int actionH = compact ? 21 : 24;
        int halfW = (panelW - gapX) / 2;

        this.startStopButton = new NeonButton(
                panelLeft, actionY, halfW, actionH,
                Component.translatable("screen.peerpulsar.host.btn_start").withStyle(ChatFormatting.BOLD),
                NeonTheme.EMERALD,
                this::handleStartOrStop
        );
        this.addRenderableWidget(startStopButton);

        this.copyDiscordButton = new NeonButton(
                panelLeft + halfW + gapX, actionY, halfW, actionH,
                Component.translatable("screen.peerpulsar.host.btn_copy_invite"),
                NeonTheme.VIOLET,
                () -> {
                    if (networkManager.getHostedRoomCode() != null && this.minecraft != null) {
                        String discordMsg = RoomCodeHelper.generateDiscordInvite(
                                networkManager.getHostedRoomCode(),
                                networkManager.getHostedPublicHost(),
                                networkManager.getHostedPublicPort(),
                                networkManager.isOfflineModeEnabled(),
                                networkManager.getSelectedGameType().getName()
                        );
                        this.minecraft.keyboardHandler.setClipboard(discordMsg);
                        particleBg.spawnBurst(copyDiscordButton.getX() + halfW / 2, copyDiscordButton.getY() + actionH / 2, NeonTheme.VIOLET, 30);
                        boolean exposesIp = isCodeExposingIp(networkManager.getActiveHostingMode());
                        showToast(tr(exposesIp
                                        ? "screen.peerpulsar.host.toast_invite_copied_ip"
                                        : "screen.peerpulsar.host.toast_invite_copied"),
                                exposesIp ? NeonTheme.AMBER : NeonTheme.VIOLET);
                    } else {
                        showToast(tr("screen.peerpulsar.host.toast_start_first"), NeonTheme.AMBER);
                    }
                }
        );
        this.addRenderableWidget(copyDiscordButton);

        int footerY = Math.min(this.height - 22, actionY + actionH + (compact ? 4 : 6));
        int footerBtnW = Math.min(160, Math.max(90, this.width - 24));
        this.backButton = new NeonButton(
                this.width / 2 - footerBtnW / 2, footerY, footerBtnW, 20,
                Component.literal("← ").append(Component.translatable("gui.back")),
                NeonTheme.SLATE,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(this.parent);
                    }
                }
        );
        this.addRenderableWidget(backButton);

        updateWidgetStates();

        if (diag.hasRecentReport()) {
            networkManager.syncSelectedModeWithDiagnostics(diag.getLastReport());
            updateWidgetStates();
        }
    }

    private static boolean isCodeExposingIp(ActiveHostingMode mode) {
        return mode == ActiveHostingMode.DIRECT_UPNP || mode == ActiveHostingMode.LOCAL_LAN;
    }

    private void requestNetworkCheck(boolean showResultToast) {
        if (PeerPulsarConfig.get().hasNetworkTestConsent()) {
            runNetworkCheck(showResultToast);
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
                            runNetworkCheck(showResultToast);
                        }
                    },
                    Component.translatable("screen.peerpulsar.host.consent_diag_title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.translatable("screen.peerpulsar.host.consent_diag_body"),
                    Component.translatable("screen.peerpulsar.host.consent_diag_confirm"),
                    Component.translatable("gui.cancel")
            ));
        }
    }

    private void selectMode(ConnectionMode mode, NeonModeOptionCard card, int accent) {
        networkManager.setSelectedConnectionMode(mode, true);
        particleBg.spawnBurst(card.getX() + card.getWidth() / 2.0f, card.getY() + card.getHeight() / 2.0f, accent, 18);
        updateWidgetStates();
    }

    private void runNetworkCheck(boolean showResultToast) {
        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
        if (diag.isTesting()) return;

        diag.runDiagnosticsAsync().whenComplete((report, err) -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (err != null || report == null) {
                        updateWidgetStates();
                        if (showResultToast) {
                            showToast(tr("screen.peerpulsar.host.toast_diag_blocked"), NeonTheme.CRIMSON);
                        }
                        return;
                    }
                    networkManager.syncSelectedModeWithDiagnostics(report);
                    updateWidgetStates();
                    if (showResultToast) {
                        switch (report.status()) {
                            case DIRECT_SUPPORTED -> showToast(tr("screen.peerpulsar.host.toast_diag_direct"), NeonTheme.EMERALD);
                            case CGNAT_DETECTED -> {
                                if (report.udpHolePunchSupported()) {
                                    showToast(tr("screen.peerpulsar.host.toast_diag_udp"), NeonTheme.CYAN);
                                } else if (report.tunnelAvailable()) {
                                    showToast(tr("screen.peerpulsar.host.toast_diag_cgnat_tunnel", report.tunnelLatencyMs()), NeonTheme.VIOLET);
                                } else {
                                    showToast(tr("screen.peerpulsar.host.toast_diag_blocked"), NeonTheme.CRIMSON);
                                }
                            }
                            case UPNP_UNAVAILABLE -> {
                                if (report.udpHolePunchSupported()) {
                                    showToast(tr("screen.peerpulsar.host.toast_diag_udp"), NeonTheme.CYAN);
                                } else if (report.tunnelAvailable()) {
                                    showToast(tr("screen.peerpulsar.host.toast_diag_upnp_tunnel", report.tunnelLatencyMs()), NeonTheme.VIOLET);
                                } else {
                                    showToast(tr("screen.peerpulsar.host.toast_diag_blocked"), NeonTheme.CRIMSON);
                                }
                            }
                            case NO_INTERNET -> showToast(tr("screen.peerpulsar.host.toast_diag_offline"), NeonTheme.CRIMSON);
                            default -> {}
                        }
                    }
                });
            }
        });
        updateWidgetStates();
    }

    private void handleStartOrStop() {
        if (this.minecraft == null) return;

        if (networkManager.isP2PActive()) {
            networkManager.stopP2PHost(this.minecraft);
            particleBg.spawnBurst(startStopButton.getX() + startStopButton.getWidth() / 2.0f,
                    startStopButton.getY() + 12, NeonTheme.CRIMSON, 32);
            updateWidgetStates();
            showToast(tr("screen.peerpulsar.host.toast_stopped"), NeonTheme.CRIMSON);
            return;
        }

        if (networkManager.getSelectedConnectionMode() != ConnectionMode.LOCAL_LAN
                && !NetworkDiagnosticsService.getInstance().hasRecentReport()
                && !PeerPulsarConfig.get().hasNetworkTestConsent()) {
            this.minecraft.setScreenAndShow(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) {
                            PeerPulsarConfig.get().setNetworkTestConsent(true);
                        }
                        if (this.minecraft != null) {
                            this.minecraft.setScreenAndShow(this);
                        }
                        if (confirmed) {
                            startHostingNow();
                        }
                    },
                    Component.translatable("screen.peerpulsar.host.consent_diag_title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.translatable("screen.peerpulsar.host.consent_diag_body"),
                    Component.translatable("screen.peerpulsar.host.consent_start_confirm"),
                    Component.translatable("gui.cancel")
            ));
            return;
        }

        startHostingNow();
    }

    private void startHostingNow() {
        if (this.minecraft == null) return;
        int port = networkManager.allocatePort();
        startStopButton.active = false;
        startStopButton.setAccentRgb(NeonTheme.AMBER);
        startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_starting").withStyle(ChatFormatting.BOLD));

        networkManager.startP2PHost(
                this.minecraft,
                networkManager.getSelectedGameType(),
                networkManager.isAllowCheats(),
                port,
                networkManager.isOfflineModeEnabled(),
                success -> {
                    updateWidgetStates();
                    if (success) {
                        particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f - 40, NeonTheme.EMERALD, 45);
                        particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f - 40, NeonTheme.CYAN, 25);
                        ActiveHostingMode activeMode = networkManager.getActiveHostingMode();
                        String toastKey = activeMode == ActiveHostingMode.TUNNEL_RELAY
                                ? "screen.peerpulsar.host.toast_started_tunnel"
                                : "screen.peerpulsar.host.toast_started";
                        int accent = activeMode == ActiveHostingMode.TUNNEL_RELAY
                                ? NeonTheme.VIOLET : NeonTheme.EMERALD;
                        showToast(tr(toastKey, networkManager.getHostedRoomCode()), accent);
                    } else {
                        String err = networkManager.getLastErrorMessage();
                        showToast(err != null ? ("⚠ " + err) : tr("screen.peerpulsar.host.toast_start_error"), NeonTheme.CRIMSON);
                    }
                }
        );
        updateWidgetStates();
    }

    private void updateWidgetStates() {
        boolean active = networkManager.isP2PActive();
        boolean busy = networkManager.isStarting();
        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
        boolean testing = diag.isTesting();
        this.lastSyncedP2PActive = active;
        this.lastSyncedStarting = busy;
        this.lastSyncedDiagTesting = testing;
        ConnectionMode selected = networkManager.getSelectedConnectionMode();

        if (startStopButton != null) {
            if (active) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.CRIMSON);
                startStopButton.setPulsingBeacon(true);
                startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_stop").withStyle(ChatFormatting.BOLD));
            } else if (busy) {
                startStopButton.active = false;
                startStopButton.setAccentRgb(NeonTheme.AMBER);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_starting").withStyle(ChatFormatting.BOLD));
            } else if (testing && selected != ConnectionMode.LOCAL_LAN) {
                startStopButton.active = false;
                startStopButton.setAccentRgb(NeonTheme.AMBER);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_testing_net").withStyle(ChatFormatting.BOLD));
            } else if (selected == ConnectionMode.UDP_HOLE_PUNCH) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.CYAN);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_start_udp").withStyle(ChatFormatting.BOLD));
            } else if (selected == ConnectionMode.TUNNEL_RELAY) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.VIOLET);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_start_tunnel").withStyle(ChatFormatting.BOLD));
            } else if (selected == ConnectionMode.LOCAL_LAN) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.CYAN);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.peerpulsar.host.btn_start_lan").withStyle(ChatFormatting.BOLD));
            } else {
                boolean directOk = diag.getStatus() == NetworkDiagnosticsService.P2PCompatibilityStatus.IDLE
                        || networkManager.isModeSupported(ConnectionMode.DIRECT_UPNP, diag.getLastReport());
                startStopButton.active = directOk;
                startStopButton.setAccentRgb(directOk ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable(
                        directOk ? "screen.peerpulsar.host.btn_start" : "screen.peerpulsar.host.btn_unsupported"
                ).withStyle(ChatFormatting.BOLD));
            }
        }
        if (retestButton != null) {
            retestButton.visible = !active;
            retestButton.active = !active && !busy && !testing;
        }
        if (copyDiscordButton != null) copyDiscordButton.active = active;
        if (copyCodeQuickButton != null) {
            copyCodeQuickButton.active = active;
            copyCodeQuickButton.visible = active;
        }
        boolean showNewCode = active && networkManager.isHostRequiresRoomKey() && networkManager.getHostRoomKey() != null;
        if (newCodeButton != null) {
            newCodeButton.active = showNewCode;
            newCodeButton.visible = showNewCode;
        }
        if (offlineAuthCard != null) offlineAuthCard.active = !active && !busy;
        if (gameModeCard != null) gameModeCard.active = !busy;
        if (cheatsCard != null) cheatsCard.active = !active && !busy;
        if (joinApprovalCard != null) joinApprovalCard.active = !busy;

        boolean canSwitchMode = !active && !busy;
        boolean untested = diag.getStatus() == NetworkDiagnosticsService.P2PCompatibilityStatus.IDLE;
        NetworkDiagnosticsService.DiagnosticReport report = diag.getLastReport();
        if (directModeCard != null) {
            directModeCard.visible = !active;
            directModeCard.active = canSwitchMode && !testing && (untested || networkManager.isModeSupported(ConnectionMode.DIRECT_UPNP, report));
        }
        if (udpPunchModeCard != null) {
            udpPunchModeCard.visible = !active;
            udpPunchModeCard.active = canSwitchMode && !testing && (untested || networkManager.isModeSupported(ConnectionMode.UDP_HOLE_PUNCH, report));
        }
        if (tunnelModeCard != null) {
            tunnelModeCard.visible = !active;
            tunnelModeCard.active = canSwitchMode && !testing && (untested || networkManager.isModeSupported(ConnectionMode.TUNNEL_RELAY, report));
        }
        if (lanModeCard != null) {
            lanModeCard.visible = !active;
            lanModeCard.active = canSwitchMode;
        }
    }

    private int getGameModeAccent() {
        GameType gt = networkManager.getSelectedGameType();
        if (gt == GameType.CREATIVE) return NeonTheme.CYAN;
        if (gt == GameType.ADVENTURE) return NeonTheme.VIOLET;
        if (gt == GameType.SPECTATOR) return NeonTheme.AMBER;
        return NeonTheme.EMERALD;
    }

    private void showToast(String msg, int accentRgb) {
        this.toastMessage = msg;
        this.toastAccent = accentRgb;
        this.toastStartMs = System.currentTimeMillis();
    }

    private List<ConnectedPlayerEntry> getConnectedPlayers() {
        if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) return List.of();
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        List<ConnectedPlayerEntry> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean isHost = server.isSingleplayerOwner(new NameAndId(p.getGameProfile()));
            int ping = isHost ? 0 : p.connection.latency();
            list.add(new ConnectedPlayerEntry(p.getUUID(), p.getScoreboardName(), ping, isHost));
        }
        return list;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        double timeSec = NeonTheme.nowSec();
        float enterT = NeonTheme.easeOutCubic(Math.min(1.0f, (System.currentTimeMillis() - openTimeMs) / 360.0f));

        graphics.blurBeforeThisStratum();
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, networkManager.isP2PActive());

        int slideOffset = Math.round((1.0f - enterT) * -10.0f);
        boolean active = networkManager.isP2PActive();
        int primaryAccent = active ? NeonTheme.EMERALD : NeonTheme.CYAN;

        // 1. Header
        int headerY = topY + slideOffset;
        int totalRight = sideLeft + sideW;
        int logoSize = compact ? 22 : 26;
        NeonRenderUtils.drawModLogo(graphics, panelLeft + 2, headerY, logoSize, primaryAccent, timeSec);

        graphics.text(this.font, Component.literal("PEERPULSAR").withStyle(ChatFormatting.BOLD),
                panelLeft + logoSize + 10, headerY + 2, NeonTheme.TEXT_PRIMARY, true);
        if (!compact) {
            graphics.text(this.font, tr("screen.peerpulsar.host.subtitle"),
                    panelLeft + logoSize + 10, headerY + 14, NeonTheme.TEXT_SECONDARY, false);
        }

        String stateBadge = active
                ? tr("screen.peerpulsar.host.badge_live")
                : tr("screen.peerpulsar.host.badge_standby");
        int stateBadgeW = this.font.width(stateBadge) + 12 + (active ? 10 : 0);
        int stateBadgeX = totalRight - 66 - 6 - stateBadgeW;

        NeonRenderUtils.drawBadge(
                graphics, this.font,
                stateBadgeX, headerY + (compact ? 4 : 6),
                stateBadge,
                active ? NeonTheme.EMERALD : NeonTheme.SLATE,
                active, timeSec
        );
        if (this.width >= 530) {
            NeonRenderUtils.drawBadge(
                    graphics, this.font,
                    stateBadgeX - 54, headerY + (compact ? 4 : 6),
                    "MC 26.3",
                    NeonTheme.VIOLET,
                    false, timeSec
            );
        }

        // 2. Hero Telemetry & Room Code Glass Card
        int cardY = topY + (compact ? 28 : 34);
        int cardH = compact ? 80 : 88;
        NeonRenderUtils.drawGlassCard(graphics, panelLeft, cardY, panelW, cardH, primaryAccent, true, timeSec);

        UPnPService upnp = UPnPService.getInstance();
        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
        NetworkDiagnosticsService.DiagnosticReport report = diag.getLastReport();
        String statusLabel;
        int statusColor;

        if (active && networkManager.getActiveHostingMode() == ActiveHostingMode.UDP_HOLE_PUNCH) {
            statusLabel = tr("screen.peerpulsar.host.status_udp_live");
            statusColor = NeonTheme.CYAN;
        } else if (active && networkManager.getActiveHostingMode() == ActiveHostingMode.TUNNEL_RELAY) {
            statusLabel = tr("screen.peerpulsar.host.status_tunnel_live");
            statusColor = NeonTheme.VIOLET;
        } else if (active && networkManager.getActiveHostingMode() == ActiveHostingMode.LOCAL_LAN) {
            statusLabel = tr("screen.peerpulsar.host.status_lan_live");
            statusColor = NeonTheme.CYAN;
        } else if (active) {
            statusLabel = tr("screen.peerpulsar.host.upnp_ok");
            statusColor = NeonTheme.EMERALD;
        } else if (diag.isTesting()) {
            statusLabel = tr("screen.peerpulsar.host.diag_testing");
            statusColor = NeonTheme.AMBER;
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.DIRECT_SUPPORTED) {
            statusLabel = tr("screen.peerpulsar.host.diag_direct_ok");
            statusColor = NeonTheme.EMERALD;
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.CGNAT_DETECTED) {
            if (report.udpHolePunchSupported()) {
                statusLabel = tr("screen.peerpulsar.host.diag_cgnat_udp");
                statusColor = NeonTheme.CYAN;
            } else if (report.tunnelAvailable()) {
                statusLabel = tr("screen.peerpulsar.host.diag_cgnat_tunnel", report.tunnelLatencyMs());
                statusColor = NeonTheme.VIOLET;
            } else {
                statusLabel = tr("screen.peerpulsar.host.diag_cgnat_blocked");
                statusColor = NeonTheme.CRIMSON;
            }
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.UPNP_UNAVAILABLE) {
            if (report.udpHolePunchSupported()) {
                statusLabel = tr("screen.peerpulsar.host.diag_upnp_udp");
                statusColor = NeonTheme.CYAN;
            } else if (report.tunnelAvailable()) {
                statusLabel = tr("screen.peerpulsar.host.diag_upnp_tunnel", report.tunnelLatencyMs());
                statusColor = NeonTheme.AMBER;
            } else {
                statusLabel = tr("screen.peerpulsar.host.diag_upnp_blocked");
                statusColor = NeonTheme.CRIMSON;
            }
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET) {
            statusLabel = tr("screen.peerpulsar.host.diag_no_internet");
            statusColor = NeonTheme.CRIMSON;
        } else {
            statusLabel = tr("screen.peerpulsar.host.upnp_idle");
            statusColor = NeonTheme.TEXT_SECONDARY;
        }

        int maxStatusW = panelW - (active ? 20 : 70);
        String drawnStatus = "◈ " + statusLabel;
        if (this.font.width(drawnStatus) > maxStatusW) {
            drawnStatus = this.font.plainSubstrByWidth(drawnStatus, Math.max(20, maxStatusW - 8)) + "...";
        }
        graphics.text(this.font, drawnStatus, panelLeft + 10, cardY + 8, NeonTheme.withAlpha(statusColor, 255), true);

        if (diag.isTesting() || upnp.getStatus() == UPnPService.UPnPStatus.SEARCHING || networkManager.isStarting()) {
            NeonRenderUtils.drawScannerBar(graphics, panelLeft + 10, cardY + 20, panelW - 20, NeonTheme.AMBER, timeSec);
        } else {
            graphics.horizontalLine(panelLeft + 10, panelLeft + panelW - 10, cardY + 21, NeonTheme.withAlpha(primaryAccent, 45));
        }

        int localPort = networkManager.getHostedPort();
        String hostedHost = networkManager.getHostedPublicHost();
        String extAddr = active
                ? (hostedHost != null ? RoomCodeHelper.formatHostPort(hostedHost, networkManager.getHostedPublicPort()) : "---")
                : (report.publicIp() != null ? report.publicIp() : "---");
        String localAddr = active ? (upnp.getLocalIp() + ":" + localPort) : upnp.getLocalIp();
        String playerCount = active
                ? (networkManager.getOnlinePlayerCount(this.minecraft) + "/" + networkManager.getMaxPlayerCount(this.minecraft))
                : "0/8";

        int col2X = panelLeft + (panelW * 46) / 100;
        int col3X = panelLeft + panelW - 54;
        int labelY = cardY + (compact ? 24 : 27);
        int valY = labelY + 10;

        graphics.text(this.font, tr("screen.peerpulsar.host.label_ext_ip"), panelLeft + 10, labelY, NeonTheme.TEXT_MUTED, false);
        String trimmedExt = this.font.plainSubstrByWidth(extAddr, Math.max(40, col2X - panelLeft - 14));
        graphics.text(this.font, trimmedExt, panelLeft + 10, valY, active ? NeonTheme.TEXT_PRIMARY : NeonTheme.TEXT_MUTED, true);

        graphics.text(this.font, tr("screen.peerpulsar.host.label_lan_ip"), col2X, labelY, NeonTheme.TEXT_MUTED, false);
        String trimmedLocal = this.font.plainSubstrByWidth(localAddr, Math.max(40, col3X - col2X - 6));
        graphics.text(this.font, trimmedLocal, col2X, valY, active ? NeonTheme.TEXT_SECONDARY : NeonTheme.TEXT_MUTED, false);

        graphics.text(this.font, tr("screen.peerpulsar.host.label_players"), col3X, labelY, NeonTheme.TEXT_MUTED, false);
        graphics.text(this.font, playerCount, col3X, valY, active ? NeonTheme.withAlpha(NeonTheme.EMERALD, 255) : NeonTheme.TEXT_MUTED, true);

        int codeBoxY = cardY + cardH - 34;
        boolean showNewCode = active && networkManager.isHostRequiresRoomKey() && networkManager.getHostRoomKey() != null;
        int codeBoxRight = active ? (panelLeft + panelW - (showNewCode ? 138 : 68)) : (panelLeft + panelW - 10);
        graphics.fill(panelLeft + 10, codeBoxY, codeBoxRight, codeBoxY + 18, NeonTheme.INPUT_BG);
        graphics.outline(panelLeft + 10, codeBoxY, codeBoxRight - (panelLeft + 10), 18,
                NeonTheme.withAlpha(active ? NeonTheme.CYAN : NeonTheme.SLATE, active ? 175 : 70));

        String code = networkManager.getHostedRoomCode();
        if (code != null) {
            String codeLabel = tr("screen.peerpulsar.host.label_room_code");
            int codeStartX = panelLeft + 16 + this.font.width(codeLabel);
            int maxCodeW = Math.max(24, codeBoxRight - codeStartX - 4);
            String drawnCode = code;
            if (this.font.width(Component.literal(drawnCode).withStyle(ChatFormatting.BOLD)) > maxCodeW) {
                int ellipsisW = this.font.width(Component.literal("...").withStyle(ChatFormatting.BOLD));
                String trimmed = this.font.plainSubstrByWidth(code, Math.max(12, maxCodeW - ellipsisW));
                while (!trimmed.isEmpty() && this.font.width(Component.literal(trimmed + "...").withStyle(ChatFormatting.BOLD)) > maxCodeW) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                drawnCode = trimmed + "...";
            }
            graphics.text(this.font, codeLabel, panelLeft + 14, codeBoxY + 5, NeonTheme.withAlpha(NeonTheme.AMBER, 255), false);
            graphics.text(this.font, Component.literal(drawnCode).withStyle(ChatFormatting.BOLD),
                    codeStartX, codeBoxY + 5, NeonTheme.withAlpha(NeonTheme.CYAN, 255), true);
        } else {
            String placeholder = this.font.plainSubstrByWidth(
                    tr("screen.peerpulsar.host.room_code_placeholder"), Math.max(40, codeBoxRight - panelLeft - 24));
            graphics.text(this.font, placeholder, panelLeft + 14, codeBoxY + 5, NeonTheme.TEXT_MUTED, false);
        }

        // Security / IP hint line inside bottom of Hero card
        String noteText;
        int noteColor = NeonTheme.TEXT_SECONDARY;
        if (active) {
            boolean ipExposed = isCodeExposingIp(networkManager.getActiveHostingMode());
            if (networkManager.getActiveHostingMode() == ActiveHostingMode.LOCAL_LAN) {
                noteText = ipExposed ? tr("screen.peerpulsar.host.note_ip_encoded") : "";
                noteColor = NeonTheme.withAlpha(NeonTheme.AMBER, 220);
            } else if (ipExposed) {
                noteText = tr("screen.peerpulsar.host.note_friends_need_mod_ip");
                noteColor = NeonTheme.withAlpha(NeonTheme.AMBER, 225);
            } else {
                noteText = tr("screen.peerpulsar.host.note_friends_need_mod");
                noteColor = NeonTheme.withAlpha(NeonTheme.CYAN, 215);
            }
        } else if (networkManager.getSelectedConnectionMode() == ConnectionMode.LOCAL_LAN) {
            noteText = tr("screen.peerpulsar.host.note_ip_encoded");
        } else if (networkManager.getSelectedConnectionMode() == ConnectionMode.DIRECT_UPNP) {
            noteText = tr("screen.peerpulsar.host.note_friends_need_mod_ip");
        } else {
            noteText = tr("screen.peerpulsar.host.note_friends_need_mod");
        }
        if (!noteText.isEmpty()) {
            String drawnNote = this.font.plainSubstrByWidth(noteText, panelW - 20);
            graphics.text(this.font, drawnNote, panelLeft + 10, cardY + cardH - 12, noteColor, false);
        }

        // 3. Right-Side Panel (Connection Modes when Standby, Connected Players + Kick when Live)
        int sideCardH = compact ? 28 : 31;
        int sideCardGap = compact ? 4 : 5;
        int sidePanelH = 24 + (sideCardH * 4) + (sideCardGap * 3) + 24;
        int sideAccent = active
                ? NeonTheme.EMERALD
                : switch (networkManager.getSelectedConnectionMode()) {
                    case UDP_HOLE_PUNCH -> NeonTheme.CYAN;
                    case TUNNEL_RELAY -> NeonTheme.VIOLET;
                    default -> NeonTheme.EMERALD;
                };
        NeonRenderUtils.drawGlassCard(graphics, sideLeft, cardY, sideW, sidePanelH, sideAccent, false, timeSec);

        if (active) {
            renderConnectedPlayersPanel(graphics, cardY, sidePanelH, sideAccent, mouseX, mouseY);
        } else {
            graphics.text(this.font, Component.translatable("screen.peerpulsar.host.side_menu_title").withStyle(ChatFormatting.BOLD),
                    sideLeft + 10, cardY + 9, NeonTheme.TEXT_PRIMARY, true);
            graphics.horizontalLine(sideLeft + 10, sideLeft + sideW - 10, cardY + 20, NeonTheme.withAlpha(sideAccent, 70));

            int legendY = cardY + sidePanelH - 14;
            graphics.horizontalLine(sideLeft + 10, sideLeft + sideW - 10, legendY - 4, NeonTheme.withAlpha(NeonTheme.SLATE, 55));

            String supText = tr("screen.peerpulsar.host.legend_supported");
            String unsupText = tr("screen.peerpulsar.host.legend_unsupported");
            if (sideLeft + 18 + this.font.width(supText) + 14 + this.font.width(unsupText) > sideLeft + sideW - 6) {
                supText = "OK";
                unsupText = "Blocked";
            }
            graphics.fill(sideLeft + 10, legendY + 1, sideLeft + 15, legendY + 6, NeonTheme.withAlpha(NeonTheme.EMERALD, 255));
            graphics.text(this.font, supText, sideLeft + 18, legendY, NeonTheme.TEXT_SECONDARY, false);

            int redDotX = sideLeft + 24 + this.font.width(supText);
            graphics.fill(redDotX, legendY + 1, redDotX + 5, legendY + 6, NeonTheme.withAlpha(NeonTheme.CRIMSON, 255));
            graphics.text(this.font, unsupText, redDotX + 8, legendY, NeonTheme.TEXT_SECONDARY, false);
        }
    }

    private void renderConnectedPlayersPanel(GuiGraphicsExtractor graphics, int cardY, int sidePanelH, int accent, int mouseX, int mouseY) {
        graphics.text(this.font, Component.translatable("screen.peerpulsar.host.players_panel_title").withStyle(ChatFormatting.BOLD),
                sideLeft + 10, cardY + 9, NeonTheme.TEXT_PRIMARY, true);
        graphics.horizontalLine(sideLeft + 10, sideLeft + sideW - 10, cardY + 20, NeonTheme.withAlpha(accent, 70));

        List<ConnectedPlayerEntry> players = getConnectedPlayers();
        int listX = sideLeft + 8;
        int listW = sideW - 16;
        int listY = cardY + 24;
        int listH = sidePanelH - 44;
        int rowH = 19;
        int maxRows = Math.max(1, listH / rowH);
        playersScroll = Math.max(0, Math.min(playersScroll, Math.max(0, players.size() - maxRows)));

        for (int i = 0; i < maxRows && (i + playersScroll) < players.size(); i++) {
            ConnectedPlayerEntry entry = players.get(i + playersScroll);
            int ry = listY + i * rowH;
            graphics.fill(listX, ry, listX + listW, ry + rowH - 2, 0x77090F1D);
            graphics.outline(listX, ry, listW, rowH - 2, NeonTheme.withAlpha(entry.isHost() ? NeonTheme.EMERALD : NeonTheme.SLATE, 70));

            int rightLimit = listX + listW - 4;
            if (!entry.isHost()) {
                int kickW = 28;
                int kickX = listX + listW - kickW - 3;
                int kickY = ry + 2;
                boolean hov = mouseX >= kickX && mouseX <= kickX + kickW && mouseY >= kickY && mouseY <= kickY + 13;
                graphics.fill(kickX, kickY, kickX + kickW, kickY + 13, NeonTheme.withAlpha(NeonTheme.CRIMSON, hov ? 150 : 65));
                graphics.outline(kickX, kickY, kickW, 13, NeonTheme.withAlpha(NeonTheme.CRIMSON, hov ? 255 : 165));
                graphics.centeredText(this.font, tr("screen.peerpulsar.host.btn_kick"), kickX + kickW / 2, kickY + 3, NeonTheme.TEXT_PRIMARY);
                rightLimit = kickX - 3;
            }

            String pingStr = entry.isHost() ? "HOST" : (entry.pingMs() + "ms");
            int pingW = this.font.width(pingStr);
            int pingColor = entry.isHost() ? NeonTheme.withAlpha(NeonTheme.EMERALD, 255) : NeonTheme.TEXT_SECONDARY;
            graphics.text(this.font, pingStr, rightLimit - pingW, ry + 5, pingColor, false);

            int maxNameW = Math.max(24, (rightLimit - pingW - 4) - (listX + 5));
            String drawnName = entry.name();
            if (this.font.width(drawnName) > maxNameW) {
                drawnName = this.font.plainSubstrByWidth(drawnName, Math.max(10, maxNameW - 6)) + "..";
            }
            graphics.text(this.font, drawnName, listX + 5, ry + 5, NeonTheme.TEXT_PRIMARY, false);
        }

        int hintY = cardY + sidePanelH - 14;
        graphics.horizontalLine(sideLeft + 10, sideLeft + sideW - 10, hintY - 4, NeonTheme.withAlpha(NeonTheme.SLATE, 55));
        String hint = this.font.plainSubstrByWidth(tr("screen.peerpulsar.host.kick_hint"), sideW - 18);
        graphics.text(this.font, hint, sideLeft + 9, hintY, NeonTheme.TEXT_MUTED, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == 0 && networkManager.isP2PActive()) {
            int cardY = topY + (compact ? 28 : 34);
            int sideCardH = compact ? 28 : 31;
            int sideCardGap = compact ? 4 : 5;
            int sidePanelH = 24 + (sideCardH * 4) + (sideCardGap * 3) + 24;
            int listX = sideLeft + 8;
            int listW = sideW - 16;
            int listY = cardY + 24;
            int listH = sidePanelH - 44;
            int rowH = 19;
            int maxRows = Math.max(1, listH / rowH);
            List<ConnectedPlayerEntry> players = getConnectedPlayers();
            double mx = event.x();
            double my = event.y();

            for (int i = 0; i < maxRows && (i + playersScroll) < players.size(); i++) {
                ConnectedPlayerEntry entry = players.get(i + playersScroll);
                if (entry.isHost()) continue;
                int ry = listY + i * rowH;
                int kickW = 28;
                int kickX = listX + listW - kickW - 3;
                int kickY = ry + 2;
                if (mx >= kickX && mx <= kickX + kickW && my >= kickY && my <= kickY + 13) {
                    kickPlayer(entry);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (networkManager.isP2PActive() && mouseX >= sideLeft && mouseX <= sideLeft + sideW) {
            playersScroll += (scrollY < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void kickPlayer(ConnectedPlayerEntry entry) {
        if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) return;
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer target = server.getPlayerList().getPlayer(entry.uuid());
            if (target != null) {
                target.connection.disconnect(Component.translatable("screen.peerpulsar.host.kicked_reason"));
            }
        });
        showToast(tr("screen.peerpulsar.host.toast_kicked", entry.name()), NeonTheme.CRIMSON);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        NeonRenderUtils.drawToast(graphics, this.font, this.width, this.height,
                toastMessage, toastAccent, toastStartMs, TOAST_DURATION_MS);
    }

    @Override
    public void tick() {
        super.tick();
        if (networkManager.isP2PActive() != lastSyncedP2PActive
                || networkManager.isStarting() != lastSyncedStarting
                || NetworkDiagnosticsService.getInstance().isTesting() != lastSyncedDiagTesting) {
            updateWidgetStates();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
