package com.kynxhel.peerpulsar.client.gui;

import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.client.network.TransportSwitchAdvisor;
import com.kynxhel.peerpulsar.client.network.UdpHolePunchService;
import com.kynxhel.peerpulsar.client.network.UdpTransportMode;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Renders a bottom-right translucent Neon toast/card when PeerPulsar detects a sustained
 * network shift suggesting a better UDP engine (RUDP / KCP / QUIC).
 * Clicking Switch disconnects cleanly and auto-reconnects using the selected engine.
 */
public final class TransportRecommendationOverlay {
    private static final int BOX_W = 246;
    private static final int BOX_H = 74;
    private static final int MARGIN = 10;
    private static final long DISPLAY_DURATION_MS = 18_000L;

    private static volatile ActiveCard currentCard = null;

    private record ActiveCard(
            TransportSwitchAdvisor.SwitchRecommendation rec,
            long shownAtMs,
            long expiresAtMs
    ) {}

    private TransportRecommendationOverlay() {}

    public static void register() {
        UdpHolePunchService.getInstance().setSwitchRecommendationListener(rec -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || rec == null) return;
            mc.execute(() -> {
                if (!UdpHolePunchService.getInstance().isClientActive()) return;
                long now = System.currentTimeMillis();
                currentCard = new ActiveCard(rec, now, now + DISPLAY_DURATION_MS);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.15f));
                if (mc.player != null) {
                    mc.player.sendSystemMessage(buildChatPrompt(rec));
                }
            });
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("peerpulsar_transport")
                        .then(ClientCommands.literal("accept").executes(ctx -> {
                            acceptCurrent();
                            return 1;
                        }))
                        .then(ClientCommands.literal("dismiss").executes(ctx -> {
                            dismissCurrent();
                            return 1;
                        }))
                )
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("peerpulsar", "transport_switch_hint"),
                TransportRecommendationOverlay::renderHud
        );

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    renderCard(graphics, client, s.width, s.height, mouseX, mouseY, true)
            );
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                    handleScreenClick(s, event)
            );
        });
    }

    public static void clear() {
        currentCard = null;
    }

    public static void acceptCurrent() {
        ActiveCard card = currentCard;
        currentCard = null;
        if (card == null) return;
        P2PNetworkManager.getInstance().reconnectUdpWithTransport(card.rec().targetEngine());
    }

    public static void dismissCurrent() {
        currentCard = null;
        UdpHolePunchService.getInstance().dismissSwitchRecommendation();
    }

    private static ActiveCard pollActive() {
        ActiveCard card = currentCard;
        if (card == null) return null;
        if (!UdpHolePunchService.getInstance().isClientActive()) {
            currentCard = null;
            return null;
        }
        if (System.currentTimeMillis() >= card.expiresAtMs()) {
            currentCard = null;
            UdpHolePunchService.getInstance().dismissSwitchRecommendation();
            return null;
        }
        return card;
    }

    private static Component buildChatPrompt(TransportSwitchAdvisor.SwitchRecommendation rec) {
        String reason = formatMetrics(rec);
        MutableComponent msg = Component.literal("§b✦ [PeerPulsar] §f")
                .append(Component.translatable("screen.peerpulsar.transport.chat_prompt",
                        rec.targetEngine().displayName(), reason))
                .append(Component.literal(" "));
        msg.append(Component.literal("[" + Component.translatable("screen.peerpulsar.transport.btn_switch").getString() + "]")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/peerpulsar_transport accept"))));
        msg.append(Component.literal(" "));
        msg.append(Component.literal("[" + Component.translatable("screen.peerpulsar.transport.btn_dismiss").getString() + "]")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.RunCommand("/peerpulsar_transport dismiss"))));
        return msg;
    }

    private static int accentFor(UdpTransportMode engine) {
        return switch (engine) {
            case RUDP -> NeonTheme.EMERALD;
            case KCP -> NeonTheme.AMBER;
            case QUIC -> NeonTheme.CYAN;
            default -> NeonTheme.VIOLET;
        };
    }

    private static String formatMetrics(TransportSwitchAdvisor.SwitchRecommendation rec) {
        int rtt = (int) Math.round(rec.rttMs());
        int jit = (int) Math.round(rec.jitterMs());
        if (rec.lossPct() >= 0.5) {
            return Component.translatable("screen.peerpulsar.transport.metrics_loss",
                    rtt, String.format("%.1f", rec.lossPct())).getString();
        }
        return Component.translatable("screen.peerpulsar.transport.metrics_ping", rtt, jit).getString();
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || mc.gui.screen() != null) return;
        renderCard(graphics, mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), -1, -1, false);
    }

    private static void renderCard(
            GuiGraphicsExtractor graphics,
            Minecraft mc,
            int screenW,
            int screenH,
            int mouseX,
            int mouseY,
            boolean interactive
    ) {
        ActiveCard card = pollActive();
        if (card == null || mc == null) return;

        TransportSwitchAdvisor.SwitchRecommendation rec = card.rec();
        Font font = mc.font;
        int w = Math.min(BOX_W, Math.max(170, screenW - 20));
        int x = screenW - w - MARGIN;
        int y = Math.max(MARGIN, screenH - BOX_H - MARGIN);

        long now = System.currentTimeMillis();
        long remainingMs = Math.max(0L, card.expiresAtMs() - now);
        float frac = Math.max(0.0f, Math.min(1.0f, (float) remainingMs / DISPLAY_DURATION_MS));
        int accent = accentFor(rec.targetEngine());

        graphics.fill(x - 2, y - 2, x + w + 2, y + BOX_H + 2, 0xAA000000);
        graphics.fillGradient(x, y, x + w, y + BOX_H, 0xEC0E162B, 0xEC090D1A);
        graphics.outline(x, y, w, BOX_H, NeonTheme.withAlpha(accent, 225));
        graphics.fill(x + 1, y + 1, x + 3, y + BOX_H - 1, NeonTheme.withAlpha(accent, 255));

        String header = Component.translatable("screen.peerpulsar.transport.popup_title",
                rec.targetEngine().displayName()).getString();
        String timerText = ((remainingMs + 999L) / 1000L) + "s";

        graphics.text(font, header, x + 8, y + 6, NeonTheme.withAlpha(accent, 255), true);
        graphics.text(font, timerText, x + w - font.width(timerText) - 8, y + 6, NeonTheme.TEXT_SECONDARY, false);

        int maxLineW = w - 16;
        String line1 = formatMetrics(rec);
        if (font.width(line1) > maxLineW) {
            line1 = font.plainSubstrByWidth(line1, Math.max(20, maxLineW - 8)) + "...";
        }
        graphics.text(font, line1, x + 8, y + 19, NeonTheme.TEXT_PRIMARY, false);

        String line2 = Component.translatable("screen.peerpulsar.transport.popup_lag_note").getString();
        if (font.width(line2) > maxLineW) {
            line2 = font.plainSubstrByWidth(line2, Math.max(20, maxLineW - 8)) + "...";
        }
        graphics.text(font, line2, x + 8, y + 31, NeonTheme.TEXT_MUTED, false);

        int btnY = y + 46;
        int btnH = 17;
        int gap = 6;
        int btnW = (w - 16 - gap) / 2;
        drawActionButton(graphics, font, x + 8, btnY, btnW, btnH,
                Component.translatable("screen.peerpulsar.transport.btn_switch").getString(),
                NeonTheme.EMERALD, interactive && isInside(mouseX, mouseY, x + 8, btnY, btnW, btnH));
        drawActionButton(graphics, font, x + 8 + btnW + gap, btnY, btnW, btnH,
                Component.translatable("screen.peerpulsar.transport.btn_dismiss").getString(),
                NeonTheme.SLATE, interactive && isInside(mouseX, mouseY, x + 8 + btnW + gap, btnY, btnW, btnH));

        int progW = Math.round((w - 4) * frac);
        graphics.fill(x + 2, y + BOX_H - 3, x + 2 + progW, y + BOX_H - 1, NeonTheme.withAlpha(accent, 220));
    }

    private static void drawActionButton(
            GuiGraphicsExtractor graphics,
            Font font,
            int x,
            int y,
            int w,
            int h,
            String label,
            int accent,
            boolean hovered
    ) {
        graphics.fill(x, y, x + w, y + h, NeonTheme.withAlpha(accent, hovered ? 105 : 42));
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(accent, hovered ? 255 : 175));
        graphics.centeredText(font, label, x + w / 2, y + 5, hovered ? 0xFFFFFFFF : NeonTheme.TEXT_PRIMARY);
    }

    private static boolean handleScreenClick(Screen screen, MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        ActiveCard card = pollActive();
        if (card == null) return true;

        int w = Math.min(BOX_W, Math.max(170, screen.width - 20));
        int x = screen.width - w - MARGIN;
        int y = Math.max(MARGIN, screen.height - BOX_H - MARGIN);
        int btnY = y + 46;
        int btnH = 17;
        int gap = 6;
        int btnW = (w - 16 - gap) / 2;

        double mx = event.x();
        double my = event.y();
        if (isInside(mx, my, x + 8, btnY, btnW, btnH)) {
            acceptCurrent();
            return false;
        }
        if (isInside(mx, my, x + 8 + btnW + gap, btnY, btnW, btnH)) {
            dismissCurrent();
            return false;
        }
        return true;
    }

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
