package com.kynxhel.peerpulsar.mixin;

import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.client.gui.screen.P2PHostScreen;
import com.kynxhel.peerpulsar.client.gui.widget.NeonButton;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private NeonButton peerpulsar$p2pButton;
    @Unique
    private boolean peerpulsar$lastP2PActive;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if (this.minecraft == null || !this.minecraft.hasSingleplayerServer()) return;

        AbstractWidget anchorButton = null;
        AbstractWidget bottomMostButton = null;
        String worldOptionsStr = Component.translatable("options.worldOptions.button").getString();
        String shareToLanStr = Component.translatable("menu.shareToLan").getString();

        for (var child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                if (bottomMostButton == null || widget.getY() > bottomMostButton.getY()) {
                    bottomMostButton = widget;
                }
                String msg = widget.getMessage().getString();
                if (msg.equals(worldOptionsStr)
                        || msg.equals(shareToLanStr)
                        || msg.equalsIgnoreCase("World Options...")
                        || msg.equalsIgnoreCase("Open to LAN")
                        || msg.equalsIgnoreCase("Yerel Ağda Paylaş")) {
                    anchorButton = widget;
                }
            }
        }

        boolean isP2PActive = P2PNetworkManager.getInstance().isP2PActive();
        this.peerpulsar$lastP2PActive = isP2PActive;
        Component p2pText = isP2PActive
                ? Component.translatable("menu.peerpulsar.p2p_active").withStyle(ChatFormatting.BOLD)
                : Component.literal("✦ ").append(Component.translatable("menu.peerpulsar.start_p2p")).withStyle(ChatFormatting.BOLD);
        int accent = isP2PActive ? NeonTheme.EMERALD : NeonTheme.CYAN;

        if (anchorButton != null) {
            int btnW = 98;
            int rightX = anchorButton.getX() + anchorButton.getWidth() + 6;
            if (rightX + btnW <= this.width - 4) {
                NeonButton p2pButton = new NeonButton(
                        rightX, anchorButton.getY(), btnW, anchorButton.getHeight(),
                        p2pText, accent,
                        () -> this.minecraft.setScreenAndShow(new P2PHostScreen(this))
                ).setPulsingBeacon(isP2PActive);
                this.peerpulsar$p2pButton = p2pButton;
                this.addRenderableWidget(p2pButton);
                return;
            }
        }

        int centerX = this.width / 2;
        int fallbackY = bottomMostButton != null
                ? Math.min(this.height - 26, bottomMostButton.getY() + bottomMostButton.getHeight() + 6)
                : Math.max(36, this.height - 28);
        NeonButton p2pButton = new NeonButton(
                centerX - 102, fallbackY, 204, 20,
                p2pText, accent,
                () -> this.minecraft.setScreenAndShow(new P2PHostScreen(this))
        ).setPulsingBeacon(isP2PActive);
        this.peerpulsar$p2pButton = p2pButton;
        this.addRenderableWidget(p2pButton);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        if (this.peerpulsar$p2pButton == null) return;
        boolean isP2PActive = P2PNetworkManager.getInstance().isP2PActive();
        if (isP2PActive != this.peerpulsar$lastP2PActive) {
            this.peerpulsar$lastP2PActive = isP2PActive;
            this.peerpulsar$p2pButton.setMessage(isP2PActive
                    ? Component.translatable("menu.peerpulsar.p2p_active").withStyle(ChatFormatting.BOLD)
                    : Component.literal("✦ ").append(Component.translatable("menu.peerpulsar.start_p2p")).withStyle(ChatFormatting.BOLD));
            this.peerpulsar$p2pButton.setAccentRgb(isP2PActive ? NeonTheme.EMERALD : NeonTheme.CYAN);
            this.peerpulsar$p2pButton.setPulsingBeacon(isP2PActive);
        }
    }
}
