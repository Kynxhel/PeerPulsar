package com.kynxhel.peerpulsar;

import com.kynxhel.peerpulsar.security.OfflineAuth;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeerPulsar implements ModInitializer {
    public static final String MOD_ID = "peerpulsar";
    public static final String MOD_NAME = "PeerPulsar";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[PeerPulsar] Initializing {} (MC 26.3)", MOD_NAME);
        OfflineAuth.registerServerSide();
    }
}
