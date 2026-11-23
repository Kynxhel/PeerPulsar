package com.p2pworld.client.gui.screen;

import com.p2pworld.client.gui.fx.NeonRenderUtils;
import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.fx.ParticleBackground;
import com.p2pworld.client.gui.widget.NeonButton;
import com.p2pworld.client.shared.SharedWorldManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Displayed when exiting a Shared Cloud World via Pause Menu ("Save and Quit to Title").
 * Saves and incrementally uploads changed world files to Google Drive / Sync Folder first,
 * then cleanly disconnects and returns to the Title Screen.
 */
public class ExitSyncScreen extends Screen {
    private static final AtomicBoolean BYPASS_DISCONNECT_HOOK = new AtomicBoolean(false);

    private final Component quitReason;
    private final ParticleBackground particleBg = new ParticleBackground();
    private final long openTimeMs = System.currentTimeMillis();

    private NeonButton retryButton;
    private NeonButton skipQuitButton;

    private boolean started = false;
    private boolean syncing = true;
    private boolean failed = false;
    private boolean screenActive = true;
    private String statusDetail = "";

    public ExitSyncScreen(Component quitReason) {
        super(Component.translatable("screen.fabricp2p.exitsync.title"));
        this.quitReason = quitReason;
    }

    public static boolean isBypassingDisconnectHook() {
        return BYPASS_DISCONNECT_HOOK.get();
    }

    public static void disconnectBypassingSync(Minecraft mc, Component reason) {
        if (mc == null) return;
        BYPASS_DISCONNECT_HOOK.set(true);
        try {
            mc.disconnectFromWorld(reason);
        } finally {
            BYPASS_DISCONNECT_HOOK.set(false);
        }
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String extractErrorMessage(Throwable err) {
        if (err == null) return "Unknown error";
        Throwable cause = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
        String msg = cause.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cause.getClass().getSimpleName();
    }

    @Override
    protected void init() {
        this.screenActive = true;
        int cx = this.width / 2;
        int panelW = Math.min(316, this.width - 24);
        int cardH = 104;
        int cardY = Math.max(24, (this.height - cardH) / 2);
        int btnY = cardY + cardH - 28;
        int halfW = (panelW - 28) / 2;

        this.retryButton = new NeonButton(
                cx - halfW - 4, btnY, halfW, 20,
                Component.translatable("screen.fabricp2p.exitsync.btn_retry").withStyle(ChatFormatting.BOLD),
                NeonTheme.CYAN,
                this::startUpload
        );
        this.addRenderableWidget(retryButton);

        this.skipQuitButton = new NeonButton(
                cx + 4, btnY, halfW, 20,
                Component.translatable("screen.fabricp2p.exitsync.btn_skip"),
                NeonTheme.SLATE,
                this::quitNowWithoutWaiting
        );
        this.addRenderableWidget(skipQuitButton);

        updateButtons();

        if (!this.started) {
            this.started = true;
            startUpload();
        }
    }

    private void startUpload() {
        if (this.minecraft == null) return;
        this.syncing = true;
        this.failed = false;
        this.statusDetail = tr("screen.fabricp2p.exitsync.uploading");
        updateButtons();

        SharedWorldManager.getInstance().uploadCurrentWorldAsync(this.minecraft).whenComplete((res, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                if (!this.screenActive) return;
                this.syncing = false;
                if (err != null) {
                    this.failed = true;
                    this.statusDetail = extractErrorMessage(err);
                    updateButtons();
                } else if (res != null && res.success()) {
                    this.statusDetail = res.message();
                    SharedWorldManager.getInstance().suppressNextExitAutoSync();
                    disconnectBypassingSync(this.minecraft, this.quitReason);
                } else {
                    this.failed = true;
                    this.statusDetail = (res != null && res.message() != null) ? res.message() : tr("screen.fabricp2p.shared.toast_op_failed");
                    updateButtons();
                }
            });
        });
    }

    private void quitNowWithoutWaiting() {
        if (this.minecraft == null) return;
        SharedWorldManager.getInstance().suppressNextExitAutoSync();
        disconnectBypassingSync(this.minecraft, this.quitReason);
    }

    private void updateButtons() {
        if (this.retryButton != null) {
            this.retryButton.visible = this.failed;
            this.retryButton.active = this.failed && !this.syncing;
        }
        if (this.skipQuitButton != null) {
            this.skipQuitButton.setMessage(Component.translatable(
                    this.failed ? "screen.fabricp2p.exitsync.btn_quit_anyway" : "screen.fabricp2p.exitsync.btn_skip"
            ));
            this.skipQuitButton.setAccentRgb(this.failed ? NeonTheme.CRIMSON : NeonTheme.SLATE);
            if (!this.failed) {
                int cx = this.width / 2;
                this.skipQuitButton.setX(cx - this.skipQuitButton.getWidth() / 2);
            } else {
                int cx = this.width / 2;
                this.skipQuitButton.setX(cx + 4);
            }
        }
    }

    private String truncatePlain(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        int ellW = this.font.width("...");
        if (maxWidth <= ellW) {
            return this.font.plainSubstrByWidth(text, maxWidth);
        }
        return this.font.plainSubstrByWidth(text, maxWidth - ellW) + "...";
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        double timeSec = NeonTheme.nowSec();
        float enterT = NeonTheme.easeOutCubic(Math.min(1.0f, (System.currentTimeMillis() - openTimeMs) / 300.0f));

        graphics.blurBeforeThisStratum();
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, !failed);

        int cx = this.width / 2;
        int panelW = Math.min(316, this.width - 24);
        int panelLeft = cx - panelW / 2;
        int cardH = 104;
        int cardY = Math.max(24, (this.height - cardH) / 2) + Math.round((1.0f - enterT) * -8.0f);
        int accent = failed ? NeonTheme.CRIMSON : NeonTheme.CYAN;

        NeonRenderUtils.drawGlassCard(graphics, panelLeft, cardY, panelW, cardH, accent, syncing, timeSec);

        int maxTextW = Math.max(40, panelW - 24);
        Component heading = Component.translatable(
                failed ? "screen.fabricp2p.exitsync.failed_title" : "screen.fabricp2p.exitsync.heading"
        ).withStyle(ChatFormatting.BOLD);
        graphics.centeredText(this.font, heading, cx, cardY + 14, failed ? NeonTheme.withAlpha(NeonTheme.CRIMSON, 255) : NeonTheme.TEXT_PRIMARY);

        graphics.centeredText(this.font, truncatePlain(statusDetail, maxTextW), cx, cardY + 32,
                failed ? NeonTheme.TEXT_PRIMARY : NeonTheme.TEXT_SECONDARY);

        if (syncing) {
            NeonRenderUtils.drawScannerBar(graphics, panelLeft + 22, cardY + 52, panelW - 44, NeonTheme.CYAN, timeSec);
            graphics.centeredText(this.font, truncatePlain(tr("screen.fabricp2p.exitsync.hint"), maxTextW),
                    cx, cardY + 60, NeonTheme.TEXT_MUTED);
        }
    }

    @Override
    public void removed() {
        this.screenActive = false;
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
