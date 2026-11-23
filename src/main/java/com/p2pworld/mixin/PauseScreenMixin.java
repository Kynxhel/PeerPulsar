package com.p2pworld.mixin;

import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.screen.P2PHostScreen;
import com.p2pworld.client.gui.widget.NeonButton;
import com.p2pworld.client.network.P2PNetworkManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
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
        Component p2pText = isP2PActive
                ? Component.translatable("menu.fabricp2p.p2p_active").withStyle(ChatFormatting.BOLD)
                : Component.literal("✦ ").append(Component.translatable("menu.fabricp2p.start_p2p")).withStyle(ChatFormatting.BOLD);
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
        this.addRenderableWidget(p2pButton);
    }
}
