package com.p2pworld.client.gui.fx;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * High-craft custom GUI rendering primitives for Minecraft 26.3:
 * Frosted glass cards, animated orbiting neon border beams, custom mod logo with pulsing halo,
 * live status badges, signal strength meters, radar loaders, and slide-in glass toasts.
 */
public final class NeonRenderUtils {
    private NeonRenderUtils() {}

    /**
     * Draws a frosted glass card with drop shadow, inner vertical gradient, corner brackets,
     * and an optional animated neon energy runner orbiting the perimeter.
     */
    public static void drawGlassCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                     int accentRgb, boolean animatedBorder, double timeSec) {
        // Soft multi-layer drop shadow
        graphics.fill(x - 3, y - 2, x + w + 3, y + h + 4, 0x33000000);
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 2, 0x55000000);

        // Glass body with subtle top-to-bottom depth gradient
        graphics.fillGradient(x, y, x + w, y + h, NeonTheme.CARD_BG_ALT, NeonTheme.CARD_BG);

        // Top subtle specular highlight
        graphics.fillGradient(x + 1, y + 1, x + w - 1, y + Math.min(22, h / 2),
                NeonTheme.withAlpha(accentRgb, 26),
                NeonTheme.withAlpha(accentRgb, 0));

        // Base border
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(accentRgb, 95));

        // Crisp neon corner brackets
        int corner = Math.min(10, Math.min(w, h) / 4);
        int brightCorner = NeonTheme.withAlpha(accentRgb, 235);
        // Top-left
        graphics.horizontalLine(x, x + corner, y, brightCorner);
        graphics.verticalLine(x, y, y + corner, brightCorner);
        // Top-right
        graphics.horizontalLine(x + w - 1 - corner, x + w - 1, y, brightCorner);
        graphics.verticalLine(x + w - 1, y, y + corner, brightCorner);
        // Bottom-left
        graphics.horizontalLine(x, x + corner, y + h - 1, brightCorner);
        graphics.verticalLine(x, y + h - 1 - corner, y + h - 1, brightCorner);
        // Bottom-right
        graphics.horizontalLine(x + w - 1 - corner, x + w - 1, y + h - 1, brightCorner);
        graphics.verticalLine(x + w - 1, y + h - 1 - corner, y + h - 1, brightCorner);

        // Animated glowing beam orbiting the card perimeter
        if (animatedBorder && w > 20 && h > 20) {
            int perimeter = 2 * (w + h);
            int head = (int) ((timeSec * 140.0) % perimeter);
            int tailLen = 34;
            for (int i = 0; i < tailLen; i++) {
                int pos = (head - i + perimeter) % perimeter;
                float fade = 1.0f - (float) i / tailLen;
                int alpha = Math.round(fade * fade * 240);
                int beamColor = i < 4
                        ? NeonTheme.withAlpha(0xFFFFFF, alpha)
                        : NeonTheme.withAlpha(accentRgb, alpha);
                int px, py;
                if (pos < w) {
                    px = x + pos;
                    py = y;
                } else if (pos < w + h) {
                    px = x + w - 1;
                    py = y + (pos - w);
                } else if (pos < 2 * w + h) {
                    px = x + w - 1 - (pos - w - h);
                    py = y + h - 1;
                } else {
                    px = x;
                    py = y + h - 1 - (pos - 2 * w - h);
                }
                graphics.fill(px, py, px + 1, py + 1, beamColor);
            }
        }
    }

    /**
     * Draws the custom generated Fabric P2P World logo icon with a pulsing neon aura behind it.
     */
    public static void drawModLogo(GuiGraphicsExtractor graphics, int x, int y, int size, int glowRgb, double timeSec) {
        float pulse = 0.7f + 0.3f * NeonTheme.pulse(timeSec, 3.0);
        // Layered square-radial glow aura
        graphics.fill(x - 4, y - 4, x + size + 4, y + size + 4, NeonTheme.withAlpha(glowRgb, Math.round(28 * pulse)));
        graphics.fill(x - 2, y - 2, x + size + 2, y + size + 2, NeonTheme.withAlpha(glowRgb, Math.round(64 * pulse)));

        // Render 512x512 icon texture scaled cleanly to size x size
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                NeonTheme.MOD_ICON,
                x, y,
                0.0f, 0.0f,
                size, size,
                size, size
        );

        // Subtle crisp frame around logo
        graphics.outline(x - 1, y - 1, size + 2, size + 2, NeonTheme.withAlpha(glowRgb, Math.round(180 * pulse)));
    }

    /**
     * Draws a glowing pill status badge with an optional pulsing live indicator dot.
     */
    public static int drawBadge(GuiGraphicsExtractor graphics, Font font, int x, int y,
                                String text, int accentRgb, boolean pulsingDot, double timeSec) {
        int dotSpace = pulsingDot ? 10 : 0;
        int textW = font.width(text);
        int w = textW + 12 + dotSpace;
        int h = 14;

        graphics.fill(x, y, x + w, y + h, NeonTheme.withAlpha(accentRgb, 36));
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(accentRgb, 165));

        if (pulsingDot) {
            float p = NeonTheme.pulse(timeSec, 4.5);
            int cx = x + 7;
            int cy = y + h / 2;
            graphics.fill(cx - 3, cy - 3, cx + 3, cy + 3, NeonTheme.withAlpha(accentRgb, Math.round(45 + 75 * p)));
            graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, NeonTheme.withAlpha(accentRgb, 255));
        }

        graphics.text(font, text, x + 6 + dotSpace, y + 3, NeonTheme.withAlpha(accentRgb, 255), false);
        return w;
    }

    /**
     * Draws a 5-bar live signal strength meter colored by latency in milliseconds.
     */
    public static void drawSignalBars(GuiGraphicsExtractor graphics, int x, int y, long latencyMs) {
        int activeBars;
        int color;
        if (latencyMs < 0) {
            activeBars = 0;
            color = NeonTheme.CRIMSON;
        } else if (latencyMs < 50) {
            activeBars = 5;
            color = NeonTheme.EMERALD;
        } else if (latencyMs < 100) {
            activeBars = 4;
            color = NeonTheme.CYAN;
        } else if (latencyMs < 160) {
            activeBars = 3;
            color = NeonTheme.AMBER;
        } else if (latencyMs < 260) {
            activeBars = 2;
            color = NeonTheme.AMBER;
        } else {
            activeBars = 1;
            color = NeonTheme.CRIMSON;
        }

        for (int i = 0; i < 5; i++) {
            int barH = 3 + i * 2;
            int bx = x + i * 4;
            int by = y + (11 - barH);
            int barColor = (i < activeBars)
                    ? NeonTheme.withAlpha(color, 255)
                    : NeonTheme.withAlpha(NeonTheme.SLATE, 70);
            graphics.fill(bx, by, bx + 2, y + 11, barColor);
        }
    }

    /**
     * Draws an animated indeterminate neon scanner bar (for UPnP discovery or Ping testing).
     */
    public static void drawScannerBar(GuiGraphicsExtractor graphics, int x, int y, int width, int accentRgb, double timeSec) {
        graphics.fill(x, y, x + width, y + 2, NeonTheme.withAlpha(accentRgb, 35));
        float wave = (float) (0.5 + 0.5 * Math.sin(timeSec * 4.2));
        int segW = Math.max(24, width / 4);
        int start = x + Math.round(wave * (width - segW));
        graphics.fill(start, y, start + segW, y + 2, NeonTheme.withAlpha(accentRgb, 235));
        graphics.fill(start + segW / 3, y, start + (segW * 2) / 3, y + 2, 0xFFFFFFFF);
    }

    /**
     * Draws a sleek slide-in/out glassmorphism toast notification at the bottom center of the screen.
     */
    public static void drawToast(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight,
                                 String message, int accentRgb, long startMs, long durationMs) {
        if (message == null) return;
        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed < 0 || elapsed >= durationMs) return;

        float inT = NeonTheme.easeOutBack(Math.min(1.0f, elapsed / 260.0f));
        float outT = elapsed > (durationMs - 300)
                ? NeonTheme.clamp01((durationMs - elapsed) / 300.0f)
                : 1.0f;
        float visibility = Math.min(inT, outT);

        int maxBoxW = Math.max(60, screenWidth - 16);
        int maxTextW = Math.max(20, maxBoxW - 26);
        String drawnMessage = message;
        if (font.width(drawnMessage) > maxTextW) {
            int ellW = font.width("...");
            drawnMessage = maxTextW > ellW
                    ? font.plainSubstrByWidth(drawnMessage, maxTextW - ellW) + "..."
                    : font.plainSubstrByWidth(drawnMessage, maxTextW);
        }

        int textW = font.width(drawnMessage);
        int boxW = Math.min(maxBoxW, textW + 26);
        int boxH = 22;
        int x = (screenWidth - boxW) / 2;
        int targetY = screenHeight - 36;
        int y = Math.round(targetY + (1.0f - visibility) * 24.0f);

        int bgAlpha = Math.round(225 * visibility);
        int borderAlpha = Math.round(220 * visibility);

        // Shadow & glass body
        graphics.fill(x - 2, y - 1, x + boxW + 2, y + boxH + 3, NeonTheme.withAlpha(0x000000, Math.round(90 * visibility)));
        graphics.fill(x, y, x + boxW, y + boxH, NeonTheme.withAlpha(0x0D1326, bgAlpha));
        graphics.outline(x, y, boxW, boxH, NeonTheme.withAlpha(accentRgb, borderAlpha));

        // Left glowing accent strip
        graphics.fill(x + 1, y + 1, x + 4, y + boxH - 1, NeonTheme.withAlpha(accentRgb, Math.round(255 * visibility)));

        // Depleting progress bar at bottom
        float remaining = 1.0f - ((float) elapsed / durationMs);
        int progW = Math.round((boxW - 4) * remaining);
        graphics.fill(x + 2, y + boxH - 2, x + 2 + progW, y + boxH - 1, NeonTheme.withAlpha(accentRgb, Math.round(210 * visibility)));

        // Message
        graphics.text(font, Component.literal(drawnMessage), x + 12, y + 7,
                NeonTheme.withAlpha(NeonTheme.TEXT_PRIMARY, Math.round(255 * visibility)), true);
    }
}
