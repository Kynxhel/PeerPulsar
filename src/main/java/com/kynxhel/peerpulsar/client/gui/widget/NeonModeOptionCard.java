package com.kynxhel.peerpulsar.client.gui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Selectable connection-mode option card for the right-side menu panel.
 * Displays a green (supported), red (unsupported), or amber (testing) status dot,
 * an optional "(Recommended)" tag on the fastest available method, and selection highlight.
 */
public class NeonModeOptionCard extends AbstractButton {
    private final String title;
    private final Supplier<String> subtitleSupplier;
    private final BooleanSupplier supportedSupplier;
    private final BooleanSupplier testingSupplier;
    private final BooleanSupplier selectedSupplier;
    private final BooleanSupplier recommendedSupplier;
    private final int accentRgb;
    private final Runnable onSelectAction;

    private float hoverProgress = 0.0f;
    private long lastFrameNanos = System.nanoTime();

    public NeonModeOptionCard(int x, int y, int width, int height,
                              String title,
                              Supplier<String> subtitleSupplier,
                              BooleanSupplier supportedSupplier,
                              BooleanSupplier testingSupplier,
                              BooleanSupplier selectedSupplier,
                              BooleanSupplier recommendedSupplier,
                              int accentRgb,
                              Runnable onSelectAction) {
        super(x, y, width, height, Component.literal(title));
        this.title = title;
        this.subtitleSupplier = subtitleSupplier;
        this.supportedSupplier = supportedSupplier;
        this.testingSupplier = testingSupplier;
        this.selectedSupplier = selectedSupplier;
        this.recommendedSupplier = recommendedSupplier;
        this.accentRgb = accentRgb;
        this.onSelectAction = onSelectAction;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (this.active && !this.testingSupplier.getAsBoolean() && this.supportedSupplier.getAsBoolean() && this.onSelectAction != null) {
            this.onSelectAction.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrameNanos) / 1_000_000_000.0f);
        lastFrameNanos = now;

        boolean testing = this.testingSupplier.getAsBoolean();
        boolean supported = !testing && this.supportedSupplier.getAsBoolean();
        boolean selected = this.selectedSupplier.getAsBoolean();
        boolean recommended = !testing && supported && this.recommendedSupplier.getAsBoolean();
        boolean clickable = this.active && supported;

        boolean hovered = clickable && this.isHoveredOrFocused();
        this.hoverProgress += ((hovered ? 1.0f : 0.0f) - this.hoverProgress) * Math.min(1.0f, dt * 14.0f);

        if (this.isHovered()) {
            graphics.requestCursor(clickable ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        int dotRgb = testing ? NeonTheme.AMBER : (supported ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
        int cardAccent = selected ? this.accentRgb : (supported ? NeonTheme.SLATE : NeonTheme.CRIMSON);

        // Outer glow when selected or hovered
        if (selected || (this.hoverProgress > 0.04f && clickable)) {
            int glowAlpha = selected ? 42 : Math.round(35 * this.hoverProgress);
            graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, NeonTheme.withAlpha(this.accentRgb, glowAlpha));
        }

        // Background
        int topBg;
        int botBg;
        if (selected) {
            topBg = NeonTheme.withAlpha(this.accentRgb, 52);
            botBg = 0xDD0A1022;
        } else if (!supported && !testing) {
            topBg = 0x99160A0E;
            botBg = 0x990C070A;
        } else {
            topBg = NeonTheme.lerpColor(0xCC0F162B, NeonTheme.withAlpha(this.accentRgb, 42), this.hoverProgress);
            botBg = 0xDD0A0E1C;
        }
        graphics.fillGradient(x, y, x + w, y + h, topBg, botBg);

        // Border
        int borderAlpha = selected ? 235 : (clickable ? Math.round(75 + 120 * this.hoverProgress) : 65);
        graphics.outline(x, y, w, h, NeonTheme.withAlpha(cardAccent, borderAlpha));

        // Left selection bar
        if (selected) {
            graphics.fill(x + 1, y + 2, x + 3, y + h - 2, NeonTheme.withAlpha(this.accentRgb, 255));
        }

        double timeSec = NeonTheme.nowSec();
        float pulse = NeonTheme.pulse(timeSec, testing ? 7.0 : 3.5);

        // Status Dot (Green = supported, Red = unsupported, Amber = testing)
        int dotCx = x + 10;
        int dotCy = y + 10;
        graphics.fill(dotCx - 3, dotCy - 3, dotCx + 4, dotCy + 4,
                NeonTheme.withAlpha(dotRgb, Math.round(45 + 65 * pulse)));
        graphics.fill(dotCx - 2, dotCy - 2, dotCx + 3, dotCy + 3,
                NeonTheme.withAlpha(dotRgb, 255));

        Font font = Minecraft.getInstance().font;

        // Title + "(Recommended)" badge
        int titleX = x + 18;
        int maxTextW = Math.max(20, w - 22);
        int titleColor = supported ? NeonTheme.TEXT_PRIMARY : (testing ? NeonTheme.TEXT_SECONDARY : 0xFF9E7B82);
        String drawnTitle = this.title != null ? this.title : "";
        if (font.width(drawnTitle) > maxTextW) {
            drawnTitle = font.plainSubstrByWidth(drawnTitle, maxTextW);
        }
        Component titleComp = Component.literal(drawnTitle).withStyle(selected ? ChatFormatting.BOLD : ChatFormatting.RESET);
        graphics.text(font, titleComp, titleX, y + 6, titleColor, selected);

        if (recommended) {
            int recX = titleX + font.width(titleComp) + 4;
            String recTag = "(Recommended)";
            if (recX + font.width(recTag) <= x + w - 4) {
                graphics.text(font, recTag, recX, y + 6, NeonTheme.withAlpha(NeonTheme.EMERALD, 255), false);
            } else if (recX + font.width("(Rec)") <= x + w - 4) {
                graphics.text(font, "(Rec)", recX, y + 6, NeonTheme.withAlpha(NeonTheme.EMERALD, 255), false);
            }
        } else if (selected && titleX + font.width(titleComp) + 4 <= x + w - 12) {
            graphics.text(font, "✓", x + w - 12, y + 6, NeonTheme.withAlpha(this.accentRgb, 255), true);
        }

        // Subtitle (speed / reason)
        String rawSub = this.subtitleSupplier.get();
        String sub = rawSub != null ? rawSub : "";
        if (font.width(sub) > maxTextW) {
            int ellW = font.width("...");
            sub = maxTextW > ellW
                    ? font.plainSubstrByWidth(sub, maxTextW - ellW) + "..."
                    : font.plainSubstrByWidth(sub, maxTextW);
        }
        int subColor = testing
                ? NeonTheme.withAlpha(NeonTheme.AMBER, 230)
                : (supported ? NeonTheme.TEXT_SECONDARY : NeonTheme.withAlpha(NeonTheme.CRIMSON, 215));
        graphics.text(font, sub, titleX, y + 19, subColor, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
