package com.p2pworld.mixin;

import com.p2pworld.server.PlayerIdentityGuard;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChangeGameModePacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetGameRulePacket;
import net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks item drops, inventory manipulation, chat, movement, and state-mutating packets
 * while a bound player is still pending Google OIDC verification.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Unique
    private boolean fabricp2p$isPending() {
        return this.player != null && PlayerIdentityGuard.isPendingVerification(this.player.getUUID());
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void onHandlePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void onHandleContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void onHandleContainerButtonClick(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerSlotStateChanged", at = @At("HEAD"), cancellable = true)
    private void onHandleContainerSlotStateChanged(ServerboundContainerSlotStateChangedPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void onHandleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void onHandlePickItemFromBlock(ServerboundPickItemFromBlockPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
    private void onHandlePickItemFromEntity(ServerboundPickItemFromEntityPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void onHandleChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void onHandleSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void onHandleMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void onHandleMoveVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
    private void onHandlePlayerCommand(ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAbilities", at = @At("HEAD"), cancellable = true)
    private void onHandlePlayerAbilities(ServerboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void onHandleEditBook(ServerboundEditBookPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void onHandleSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void onHandlePlaceRecipe(ServerboundPlaceRecipePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
    private void onHandleRenameItem(ServerboundRenameItemPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void onHandleSelectTrade(ServerboundSelectTradePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetBeaconPacket", at = @At("HEAD"), cancellable = true)
    private void onHandleSetBeaconPacket(ServerboundSetBeaconPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void onHandleSetCarriedItem(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void onHandleClientCommand(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCommandBlock", at = @At("HEAD"), cancellable = true)
    private void onHandleSetCommandBlock(ServerboundSetCommandBlockPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCommandMinecart", at = @At("HEAD"), cancellable = true)
    private void onHandleSetCommandMinecart(ServerboundSetCommandMinecartPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetStructureBlock", at = @At("HEAD"), cancellable = true)
    private void onHandleSetStructureBlock(ServerboundSetStructureBlockPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetJigsawBlock", at = @At("HEAD"), cancellable = true)
    private void onHandleSetJigsawBlock(ServerboundSetJigsawBlockPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleJigsawGenerate", at = @At("HEAD"), cancellable = true)
    private void onHandleJigsawGenerate(ServerboundJigsawGeneratePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetTestBlock", at = @At("HEAD"), cancellable = true)
    private void onHandleSetTestBlock(ServerboundSetTestBlockPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTestInstanceBlockAction", at = @At("HEAD"), cancellable = true)
    private void onHandleTestInstanceBlockAction(ServerboundTestInstanceBlockActionPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetGameRule", at = @At("HEAD"), cancellable = true)
    private void onHandleSetGameRule(ServerboundSetGameRulePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChangeDifficulty", at = @At("HEAD"), cancellable = true)
    private void onHandleChangeDifficulty(ServerboundChangeDifficultyPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleLockDifficulty", at = @At("HEAD"), cancellable = true)
    private void onHandleLockDifficulty(ServerboundLockDifficultyPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChangeGameMode", at = @At("HEAD"), cancellable = true)
    private void onHandleChangeGameMode(ServerboundChangeGameModePacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSpectatorAction", at = @At("HEAD"), cancellable = true)
    private void onHandleSpectatorAction(ServerboundSpectatorActionPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTeleportToEntityPacket", at = @At("HEAD"), cancellable = true)
    private void onHandleTeleportToEntityPacket(ServerboundTeleportToEntityPacket packet, CallbackInfo ci) {
        if (fabricp2p$isPending()) {
            ci.cancel();
        }
    }
}
