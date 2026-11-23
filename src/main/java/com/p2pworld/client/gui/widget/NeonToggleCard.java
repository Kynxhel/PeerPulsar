package com.p2pworld.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.p2pworld.client.gui.fx.NeonTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Modern glassmorphism option card with an animated sliding toggle pill (or value badge).
 */
public class NeonToggleCard extends AbstractButton {
    private final String title;
    private final Supplier<String> valueSupplier;
    private final BooleanSupplier enabledStateSupplier; // null for cycle-only cards like GameMode
    private final IntSupplier accentSupplier;
    private final Runnable onPressAction;

    private float hoverProgress = 0.0f;
    private float toggleAnim;
    private long lastFrameNanos = System.nanoTime();

    public NeonToggleCard(int x, int y, int width, int height,
                          String title,
                          Supplier<String> valueSupplier,
                          BooleanSupplier enabledStateSupplier,
                          IntSupplier accentSupplier,
                          Runnable onPressAction) {
        super(x, y, width, height, Component.literal(title));
        this.title = title;
        this.valueSupplier = valueSupplier;
        this.enabledStateSupplier = enabledStateSupplier;
        this.accentSupplier = accentSupplier;
        this.onPressAction = onPressAction;
        this.toggleAnim = (enabledStateSupplier != null && enabledStateSupplier.getAsBoolean()) ? 1.0f : 0.0f;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (this.onPressAction != null) {
            this.onPressAction.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrameNanos) / 1_000_000_000.0f);
        lastFrameNanos = now;

        boolean hovered = this.active && this.isHoveredOrFocused();
        this.hoverProgress += ((hovered ? 1.0f : 0.0f) - this.hoverProgress) * Math.min(1.0f, dt * 14.0f);

        if (this.enabledStateSupplier != null) {
            float targetToggle = this.enabledStateSupplier.getAsBoolean() ? 1.0f : 0.0f;
            this.toggleAnim += (targetToggle - this.toggleAnim) * Math.min(1.0f, dt * 16.0f);
        }

        if (this.isHovered()) {
            graphics.requestCursor(this.active ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        int accent = this.active ? this.accentSupplier.getAsInt() : NeonTheme.SLATE;

        // Subtle outer glow on hover
        if (this.hoverProgress > 0.03f && this.active) {
            graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1,
                    NeonTheme.withAlpha(accent, Math.round(45 * this.hoverProgress)));
        }

        // Card background
        int topBg = this.active
                ? NeonTheme.lerpColor(0xCC0F162B, NeonTheme.withAlpha(accent, 52), this.hoverProgress * 0.65f)
                : 0x990A0E1A;
        int botBg = this.active ? 0xDD0A0E1C : 0x99070A12;
        graphics.fillGradient(x, y, x + w, y + h, topBg, botBg);

        // Border
        int borderAlpha = this.active ? Math.round(95 + 135 * this.hoverProgress) : 45;
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(accent, borderAlpha));

        // Left glowing indicator bar
        graphics.fill(x + 1, y + 3, x + 3, y + h - 3, NeonTheme.withAlpha(accent, this.active ? 225 : 65));

        Font font = Minecraft.getInstance().font;
        int titleColor = this.active ? NeonTheme.TEXT_SECONDARY : NeonTheme.TEXT_MUTED;
        int valueColor = this.active ? NeonTheme.withAlpha(accent, 255) : NeonTheme.TEXT_MUTED;

        graphics.text(font, this.title, x + 8, y + 5, titleColor, false);
        graphics.text(font, this.valueSupplier.get(), x + 8, y + 17, valueColor, true);

        // Animated sliding switch pill on the right side (for boolean toggles)
        if (this.enabledStateSupplier != null) {
            int pillW = 26;
            int pillH = 12;
            int px = x + w - pillW - 8;
            int py = y + (h - pillH) / 2;

            int trackColor = NeonTheme.lerpColor(0xCC1E293B, NeonTheme.withAlpha(accent, 175), this.toggleAnim);
            graphics.fill(px, py, px + pillW, py + pillH, trackColor);
            graphics.outline(px, py, pillW, pillH, NeonTheme.withAlpha(accent, Math.round(80 + 150 * this.toggleAnim)));

            int knobSize = 8;
            int knobX = px + 2 + Math.round(this.toggleAnim * (pillW - knobSize - 4));
            int knobY = py + 2;
            graphics.fill(knobX, knobY, knobX + knobSize, knobY + knobSize, this.active ? 0xFFFFFFFF : 0xFF94A3B8);
        } else {
            // Cycle chevron badge
            int bx = x + w - 18;
            int by = y + (h - 8) / 2;
            graphics.text(font, "⟳", bx, by, NeonTheme.withAlpha(accent, this.active ? 230 : 80), false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
