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

        AbstractWidget lanButton = null;
        Component shareToLanText = Component.translatable("menu.shareToLan");

        for (var child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                String msg = widget.getMessage().getString();
                if (msg.equals(shareToLanText.getString())
                        || msg.equalsIgnoreCase("Open to LAN")
                        || msg.equalsIgnoreCase("Yerel Ağda Paylaş")) {
                    lanButton = widget;
                    break;
                }
            }
        }

        boolean isP2PActive = P2PNetworkManager.getInstance().isP2PActive();
        Component p2pText = isP2PActive
                ? Component.translatable("menu.fabricp2p.p2p_active").withStyle(ChatFormatting.BOLD)
                : Component.literal("✦ ").append(Component.translatable("menu.fabricp2p.start_p2p")).withStyle(ChatFormatting.BOLD);
        int accent = isP2PActive ? NeonTheme.EMERALD : NeonTheme.CYAN;

        if (lanButton != null) {
            int originalX = lanButton.getX();
            int originalY = lanButton.getY();
            lanButton.setWidth(98);

            NeonButton p2pButton = new NeonButton(
                    originalX + 104, originalY, 100, 20,
                    p2pText, accent,
                    () -> this.minecraft.setScreenAndShow(new P2PHostScreen(this))
            ).setPulsingBeacon(isP2PActive);

            this.addRenderableWidget(p2pButton);
        } else {
            int centerX = this.width / 2;
            NeonButton p2pButton = new NeonButton(
                    centerX - 102, 10, 204, 20,
                    p2pText, accent,
                    () -> this.minecraft.setScreenAndShow(new P2PHostScreen(this))
            ).setPulsingBeacon(isP2PActive);

            this.addRenderableWidget(p2pButton);
        }
    }
}
