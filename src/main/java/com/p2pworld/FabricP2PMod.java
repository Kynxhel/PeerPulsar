package com.p2pworld;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FabricP2PMod implements ModInitializer {
    public static final String MOD_ID = "fabricp2p";
    public static final String MOD_NAME = "Fabric P2P World";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[FabricP2P] Initializing {} v2.0.0 (Neon Edition - MC 26.3)", MOD_NAME);
    }
}
