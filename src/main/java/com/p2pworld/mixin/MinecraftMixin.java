package com.p2pworld.mixin;

import com.p2pworld.client.gui.screen.ExitSyncScreen;
import com.p2pworld.client.shared.SharedWorldConfig;
import com.p2pworld.client.shared.SharedWorldManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts "Save and Quit to Title" from the Pause Menu when playing a Shared Cloud World
 * with Auto-Sync on Exit enabled, uploading changed world files first before disconnecting.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "disconnectFromWorld", at = @At("HEAD"), cancellable = true)
    private void onDisconnectFromWorld(Component reason, CallbackInfo ci) {
        if (ExitSyncScreen.isBypassingDisconnectHook()) {
            return;
        }
        Minecraft self = (Minecraft) (Object) this;
        if (self.gui == null || !(self.gui.screen() instanceof PauseScreen)) {
            return;
        }
        IntegratedServer server = self.getSingleplayerServer();
        if (!self.hasSingleplayerServer() || server == null || !server.isRunning()) {
            return;
        }
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        SharedWorldManager mgr = SharedWorldManager.getInstance();
        if (!cfg.isEnabled() || !cfg.isAutoSyncOnExit()) {
            return;
        }
        if (!mgr.isUsingGoogleDrive() && !cfg.hasValidSyncFolder()) {
            return;
        }
        if (!mgr.isCurrentWorldShared(server)) {
            return;
        }

        self.setScreenAndShow(new ExitSyncScreen(reason));
        ci.cancel();
    }
}
