package com.kynxhel.peerpulsar.client.gui.screen;

import com.kynxhel.peerpulsar.client.gui.fx.NeonRenderUtils;
import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.client.gui.fx.ParticleBackground;
import com.kynxhel.peerpulsar.client.gui.widget.NeonButton;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.security.ClientIdentity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown when {@code identity.json} is missing or corrupted after prior initialization,
 * preventing silent key regeneration that would lock the player out of existing worlds.
 */
public class IdentityRecoveryScreen extends Screen {
    private final Screen parent;
    private final ClientIdentity.Status status;
    private final Runnable onProceedAfterCreate;
    private final ParticleBackground particleBg = new ParticleBackground();

    public IdentityRecoveryScreen(Screen parent, ClientIdentity.Status status, Runnable onProceedAfterCreate) {
        super(Component.translatable("screen.peerpulsar.identity.recovery_title"));
        this.parent = parent;
        this.status = status;
        this.onProceedAfterCreate = onProceedAfterCreate;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int panelW = Math.min(340, this.width - 24);
        int panelLeft = cx - panelW / 2;
        int btnY = cy + 28;
        int btnW = (panelW - 12) / 2;

        this.addRenderableWidget(new NeonButton(
                panelLeft, btnY, btnW, 22,
                Component.translatable("screen.peerpulsar.identity.btn_import"),
                NeonTheme.CYAN,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new P2PAdvancedScreen(this.parent, P2PAdvancedScreen.Tab.IDENTITY));
                    }
                }
        ));

        this.addRenderableWidget(new NeonButton(
                panelLeft + btnW + 12, btnY, btnW, 22,
                Component.translatable("screen.peerpulsar.identity.btn_create_new"),
                NeonTheme.AMBER,
                () -> {
                    ClientIdentity.recreateAndReplace();
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(this.parent);
                    }
                    if (this.onProceedAfterCreate != null) {
                        this.onProceedAfterCreate.run();
                    }
                }
        ));

        this.addRenderableWidget(new NeonButton(
                cx - 65, btnY + 30, 130, 20,
                Component.translatable("gui.cancel"),
                NeonTheme.SLATE,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(this.parent);
                    }
                }
        ));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.blurBeforeThisStratum();
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, P2PNetworkManager.getInstance().isP2PActive());

        int cx = this.width / 2;
        int cy = this.height / 2;
        int panelW = Math.min(340, this.width - 24);
        int panelLeft = cx - panelW / 2;
        int cardY = cy - 68;

        NeonRenderUtils.drawGlassCard(graphics, panelLeft, cardY, panelW, 88, NeonTheme.AMBER, true, NeonTheme.nowSec());
        graphics.centeredText(this.font,
                Component.translatable("screen.peerpulsar.identity.recovery_title").withStyle(ChatFormatting.BOLD),
                cx, cardY + 12, NeonTheme.withAlpha(NeonTheme.AMBER, 255));

        String bodyKey = status == ClientIdentity.Status.CORRUPT
                ? "screen.peerpulsar.identity.recovery_corrupt"
                : "screen.peerpulsar.identity.recovery_missing";
        var lines = this.font.split(Component.translatable(bodyKey), panelW - 24);
        int ly = cardY + 30;
        for (var line : lines) {
            graphics.text(this.font, line, panelLeft + 12, ly, NeonTheme.TEXT_PRIMARY, false);
            ly += 11;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
