package com.p2pworld.client.gui.fx;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Interactive real-time P2P Mesh Constellation & Particle Burst engine.
 * Renders drifting glowing network nodes, dynamic peer connection beams,
 * traveling data packets, subtle cyber-grid lines, and burst sparks.
 */
public class ParticleBackground {
    private static final int NODE_COUNT = 42;
    private static final float LINK_DISTANCE = 115.0f;
    private static final float MOUSE_RADIUS = 130.0f;

    private final List<MeshNode> nodes = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final Random random = new Random();

    private int lastWidth = -1;
    private int lastHeight = -1;
    private long lastUpdateNanos = System.nanoTime();

    private static final int[] NODE_PALETTE = {
            NeonTheme.CYAN,
            NeonTheme.VIOLET,
            NeonTheme.EMERALD,
            0x38BDF8
    };

    public void ensureInitialized(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (nodes.isEmpty() || Math.abs(width - lastWidth) > 120 || Math.abs(height - lastHeight) > 120) {
            nodes.clear();
            for (int i = 0; i < NODE_COUNT; i++) {
                float x = random.nextFloat() * width;
                float y = random.nextFloat() * height;
                float vx = (random.nextFloat() - 0.5f) * 26.0f;
                float vy = (random.nextFloat() - 0.5f) * 26.0f;
                int color = NODE_PALETTE[random.nextInt(NODE_PALETTE.length)];
                float radius = 1.2f + random.nextFloat() * 1.4f;
                float phase = random.nextFloat() * 6.283f;
                nodes.add(new MeshNode(x, y, vx, vy, color, radius, phase));
            }
            lastWidth = width;
            lastHeight = height;
        }
    }

    public void spawnBurst(float x, float y, int rgbColor, int count) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            float speed = 45.0f + random.nextFloat() * 145.0f;
            float vx = (float) Math.cos(angle) * speed;
            float vy = (float) Math.sin(angle) * speed - 25.0f;
            float life = 0.45f + random.nextFloat() * 0.55f;
            int size = random.nextFloat() < 0.35f ? 3 : 2;
            sparks.add(new Spark(x, y, vx, vy, rgbColor, life, size));
        }
    }

    public void render(GuiGraphicsExtractor graphics, int width, int height, int mouseX, int mouseY, boolean liveMode) {
        ensureInitialized(width, height);

        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastUpdateNanos) / 1_000_000_000.0f);
        lastUpdateNanos = now;
        double timeSec = now / 1_000_000_000.0;

        // 1. Deep obsidian gradient backdrop
        graphics.fillGradient(0, 0, width, height, NeonTheme.BG_TOP, NeonTheme.BG_BOTTOM);

        // 2. Ambient top/bottom neon aura bands
        int topGlowColor = liveMode ? NeonTheme.EMERALD : NeonTheme.CYAN;
        int bottomGlowColor = liveMode ? NeonTheme.CYAN : NeonTheme.VIOLET;
        float auraPulse = 0.75f + 0.25f * NeonTheme.pulse(timeSec, 1.8);
        graphics.fillGradient(0, 0, width, Math.min(110, height / 3),
                NeonTheme.withAlpha(topGlowColor, Math.round(32 * auraPulse)),
                NeonTheme.withAlpha(topGlowColor, 0));
        graphics.fillGradient(0, height - Math.min(110, height / 3), width, height,
                NeonTheme.withAlpha(bottomGlowColor, 0),
                NeonTheme.withAlpha(bottomGlowColor, Math.round(28 * auraPulse)));

        // 3. Subtle scrolling cyber-grid
        int gridSpacing = 36;
        int offsetX = (int) ((timeSec * 8.0) % gridSpacing);
        int offsetY = (int) ((timeSec * 5.0) % gridSpacing);
        int gridColor = NeonTheme.withAlpha(liveMode ? NeonTheme.EMERALD : NeonTheme.CYAN, 11);
        for (int gx = offsetX; gx < width; gx += gridSpacing) {
            graphics.verticalLine(gx, 0, height, gridColor);
        }
        for (int gy = offsetY; gy < height; gy += gridSpacing) {
            graphics.horizontalLine(0, width, gy, gridColor);
        }

        // 4. Update mesh nodes with soft mouse interaction
        for (MeshNode n : nodes) {
            n.x += n.vx * dt;
            n.y += n.vy * dt;

            // Gentle mouse repulsion/swirl
            float dx = n.x - mouseX;
            float dy = n.y - mouseY;
            float distSq = dx * dx + dy * dy;
            if (distSq > 4.0f && distSq < MOUSE_RADIUS * MOUSE_RADIUS) {
                float dist = (float) Math.sqrt(distSq);
                float force = (1.0f - dist / MOUSE_RADIUS) * 42.0f * dt;
                n.x += (dx / dist) * force;
                n.y += (dy / dist) * force;
            }

            // Wrap seamlessly
            if (n.x < -12) n.x = width + 12;
            if (n.x > width + 12) n.x = -12;
            if (n.y < -12) n.y = height + 12;
            if (n.y > height + 12) n.y = -12;
        }

        // 5. Render P2P mesh links (fast stepped dotted/segment beams)
        float maxDistSq = LINK_DISTANCE * LINK_DISTANCE;
        for (int i = 0; i < nodes.size(); i++) {
            MeshNode a = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                MeshNode b = nodes.get(j);
                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float dSq = dx * dx + dy * dy;
                if (dSq < maxDistSq) {
                    float dist = (float) Math.sqrt(dSq);
                    float strength = 1.0f - (dist / LINK_DISTANCE);
                    int alpha = Math.round(strength * strength * (liveMode ? 82 : 58));
                    if (alpha > 4) {
                        int linkRgb = liveMode ? NeonTheme.EMERALD : NeonTheme.lerpRgb(a.color, b.color, 0.5f);
                        drawBeam(graphics, a.x, a.y, b.x, b.y, NeonTheme.withAlpha(linkRgb, alpha));

                        // Traveling data packet dot on stronger links
                        if (strength > 0.42f && ((i + j) % 3 == 0)) {
                            float t = (float) ((timeSec * 0.65 + (i * 0.17 + j * 0.11)) % 1.0);
                            int px = Math.round(a.x + dx * t);
                            int py = Math.round(a.y + dy * t);
                            graphics.fill(px - 1, py - 1, px + 1, py + 1, NeonTheme.withAlpha(NeonTheme.TEXT_PRIMARY, Math.round(strength * 170)));
                        }
                    }
                }
            }
        }

        // 6. Render glowing nodes
        for (MeshNode n : nodes) {
            float p = 0.65f + 0.35f * (float) Math.sin(timeSec * 2.6 + n.phase);
            int nx = Math.round(n.x);
            int ny = Math.round(n.y);
            int nodeRgb = liveMode && (n.phase > 3.14f) ? NeonTheme.EMERALD : n.color;

            // Outer soft halo
            graphics.fill(nx - 3, ny - 3, nx + 3, ny + 3, NeonTheme.withAlpha(nodeRgb, Math.round(28 * p)));
            // Mid glow
            graphics.fill(nx - 2, ny - 2, nx + 2, ny + 2, NeonTheme.withAlpha(nodeRgb, Math.round(90 * p)));
            // Bright core
            graphics.fill(nx - 1, ny - 1, nx + 1, ny + 1, NeonTheme.withAlpha(0xFFFFFF, Math.round(210 * p)));
        }

        // 7. Update & render burst sparks
        renderSparks(graphics, dt);
    }

    public void renderSparksOnly(GuiGraphicsExtractor graphics) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastUpdateNanos) / 1_000_000_000.0f);
        renderSparks(graphics, dt);
    }

    private void renderSparks(GuiGraphicsExtractor graphics, float dt) {
        Iterator<Spark> it = sparks.iterator();
        while (it.hasNext()) {
            Spark s = it.next();
            s.age += dt;
            if (s.age >= s.maxLife) {
                it.remove();
                continue;
            }
            s.vy += 170.0f * dt; // soft gravity
            s.vx *= (1.0f - 1.8f * dt); // air drag
            s.x += s.vx * dt;
            s.y += s.vy * dt;

            float lifeRatio = 1.0f - (s.age / s.maxLife);
            int sx = Math.round(s.x);
            int sy = Math.round(s.y);
            int r = s.size;

            graphics.fill(sx - r - 1, sy - r - 1, sx + r + 1, sy + r + 1,
                    NeonTheme.withAlpha(s.color, Math.round(lifeRatio * 75)));
            graphics.fill(sx - r + 1, sy - r + 1, sx + r, sy + r,
                    NeonTheme.withAlpha(0xFFFFFF, Math.round(lifeRatio * 240)));
        }
    }

    /**
     * Draws a crisp anti-aliased-feel dotted/stepped beam between two points using GuiGraphicsExtractor fills.
     */
    private void drawBeam(GuiGraphicsExtractor graphics, float x1, float y1, float x2, float y2, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        int steps = Math.max(4, Math.min(28, (int) (Math.hypot(dx, dy) / 5.0)));
        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps;
            int px = Math.round(x1 + dx * t);
            int py = Math.round(y1 + dy * t);
            graphics.fill(px, py, px + 1, py + 1, argb);
        }
    }

    private static class MeshNode {
        float x, y, vx, vy;
        final int color;
        final float radius;
        final float phase;

        MeshNode(float x, float y, float vx, float vy, int color, float radius, float phase) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.color = color;
            this.radius = radius;
            this.phase = phase;
        }
    }

    private static class Spark {
        float x, y, vx, vy;
        final int color;
        final float maxLife;
        final int size;
        float age = 0.0f;

        Spark(float x, float y, float vx, float vy, int color, float maxLife, int size) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.color = color;
            this.maxLife = maxLife;
            this.size = size;
        }
    }
}
