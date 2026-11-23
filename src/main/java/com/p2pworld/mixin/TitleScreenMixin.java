package com.p2pworld.mixin;

import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.screen.P2PJoinScreen;
import com.p2pworld.client.gui.widget.NeonButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (this.minecraft == null) return;

        int btnWidth = 112;
        int btnHeight = 20;
        int x = this.width / 2 + 104;
        int y = this.height / 4 + 48 + 24;

        if (x + btnWidth > this.width - 4) {
            int available = this.width - x - 4;
            if (available >= 88) {
                btnWidth = Math.max(88, available);
            } else {
                btnWidth = 104;
                x = Math.max(4, this.width - btnWidth - 6);
                y = 6;
            }
        }

        NeonButton joinP2PBtn = new NeonButton(
                x, y, btnWidth, btnHeight,
                Component.literal("✦ ").append(Component.translatable("menu.fabricp2p.join_p2p")).withStyle(ChatFormatting.BOLD),
                NeonTheme.CYAN,
                () -> this.minecraft.setScreenAndShow(new P2PJoinScreen(this))
        );

        this.addRenderableWidget(joinP2PBtn);
    }
}
