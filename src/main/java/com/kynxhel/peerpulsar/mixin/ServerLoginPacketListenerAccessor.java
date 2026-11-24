package com.kynxhel.peerpulsar.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerAccessor {
    @Accessor("connection")
    Connection peerpulsar$getConnection();

    @Accessor("authenticatedProfile")
    GameProfile peerpulsar$getAuthenticatedProfile();

    @Accessor("authenticatedProfile")
    void peerpulsar$setAuthenticatedProfile(GameProfile profile);

    @Accessor("requestedUsername")
    String peerpulsar$getRequestedUsername();
}
