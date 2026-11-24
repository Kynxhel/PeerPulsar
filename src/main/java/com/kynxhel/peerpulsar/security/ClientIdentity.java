package com.kynxhel.peerpulsar.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * A persistent, locally generated Ed25519 key pair that identifies this PeerPulsar install.
 * Used for Offline LAN Auth name locking, key-based player UUIDs, and Join Approval device identification.
 */
public final class ClientIdentity {
    private static final String ALGORITHM = "Ed25519";
    private static ClientIdentity instance;

    public enum Status {
        OK,
        NEEDS_FIRST_CREATE,
        MISSING_AFTER_INIT,
        CORRUPT
    }

    private final PublicKey publicKey;
    private final PrivateKey privateKey;

    private ClientIdentity(PublicKey publicKey, PrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public static Path defaultFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("peerpulsar").resolve("identity.json");
    }

    public static synchronized Status inspectStatus() {
        return inspectStatus(defaultFile(), PeerPulsarConfig.get().isIdentityInitialized());
    }

    public static Status inspectStatus(Path file, boolean previouslyInitialized) {
        if (!Files.exists(file)) {
            return previouslyInitialized ? Status.MISSING_AFTER_INIT : Status.NEEDS_FIRST_CREATE;
        }
        return tryLoad(file) != null ? Status.OK : Status.CORRUPT;
    }

    public static synchronized ClientIdentity get() {
        if (instance != null) {
            return instance;
        }
        Path file = defaultFile();
        ClientIdentity loaded = tryLoad(file);
        if (loaded != null) {
            instance = loaded;
            if (!PeerPulsarConfig.get().isIdentityInitialized()) {
                PeerPulsarConfig.get().setIdentityInitialized(true);
            }
            return instance;
        }
        Status status = inspectStatus(file, PeerPulsarConfig.get().isIdentityInitialized());
        if (status == Status.NEEDS_FIRST_CREATE) {
            instance = createAndSave(file);
            PeerPulsarConfig.get().setIdentityInitialized(true);
            return instance;
        }
        throw new IllegalStateException("PeerPulsar identity file is " + status + " at " + file);
    }

    /**
     * Loads existing valid identity or creates one on very first launch; returns null if corrupt or missing after init.
     */
    public static synchronized ClientIdentity getIfHealthyOrFirstTime() {
        Status status = inspectStatus();
        if (status == Status.OK || status == Status.NEEDS_FIRST_CREATE) {
            return get();
        }
        return null;
    }

    /**
     * Explicitly creates and replaces the identity file after user confirmation when corrupt/missing.
     */
    public static synchronized ClientIdentity recreateAndReplace() {
        Path file = defaultFile();
        backupCorruptFileIfPresent(file);
        instance = createAndSave(file);
        PeerPulsarConfig.get().setIdentityInitialized(true);
        return instance;
    }

    public static synchronized void exportTo(Path destination) throws Exception {
        exportFromFile(defaultFile(), destination);
    }

    public static void exportFromFile(Path sourceFile, Path destination) throws Exception {
        ClientIdentity id = tryLoad(sourceFile);
        if (id == null) {
            throw new IllegalStateException("No valid identity.json found to export.");
        }
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        Files.copy(sourceFile, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    public static synchronized ClientIdentity importFrom(Path source) throws Exception {
        ClientIdentity imported = importIntoFile(source, defaultFile());
        instance = imported;
        PeerPulsarConfig.get().setIdentityInitialized(true);
        return imported;
    }

    public static ClientIdentity importIntoFile(Path source, Path targetFile) throws Exception {
        ClientIdentity imported = tryLoad(source);
        if (imported == null) {
            throw new IllegalArgumentException("Selected file is not a valid PeerPulsar Ed25519 identity.json.");
        }
        backupCorruptFileIfPresent(targetFile);
        writeIdentityFile(targetFile, imported.publicKey, imported.privateKey);
        return imported;
    }

    public static ClientIdentity tryLoad(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!obj.has("public") || !obj.has("private")) {
                return null;
            }
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(obj.get("public").getAsString())));
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(obj.get("private").getAsString())));
            ClientIdentity id = new ClientIdentity(pub, priv);
            byte[] probe = "peerpulsar-self-check".getBytes(StandardCharsets.UTF_8);
            if (!verify(id.publicKeyEncoded(), probe, id.sign(probe))) {
                return null;
            }
            return id;
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar] Identity file invalid at {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static ClientIdentity createAndSave(Path file) {
        try {
            KeyPair kp = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            writeIdentityFile(file, kp.getPublic(), kp.getPrivate());
            return new ClientIdentity(kp.getPublic(), kp.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("Could not create PeerPulsar identity key", e);
        }
    }

    private static void writeIdentityFile(Path file, PublicKey pub, PrivateKey priv) throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("_comment", "PeerPulsar identity key. Keep this file private; losing it changes your offline world UUID.");
        obj.addProperty("public", Base64.getEncoder().encodeToString(pub.getEncoded()));
        obj.addProperty("private", Base64.getEncoder().encodeToString(priv.getEncoded()));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicEx) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void backupCorruptFileIfPresent(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                Path bak = file.resolveSibling(file.getFileName() + ".bak-" + System.currentTimeMillis());
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {}
    }

    public byte[] publicKeyEncoded() {
        return publicKey.getEncoded();
    }

    public String fingerprintHex() {
        return RoomKeys.hex(RoomKeys.sha256(publicKeyEncoded()));
    }

    public String shortFingerprint() {
        String hex = fingerprintHex();
        return hex.length() >= 8 ? hex.substring(0, 8) : hex;
    }

    public byte[] sign(byte[] data) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public static boolean verify(byte[] encodedPublicKey, byte[] data, byte[] signature) {
        try {
            PublicKey pub = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encodedPublicKey));
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(pub);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
