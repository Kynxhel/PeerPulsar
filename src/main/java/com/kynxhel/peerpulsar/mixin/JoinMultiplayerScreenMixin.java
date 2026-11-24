package com.kynxhel.peerpulsar.mixin;

import com.kynxhel.peerpulsar.client.gui.fx.NeonTheme;
import com.kynxhel.peerpulsar.client.gui.screen.P2PJoinScreen;
import com.kynxhel.peerpulsar.client.gui.widget.NeonButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (this.minecraft == null) return;

        int btnWidth = 116;
        int btnHeight = 20;
        int x = this.width - btnWidth - 10;
        int y = 8;

        NeonButton joinP2PButton = new NeonButton(
                x, y, btnWidth, btnHeight,
                Component.literal("✦ ").append(Component.translatable("menu.peerpulsar.join_p2p")).withStyle(ChatFormatting.BOLD),
                NeonTheme.CYAN,
                () -> this.minecraft.setScreenAndShow(new P2PJoinScreen(this))
        );

        this.addRenderableWidget(joinP2PButton);
    }
}
