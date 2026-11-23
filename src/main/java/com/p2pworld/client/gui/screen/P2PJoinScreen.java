package com.p2pworld.client.gui.screen;

import com.p2pworld.client.gui.fx.NeonRenderUtils;
import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.fx.ParticleBackground;
import com.p2pworld.client.gui.widget.NeonButton;
import com.p2pworld.client.network.P2PNetworkManager;
import com.p2pworld.client.network.RoomCodeHelper;
import com.p2pworld.client.network.UdpHolePunchService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletionException;

public class P2PJoinScreen extends Screen {
    private final Screen parent;
    private final ParticleBackground particleBg = new ParticleBackground();
    private final long openTimeMs = System.currentTimeMillis();

    private EditBox inputField;
    private NeonButton pasteButton;
    private NeonButton testPingButton;
    private NeonButton connectButton;
    private NeonButton backButton;

    private String savedInputText = "";
    private RoomCodeHelper.ConnectionTarget parsedTarget = null;
    private String activeOpTargetLabel = "";
    private P2PNetworkManager.PingResult lastPingResult = null;
    private boolean isTesting = false;
    private boolean isPunching = false;

    private String toastMessage = null;
    private int toastAccent = NeonTheme.CYAN;
    private long toastStartMs = 0;

    public P2PJoinScreen(Screen parent) {
        super(Component.translatable("screen.fabricp2p.join.title"));
        this.parent = parent;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private int computeCardY() {
        return Math.max(46, Math.min(this.height - 172, this.height / 2 - 66));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int panelW = Math.min(328, this.width - 20);
        int panelLeft = cx - panelW / 2;
        int cardY = computeCardY();

        // Borderless EditBox nested inside custom glowing neon frame
        this.inputField = new EditBox(this.font, panelLeft + 18, cardY + 34, panelW - 104, 14, Component.literal("Room Code / IP"));
        this.inputField.setMaxLength(256);
        this.inputField.setBordered(false);
        this.inputField.setTextColor(NeonTheme.TEXT_PRIMARY);
        this.inputField.setHint(Component.translatable("screen.fabricp2p.join.placeholder").withStyle(ChatFormatting.DARK_GRAY));
        this.inputField.setValue(this.savedInputText);
        this.inputField.setResponder(this::onInputChanged);
        this.addRenderableWidget(inputField);
        this.setInitialFocus(inputField);

        // Smart Paste button (auto-extracts room code even from a full Discord invite!)
        this.pasteButton = new NeonButton(
                panelLeft + panelW - 76, cardY + 28, 64, 22,
                Component.translatable("screen.fabricp2p.join.btn_paste"),
                NeonTheme.CYAN,
                () -> {
                    if (this.minecraft == null) return;
                    String clip = this.minecraft.keyboardHandler.getClipboard();
                    if (clip != null && !clip.trim().isEmpty()) {
                        String extracted = RoomCodeHelper.extractCleanCode(clip);
                        this.inputField.setValue(extracted);
                        particleBg.spawnBurst(pasteButton.getX() + 32, pasteButton.getY() + 11, NeonTheme.CYAN, 20);
                        showToast(tr("screen.fabricp2p.join.toast_pasted"), NeonTheme.CYAN);
                    }
                }
        );
        this.addRenderableWidget(pasteButton);

        // Action Row: Test Ping & Connect
        int btnY = cardY + 112;
        int halfW = (panelW - 10) / 2;

        this.testPingButton = new NeonButton(
                panelLeft, btnY, halfW, 24,
                Component.translatable("screen.fabricp2p.join.btn_test"),
                NeonTheme.VIOLET,
                this::testConnection
        );
        this.addRenderableWidget(testPingButton);

        this.connectButton = new NeonButton(
                panelLeft + halfW + 10, btnY, halfW, 24,
                Component.translatable("screen.fabricp2p.join.btn_connect").withStyle(ChatFormatting.BOLD),
                NeonTheme.EMERALD,
                this::connectToWorld
        );
        this.addRenderableWidget(connectButton);

        // Cancel / Back Button
        this.backButton = new NeonButton(
                cx - 70, btnY + 30, 140, 20,
                Component.literal("← ").append(Component.translatable("gui.cancel")),
                NeonTheme.SLATE,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(this.parent);
                    }
                }
        );
        this.addRenderableWidget(backButton);

        // Sync button states after init / window resize
        syncButtonStates();
    }

    private void onInputChanged(String text) {
        this.savedInputText = text != null ? text : "";
        this.parsedTarget = RoomCodeHelper.parseInput(this.savedInputText);
        this.lastPingResult = null;
        syncButtonStates();
    }

    private void syncButtonStates() {
        boolean valid = (this.parsedTarget != null);
        if (this.connectButton != null) {
            this.connectButton.active = valid && !this.isPunching;
            this.connectButton.setPulsingBeacon(valid && !this.isPunching);
            if (this.isPunching) {
                this.connectButton.setAccentRgb(NeonTheme.AMBER);
                this.connectButton.setMessage(Component.translatable("screen.fabricp2p.join.btn_punching").withStyle(ChatFormatting.BOLD));
            } else {
                this.connectButton.setAccentRgb(NeonTheme.EMERALD);
                this.connectButton.setMessage(Component.translatable("screen.fabricp2p.join.btn_connect").withStyle(ChatFormatting.BOLD));
            }
        }
        if (this.testPingButton != null) {
            this.testPingButton.active = valid && !this.isTesting && !this.isPunching && !this.parsedTarget.isUdpHolePunch();
            this.testPingButton.setMessage(Component.translatable(
                    this.isTesting ? "screen.fabricp2p.join.btn_testing" : "screen.fabricp2p.join.btn_test"
            ));
        }
    }

    private static String extractErrorMessage(Throwable err) {
        if (err == null) return "error";
        Throwable cause = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
        String msg = cause.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cause.getClass().getSimpleName();
    }

    private void testConnection() {
        if (this.parsedTarget == null || this.parsedTarget.isUdpHolePunch() || this.minecraft == null || this.isTesting) return;
        RoomCodeHelper.ConnectionTarget target = this.parsedTarget;

        this.isTesting = true;
        this.activeOpTargetLabel = RoomCodeHelper.formatHostPort(target.host(), target.port());
        this.lastPingResult = null;
        syncButtonStates();

        P2PNetworkManager.getInstance().pingTarget(target.host(), target.port()).whenComplete((res, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.isTesting = false;
                if (err != null || res == null) {
                    String reason = extractErrorMessage(err);
                    this.lastPingResult = new P2PNetworkManager.PingResult(false, -1, "UNREACHABLE", null, -1, -1, reason);
                    syncButtonStates();
                    particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f + 10, NeonTheme.CRIMSON, 18);
                    showToast(tr("screen.fabricp2p.join.toast_ping_fail", reason), NeonTheme.CRIMSON);
                    return;
                }
                this.lastPingResult = res;
                syncButtonStates();

                if (res.success()) {
                    particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f + 10, NeonTheme.EMERALD, 36);
                    showToast(tr("screen.fabricp2p.join.toast_ping_ok", res.latencyMs()), NeonTheme.EMERALD);
                } else {
                    particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f + 10, NeonTheme.CRIMSON, 18);
                    showToast(tr("screen.fabricp2p.join.toast_ping_fail", res.motd()), NeonTheme.CRIMSON);
                }
            });
        });
    }

    private void connectToWorld() {
        if (this.parsedTarget == null || this.minecraft == null || this.isPunching) return;
        RoomCodeHelper.ConnectionTarget target = this.parsedTarget;

        if (target.isUdpHolePunch()) {
            this.isPunching = true;
            this.activeOpTargetLabel = target.host();
            syncButtonStates();
            showToast(tr("screen.fabricp2p.join.toast_punching"), NeonTheme.CYAN);

            UdpHolePunchService.getInstance().startClientProxyAsync(target.host()).whenComplete((res, err) -> {
                if (this.minecraft == null) return;
                this.minecraft.execute(() -> {
                    this.isPunching = false;
                    if (err != null || res == null) {
                        syncButtonStates();
                        showToast(tr("screen.fabricp2p.join.toast_punch_fail", extractErrorMessage(err)), NeonTheme.CRIMSON);
                        return;
                    }
                    if (res.success()) {
                        ServerData serverData = new ServerData(
                                "UDP P2P (" + target.host() + ")",
                                "127.0.0.1:" + res.localPort(),
                                ServerData.Type.OTHER
                        );
                        ConnectScreen.startConnecting(
                                this.parent,
                                this.minecraft,
                                new ServerAddress("127.0.0.1", res.localPort()),
                                serverData,
                                false,
                                null
                        );
                    } else {
                        syncButtonStates();
                        showToast(tr("screen.fabricp2p.join.toast_punch_fail", res.error() != null ? res.error() : "timeout"), NeonTheme.CRIMSON);
                    }
                });
            });
            return;
        }

        String formattedAddr = RoomCodeHelper.formatHostPort(target.host(), target.port());
        ServerData serverData = new ServerData(
                "P2P World (" + target.host() + ")",
                formattedAddr,
                ServerData.Type.OTHER
        );

        ConnectScreen.startConnecting(
                this.parent,
                this.minecraft,
                new ServerAddress(target.host(), target.port()),
                serverData,
                false,
                null
        );
    }

    private void showToast(String msg, int accentRgb) {
        this.toastMessage = msg;
        this.toastAccent = accentRgb;
        this.toastStartMs = System.currentTimeMillis();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        double timeSec = NeonTheme.nowSec();
        float enterT = NeonTheme.easeOutCubic(Math.min(1.0f, (System.currentTimeMillis() - openTimeMs) / 360.0f));

        boolean online = lastPingResult != null && lastPingResult.success();
        graphics.blurBeforeThisStratum();
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, online);

        int cx = this.width / 2;
        int panelW = Math.min(328, this.width - 20);
        int panelLeft = cx - panelW / 2;
        int cardY = computeCardY();
        int slideOffset = Math.round((1.0f - enterT) * -10.0f);
        RoomCodeHelper.ConnectionTarget currentTarget = this.parsedTarget;
        int cardAccent = online ? NeonTheme.EMERALD : (currentTarget != null ? NeonTheme.CYAN : NeonTheme.VIOLET);

        // 1. Floating Logo & Header above Portal Card
        int floatBob = Math.round((float) Math.sin(timeSec * 2.4) * 2.0f);
        int headerY = cardY - 40 + slideOffset;
        NeonRenderUtils.drawModLogo(graphics, panelLeft + 2, headerY + floatBob, 28, cardAccent, timeSec);
        graphics.text(this.font, Component.translatable("screen.fabricp2p.join.header").withStyle(ChatFormatting.BOLD),
                panelLeft + 38, headerY + 3, NeonTheme.TEXT_PRIMARY, true);
        graphics.text(this.font, Component.translatable("screen.fabricp2p.join.instruction"),
                panelLeft + 38, headerY + 16, NeonTheme.TEXT_SECONDARY, false);
        NeonRenderUtils.drawBadge(graphics, this.font, panelLeft + panelW - 54, headerY + 7,
                "MC 26.3", NeonTheme.CYAN, false, timeSec);

        // 2. Main Glass Portal Card
        int cardH = 104;
        NeonRenderUtils.drawGlassCard(graphics, panelLeft, cardY, panelW, cardH, cardAccent, true, timeSec);

        // Input Field Label & Custom Neon Frame
        graphics.text(this.font, Component.translatable("screen.fabricp2p.join.input_label"),
                panelLeft + 12, cardY + 12, NeonTheme.TEXT_SECONDARY, false);

        int inputBoxLeft = panelLeft + 12;
        int inputBoxTop = cardY + 28;
        int inputBoxW = panelW - 94;
        int inputBoxH = 22;
        int inputBorderAccent = (inputField != null && inputField.isFocused())
                ? NeonTheme.CYAN
                : (currentTarget != null ? NeonTheme.EMERALD : NeonTheme.SLATE);

        graphics.fill(inputBoxLeft, inputBoxTop, inputBoxLeft + inputBoxW, inputBoxTop + inputBoxH, NeonTheme.INPUT_BG);
        graphics.outline(inputBoxLeft, inputBoxTop, inputBoxW, inputBoxH,
                NeonTheme.withAlpha(inputBorderAccent, (inputField != null && inputField.isFocused()) ? 235 : 130));

        // 3. Telemetry Status Box inside Portal Card (null-safe even if input cleared mid-operation)
        int teleY = cardY + 58;
        int teleH = 36;
        graphics.fill(panelLeft + 12, teleY, panelLeft + panelW - 12, teleY + teleH, 0x99070B16);
        graphics.outline(panelLeft + 12, teleY, panelW - 24, teleH, NeonTheme.withAlpha(cardAccent, 75));

        if (isPunching) {
            String targetText = currentTarget != null ? currentTarget.host() : activeOpTargetLabel;
            graphics.text(this.font, tr("screen.fabricp2p.join.punching_status", targetText),
                    panelLeft + 20, teleY + 8, NeonTheme.withAlpha(NeonTheme.CYAN, 255), true);
            NeonRenderUtils.drawScannerBar(graphics, panelLeft + 20, teleY + 24, panelW - 40, NeonTheme.CYAN, timeSec);
        } else if (isTesting) {
            String targetText = currentTarget != null ? RoomCodeHelper.formatHostPort(currentTarget.host(), currentTarget.port()) : activeOpTargetLabel;
            graphics.text(this.font, tr("screen.fabricp2p.join.scanning", targetText),
                    panelLeft + 20, teleY + 8, NeonTheme.withAlpha(NeonTheme.AMBER, 255), true);
            NeonRenderUtils.drawScannerBar(graphics, panelLeft + 20, teleY + 24, panelW - 40, NeonTheme.AMBER, timeSec);
        } else if (lastPingResult != null) {
            if (lastPingResult.success()) {
                NeonRenderUtils.drawSignalBars(graphics, panelLeft + 20, teleY + 12, lastPingResult.latencyMs());
                String players = lastPingResult.onlinePlayers() >= 0
                        ? (" • " + tr("screen.fabricp2p.join.players", lastPingResult.onlinePlayers(), lastPingResult.maxPlayers()))
                        : "";
                graphics.text(this.font, tr("screen.fabricp2p.join.online_label", lastPingResult.latencyMs()) + players,
                        panelLeft + 46, teleY + 7, NeonTheme.withAlpha(NeonTheme.EMERALD, 255), true);
                String fallbackAddr = currentTarget != null ? RoomCodeHelper.formatHostPort(currentTarget.host(), currentTarget.port()) : activeOpTargetLabel;
                String sub = (lastPingResult.motd() != null && !lastPingResult.motd().isEmpty())
                        ? lastPingResult.motd()
                        : fallbackAddr;
                if (sub.length() > 44) sub = sub.substring(0, 44) + "...";
                graphics.text(this.font, sub, panelLeft + 46, teleY + 20, NeonTheme.TEXT_SECONDARY, false);
            } else {
                NeonRenderUtils.drawSignalBars(graphics, panelLeft + 20, teleY + 12, -1);
                graphics.text(this.font, tr("screen.fabricp2p.join.offline_title"),
                        panelLeft + 46, teleY + 7, NeonTheme.withAlpha(NeonTheme.CRIMSON, 255), true);
                graphics.text(this.font, tr("screen.fabricp2p.join.offline_hint"),
                        panelLeft + 46, teleY + 20, NeonTheme.TEXT_MUTED, false);
            }
        } else if (currentTarget != null) {
            String targetLabel = currentTarget.isUdpHolePunch()
                    ? tr("screen.fabricp2p.join.resolved_udp", currentTarget.host())
                    : tr("screen.fabricp2p.join.resolved", RoomCodeHelper.formatHostPort(currentTarget.host(), currentTarget.port()));
            String hintLabel = currentTarget.isUdpHolePunch()
                    ? tr("screen.fabricp2p.join.resolved_udp_hint")
                    : tr("screen.fabricp2p.join.resolved_hint");
            graphics.text(this.font, targetLabel,
                    panelLeft + 20, teleY + 8, NeonTheme.withAlpha(NeonTheme.CYAN, 255), true);
            graphics.text(this.font, hintLabel,
                    panelLeft + 20, teleY + 21, NeonTheme.TEXT_SECONDARY, false);
        } else {
            graphics.text(this.font, Component.translatable("screen.fabricp2p.join.status_idle"),
                    panelLeft + 20, teleY + 8, NeonTheme.TEXT_MUTED, false);
            graphics.text(this.font, tr("screen.fabricp2p.join.tip_discord"),
                    panelLeft + 20, teleY + 21, NeonTheme.withAlpha(NeonTheme.CYAN, 175), false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        NeonRenderUtils.drawToast(graphics, this.font, this.width, this.height,
                toastMessage, toastAccent, toastStartMs, 3200);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
