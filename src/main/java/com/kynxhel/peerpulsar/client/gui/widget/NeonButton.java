package com.kynxhel.peerpulsar.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * Custom animated neon glassmorphism button for Minecraft 26.3:
 * Smooth hover glow bloom, diagonal glossy shimmer sweep, click ripple wave,
 * optional pulsing live beacon, and hand pointer cursor.
 */
public class NeonButton extends AbstractButton {
    private final Runnable onPressAction;
    private int accentRgb;
    private boolean pulsingBeacon;

    private float hoverProgress = 0.0f;
    private float rippleProgress = 1.0f;
    private long lastFrameNanos = System.nanoTime();

    public NeonButton(int x, int y, int width, int height, Component message, int accentRgb, Runnable onPressAction) {
        super(x, y, width, height, message);
        this.accentRgb = accentRgb;
        this.onPressAction = onPressAction;
    }

    public NeonButton setAccentRgb(int accentRgb) {
        this.accentRgb = accentRgb;
        return this;
    }

    public NeonButton setPulsingBeacon(boolean pulsingBeacon) {
        this.pulsingBeacon = pulsingBeacon;
        return this;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.rippleProgress = 0.0f;
        if (this.onPressAction != null) {
            this.onPressAction.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrameNanos) / 1_000_000_000.0f);
        lastFrameNanos = now;
        double timeSec = now / 1_000_000_000.0;

        boolean hovered = this.active && this.isHoveredOrFocused();
        float targetHover = hovered ? 1.0f : 0.0f;
        this.hoverProgress += (targetHover - this.hoverProgress) * Math.min(1.0f, dt * 14.0f);

        if (this.rippleProgress < 1.0f) {
            this.rippleProgress = Math.min(1.0f, this.rippleProgress + dt * 2.8f);
        }

        if (this.isHovered()) {
            graphics.requestCursor(this.active ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        int effectiveAccent = this.active ? this.accentRgb : NeonTheme.SLATE;

        // Outer neon bloom on hover (or pulsing beacon)
        float beacon = pulsingBeacon && this.active ? (0.45f + 0.55f * NeonTheme.pulse(timeSec, 4.0)) : 0.0f;
        float glowStrength = Math.max(this.hoverProgress, beacon * 0.75f);
        if (glowStrength > 0.02f && this.active) {
            graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2,
                    NeonTheme.withAlpha(effectiveAccent, Math.round(38 * glowStrength)));
            graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1,
                    NeonTheme.withAlpha(effectiveAccent, Math.round(70 * glowStrength)));
        }

        // Glass button body
        int topColor = this.active
                ? NeonTheme.lerpColor(0xD6121A30, NeonTheme.withAlpha(effectiveAccent, 105), this.hoverProgress * 0.75f)
                : 0xAA0C101C;
        int bottomColor = this.active
                ? NeonTheme.lerpColor(0xE00A0F1E, NeonTheme.withAlpha(effectiveAccent, 65), this.hoverProgress * 0.75f)
                : 0xAA080B14;
        graphics.fillGradient(x, y, x + w, y + h, topColor, bottomColor);

        // Left vertical accent bar
        int barAlpha = this.active ? Math.round(170 + 85 * this.hoverProgress) : 65;
        graphics.fill(x + 1, y + 2, x + 3, y + h - 2, NeonTheme.withAlpha(effectiveAccent, barAlpha));

        // Scissored shimmer sweep & click ripple inside button bounds
        graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);

        // Glossy shimmer sweep on hover
        if (this.hoverProgress > 0.05f && this.active) {
            float sweepPos = (float) ((timeSec * 1.15) % 1.6) - 0.3f;
            int sx = x + Math.round(sweepPos * w);
            int sw = Math.max(18, w / 4);
            graphics.fill(sx, y, sx + sw, y + h,
                    NeonTheme.withAlpha(0xFFFFFF, Math.round(28 * this.hoverProgress)));
            graphics.fill(sx + sw / 3, y, sx + (sw * 2) / 3, y + h,
                    NeonTheme.withAlpha(0xFFFFFF, Math.round(45 * this.hoverProgress)));
        }

        // Click ripple shockwave
        if (this.rippleProgress < 1.0f) {
            float ease = NeonTheme.easeOutCubic(this.rippleProgress);
            int rw = Math.round(w * ease);
            int cx = x + w / 2;
            int alpha = Math.round((1.0f - ease) * 110);
            graphics.fill(cx - rw / 2, y, cx + rw / 2, y + h, NeonTheme.withAlpha(0xFFFFFF, alpha));
        }

        graphics.disableScissor();

        // Crisp neon border
        int borderAlpha = this.active
                ? Math.round(115 + 140 * Math.max(this.hoverProgress, beacon * 0.6f))
                : 55;
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(effectiveAccent, borderAlpha));

        // Pulsing live dot on left if beacon enabled
        Font font = Minecraft.getInstance().font;
        int textXOffset = 0;
        if (this.pulsingBeacon && this.active) {
            int dx = x + 9;
            int dy = y + h / 2;
            graphics.fill(dx - 3, dy - 3, dx + 3, dy + 3, NeonTheme.withAlpha(effectiveAccent, Math.round(70 + 80 * beacon)));
            graphics.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFFFFFFFF);
            textXOffset = 5;
        }

        // Centered label
        int textColor = this.active
                ? NeonTheme.lerpColor(NeonTheme.TEXT_PRIMARY, 0xFFFFFFFF, this.hoverProgress)
                : NeonTheme.TEXT_MUTED;
        int textY = y + (h - 8) / 2;
        graphics.centeredText(font, this.getMessage(), x + w / 2 + textXOffset, textY, textColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
