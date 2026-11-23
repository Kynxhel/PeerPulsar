package com.p2pworld.client.gui.fx;

import net.minecraft.resources.Identifier;

/**
 * Centralized Cyber-Obsidian & Neon color palette and smooth animation math utilities.
 */
public final class NeonTheme {
    private NeonTheme() {}

    public static final Identifier MOD_ICON = Identifier.fromNamespaceAndPath("fabricp2p", "icon.png");

    // Deep obsidian glass surfaces
    public static final int BG_TOP        = 0xCC060913;
    public static final int BG_BOTTOM     = 0xE60B0F1E;
    public static final int CARD_BG       = 0xC80D1224;
    public static final int CARD_BG_ALT   = 0xD8121930;
    public static final int CARD_BORDER   = 0x66405480;
    public static final int INPUT_BG      = 0xE0070B16;

    // Signature neon accents (RGB without alpha, use withAlpha)
    public static final int CYAN          = 0x00F0FF;
    public static final int VIOLET        = 0xA855F7;
    public static final int EMERALD       = 0x10B981;
    public static final int AMBER         = 0xF59E0B;
    public static final int CRIMSON       = 0xEF4444;
    public static final int SLATE         = 0x64748B;

    // Text colors (ARGB)
    public static final int TEXT_PRIMARY   = 0xFFF8FAFC;
    public static final int TEXT_SECONDARY = 0xFF94A3B8;
    public static final int TEXT_MUTED     = 0xFF64748B;

    public static int withAlpha(int rgb, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    public static int withAlpha(int rgb, float alpha01) {
        return withAlpha(rgb, Math.round(clamp01(alpha01) * 255.0f));
    }

    public static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp01(t);
    }

    public static int lerpColor(int argbA, int argbB, float t) {
        float c = clamp01(t);
        int aA = (argbA >>> 24) & 0xFF;
        int rA = (argbA >>> 16) & 0xFF;
        int gA = (argbA >>> 8) & 0xFF;
        int bA = argbA & 0xFF;

        int aB = (argbB >>> 24) & 0xFF;
        int rB = (argbB >>> 16) & 0xFF;
        int gB = (argbB >>> 8) & 0xFF;
        int bB = argbB & 0xFF;

        int a = Math.round(aA + (aB - aA) * c);
        int r = Math.round(rA + (rB - rA) * c);
        int g = Math.round(gA + (gB - gA) * c);
        int b = Math.round(bA + (bB - bA) * c);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int lerpRgb(int rgbA, int rgbB, float t) {
        return lerpColor(0xFF000000 | (rgbA & 0xFFFFFF), 0xFF000000 | (rgbB & 0xFFFFFF), t) & 0x00FFFFFF;
    }

    public static float easeOutCubic(float t) {
        float x = 1.0f - clamp01(t);
        return 1.0f - x * x * x;
    }

    public static float easeOutBack(float t) {
        float x = clamp01(t) - 1.0f;
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        return 1.0f + c3 * x * x * x + c1 * x * x;
    }

    public static float pulse(double timeSec, double speed) {
        return (float) (0.5 + 0.5 * Math.sin(timeSec * speed));
    }

    public static double nowSec() {
        return System.nanoTime() / 1_000_000_000.0;
    }
}
