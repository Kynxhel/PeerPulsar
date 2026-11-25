package com.kynxhel.peerpulsar.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.security.JoinApprovalManager;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * Renders an interactive Neon toast/card for incoming Join Approval requests both in-game
 * (HUD + clickable chat prompt + sound) and across any open GUI screen (including the Pause Menu).
 */
public final class JoinApprovalOverlay {
    private static final int BOX_W = 236;
    private static final int BOX_H = 60;
    private static final int MARGIN = 10;

    private JoinApprovalOverlay() {}

    public static void register() {
        JoinApprovalManager.getInstance().setListener(req -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.35f));
                if (mc.player != null) {
                    mc.player.sendSystemMessage(buildChatPrompt(req));
                }
            });
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("peerpulsar_approve")
                        .then(ClientCommands.literal("accept")
                                .then(ClientCommands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> handleCommand(StringArgumentType.getString(ctx, "id"), JoinApprovalManager.Decision.ACCEPT))))
                        .then(ClientCommands.literal("always")
                                .then(ClientCommands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> handleCommand(StringArgumentType.getString(ctx, "id"), JoinApprovalManager.Decision.ALWAYS))))
                        .then(ClientCommands.literal("deny")
                                .then(ClientCommands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> handleCommand(StringArgumentType.getString(ctx, "id"), JoinApprovalManager.Decision.DENY))))
                )
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("peerpulsar", "join_approval"),
                JoinApprovalOverlay::renderHud
        );

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    renderCard(graphics, client, s.width, mouseX, mouseY, true)
            );
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                    handleScreenClick(s, event)
            );
        });
    }

    private static int handleCommand(String id, JoinApprovalManager.Decision decision) {
        JoinApprovalManager.getInstance().decide(id, decision);
        return 1;
    }

    private static Component buildChatPrompt(JoinApprovalManager.PendingRequest req) {
        String prefix = req.differentDevice()
                ? "§e⚠ [PeerPulsar] Different device! "
                : (req.legacyClaim() ? "§e⚠ [PeerPulsar] Legacy data claim: " : "§b✦ [PeerPulsar] ");
        MutableComponent msg = Component.literal(prefix + "§f" + req.playerName()
                + " §7(device: " + req.shortFingerprint() + ") ");
        msg.append(Component.literal("[Accept]")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/peerpulsar_approve accept " + req.id()))));
        msg.append(Component.literal(" "));
        msg.append(Component.literal("[Always]")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/peerpulsar_approve always " + req.id()))));
        msg.append(Component.literal(" "));
        msg.append(Component.literal("[Deny]")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/peerpulsar_approve deny " + req.id()))));
        return msg;
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || mc.gui.screen() != null) return;
        renderCard(graphics, mc, mc.getWindow().getGuiScaledWidth(), -1, -1, false);
    }

    private static void renderCard(GuiGraphicsExtractor graphics, Minecraft mc, int screenW, int mouseX, int mouseY, boolean interactive) {
        JoinApprovalManager.PendingRequest req = JoinApprovalManager.getInstance().peekCurrent();
        if (req == null || mc == null) return;

        Font font = mc.font;
        int w = Math.min(BOX_W, Math.max(160, screenW - 20));
        int x = screenW - w - MARGIN;
        int y = MARGIN;

        long now = System.currentTimeMillis();
        long remainingMs = Math.max(0L, req.expiresAtMs() - now);
        long totalMs = Math.max(1L, req.expiresAtMs() - req.createdAtMs());
        float frac = Math.max(0.0f, Math.min(1.0f, (float) remainingMs / totalMs));
        int accent = req.differentDevice() || req.legacyClaim() ? NeonTheme.AMBER : NeonTheme.CYAN;

        graphics.fill(x - 2, y - 2, x + w + 2, y + BOX_H + 2, 0xAA000000);
        graphics.fillGradient(x, y, x + w, y + BOX_H, 0xEC0E162B, 0xEC090D1A);
        graphics.outline(x, y, w, BOX_H, NeonTheme.withAlpha(accent, 225));
        graphics.fill(x + 1, y + 1, x + 3, y + BOX_H - 1, NeonTheme.withAlpha(accent, 255));

        int pendingCount = JoinApprovalManager.getInstance().pendingCount();
        String header;
        if (req.differentDevice()) {
            header = Component.translatable("screen.peerpulsar.approval.diff_device").getString();
        } else if (req.legacyClaim()) {
            header = Component.translatable("screen.peerpulsar.approval.legacy_claim").getString();
        } else {
            header = Component.translatable("screen.peerpulsar.approval.title").getString();
        }
        if (pendingCount > 1) {
            header += " (1/" + pendingCount + ")";
        }
        String timerText = ((remainingMs + 999L) / 1000L) + "s";

        graphics.text(font, header, x + 8, y + 6, NeonTheme.withAlpha(accent, 255), true);
        graphics.text(font, timerText, x + w - font.width(timerText) - 8, y + 6, NeonTheme.TEXT_SECONDARY, false);

        String body = Component.translatable("screen.peerpulsar.approval.body", req.playerName(), req.shortFingerprint()).getString();
        int maxBodyW = w - 16;
        if (font.width(body) > maxBodyW) {
            body = font.plainSubstrByWidth(body, Math.max(20, maxBodyW - 8)) + "...";
        }
        graphics.text(font, body, x + 8, y + 19, NeonTheme.TEXT_PRIMARY, false);

        int btnY = y + 34;
        int btnH = 16;
        int gap = 5;
        int btnW = (w - 16 - gap * 2) / 3;
        drawActionButton(graphics, font, x + 8, btnY, btnW, btnH,
                Component.translatable("screen.peerpulsar.approval.accept").getString(),
                NeonTheme.EMERALD, interactive && isInside(mouseX, mouseY, x + 8, btnY, btnW, btnH));
        drawActionButton(graphics, font, x + 8 + btnW + gap, btnY, btnW, btnH,
                Component.translatable("screen.peerpulsar.approval.always").getString(),
                NeonTheme.CYAN, interactive && isInside(mouseX, mouseY, x + 8 + btnW + gap, btnY, btnW, btnH));
        drawActionButton(graphics, font, x + 8 + (btnW + gap) * 2, btnY, btnW, btnH,
                Component.translatable("screen.peerpulsar.approval.deny").getString(),
                NeonTheme.CRIMSON, interactive && isInside(mouseX, mouseY, x + 8 + (btnW + gap) * 2, btnY, btnW, btnH));

        int progW = Math.round((w - 4) * frac);
        graphics.fill(x + 2, y + BOX_H - 3, x + 2 + progW, y + BOX_H - 1, NeonTheme.withAlpha(accent, 220));
    }

    private static void drawActionButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h,
                                         String label, int accent, boolean hovered) {
        graphics.fill(x, y, x + w, y + h, NeonTheme.withAlpha(accent, hovered ? 95 : 40));
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(accent, hovered ? 255 : 175));
        graphics.centeredText(font, label, x + w / 2, y + 4, hovered ? 0xFFFFFFFF : NeonTheme.TEXT_PRIMARY);
    }

    private static boolean handleScreenClick(Screen screen, MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        JoinApprovalManager.PendingRequest req = JoinApprovalManager.getInstance().peekCurrent();
        if (req == null) return true;

        int w = Math.min(BOX_W, Math.max(160, screen.width - 20));
        int x = screen.width - w - MARGIN;
        int y = MARGIN;
        int btnY = y + 34;
        int btnH = 16;
        int gap = 5;
        int btnW = (w - 16 - gap * 2) / 3;

        double mx = event.x();
        double my = event.y();
        if (isInside(mx, my, x + 8, btnY, btnW, btnH)) {
            playClickSound();
            JoinApprovalManager.getInstance().decide(req.id(), JoinApprovalManager.Decision.ACCEPT);
            return false;
        }
        if (isInside(mx, my, x + 8 + btnW + gap, btnY, btnW, btnH)) {
            playClickSound();
            JoinApprovalManager.getInstance().decide(req.id(), JoinApprovalManager.Decision.ALWAYS);
            return false;
        }
        if (isInside(mx, my, x + 8 + (btnW + gap) * 2, btnY, btnW, btnH)) {
            playClickSound();
            JoinApprovalManager.getInstance().decide(req.id(), JoinApprovalManager.Decision.DENY);
            return false;
        }
        return true;
    }

    private static void playClickSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
