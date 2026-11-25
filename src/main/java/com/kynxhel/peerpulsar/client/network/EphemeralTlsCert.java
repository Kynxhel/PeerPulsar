package com.kynxhel.peerpulsar.client.network;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Generates an ephemeral in-memory self-signed ECDSA P-256 X.509 v3 certificate and KeyStore
 * for QUIC (Kwik) UDP transport, pinned by SHA-256 fingerprint over AES-256-GCM signaling.
 * Zero external certificate library dependencies required.
 */
public final class EphemeralTlsCert {
    private static final DateTimeFormatter UTC_TIME_FMT =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final char[] INTERNAL_PASSWORD = "peerpulsar-ephemeral".toCharArray();
    private static final String ALIAS = "peerpulsar";

    private final KeyStore keyStore;
    private final X509Certificate certificate;
    private final String sha256Hex;

    private EphemeralTlsCert(KeyStore keyStore, X509Certificate certificate, String sha256Hex) {
        this.keyStore = keyStore;
        this.certificate = certificate;
        this.sha256Hex = sha256Hex;
    }

    public KeyStore keyStore() {
        return keyStore;
    }

    public String alias() {
        return ALIAS;
    }

    public char[] password() {
        return INTERNAL_PASSWORD.clone();
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public String sha256Hex() {
        return sha256Hex;
    }

    public static EphemeralTlsCert generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        SecureRandom rng = new SecureRandom();
        BigInteger serial = new BigInteger(120, rng).add(BigInteger.ONE);

        // SHA256withECDSA OID 1.2.840.10045.4.3.2 (RFC 5758: no NULL parameters)
        byte[] sigAlgId = derSequence(derOid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02));

        // DN: CN=peerpulsar.local
        byte[] cnAttr = derSequence(
                derOid(0x55, 0x04, 0x03),
                derUtf8String("peerpulsar.local")
        );
        byte[] nameDer = derSequence(derSet(cnAttr));

        Instant now = Instant.now();
        byte[] notBefore = derUtcTime(now.minusSeconds(86_400L));
        byte[] notAfter = derUtcTime(now.plusSeconds(30L * 86_400L));
        byte[] validity = derSequence(notBefore, notAfter);

        byte[] spki = kp.getPublic().getEncoded();

        // Extensions: BasicConstraints(CA:FALSE), KeyUsage(digitalSignature), EKU(serverAuth), SAN(DNS:peerpulsar.local)
        byte[] extBasicConstraints = derSequence(
                derOid(0x55, 0x1D, 0x13),
                derBoolean(true),
                derOctetString(derSequence())
        );
        byte[] extKeyUsage = derSequence(
                derOid(0x55, 0x1D, 0x0F),
                derBoolean(true),
                derOctetString(new byte[]{0x03, 0x02, 0x07, (byte) 0x80})
        );
        byte[] extEku = derSequence(
                derOid(0x55, 0x1D, 0x25),
                derOctetString(derSequence(derOid(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01)))
        );
        byte[] sanDns = derTag(0x82, "peerpulsar.local".getBytes(StandardCharsets.US_ASCII));
        byte[] extSan = derSequence(
                derOid(0x55, 0x1D, 0x11),
                derOctetString(derSequence(sanDns))
        );
        byte[] extensions = derTag(0xA3, derSequence(extBasicConstraints, extKeyUsage, extEku, extSan));

        byte[] tbs = derSequence(
                derTag(0xA0, derInteger(BigInteger.TWO)),
                derInteger(serial),
                sigAlgId,
                nameDer,
                validity,
                nameDer,
                spki,
                extensions
        );

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(kp.getPrivate(), rng);
        signer.update(tbs);
        byte[] signatureBytes = signer.sign();

        byte[] certDer = derSequence(tbs, sigAlgId, derBitString(signatureBytes));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));

        String sha256 = sha256Hex(cert.getEncoded());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), INTERNAL_PASSWORD, new Certificate[]{cert});

        return new EphemeralTlsCert(ks, cert, sha256);
    }

    public static X509TrustManager pinnedTrustManager(String expectedSha256Hex) {
        if (expectedSha256Hex == null || !expectedSha256Hex.matches("^[0-9a-fA-F]{64}$")) {
            throw new IllegalArgumentException("Invalid expected certificate SHA-256 fingerprint");
        }
        final byte[] expectedDigest = HexFormat.of().parseHex(expectedSha256Hex.toLowerCase());
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not accepted");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0 || chain[0] == null) {
                    throw new CertificateException("Empty peer certificate chain");
                }
                byte[] actual;
                try {
                    actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                } catch (CertificateEncodingException e) {
                    throw new CertificateException("Failed to encode peer certificate", e);
                } catch (Exception e) {
                    throw new CertificateException("SHA-256 unavailable", e);
                }
                if (!MessageDigest.isEqual(expectedDigest, actual)) {
                    throw new CertificateException("QUIC peer certificate fingerprint mismatch");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        return HexFormat.of().formatHex(digest);
    }

    private static byte[] derSequence(byte[]... items) {
        return derTag(0x30, concat(items));
    }

    private static byte[] derSet(byte[]... items) {
        return derTag(0x31, concat(items));
    }

    private static byte[] derInteger(BigInteger value) {
        return derTag(0x02, value.toByteArray());
    }

    private static byte[] derBoolean(boolean value) {
        return derTag(0x01, new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    private static byte[] derOctetString(byte[] content) {
        return derTag(0x04, content);
    }

    private static byte[] derUtf8String(String s) {
        return derTag(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derUtcTime(Instant instant) {
        return derTag(0x17, UTC_TIME_FMT.format(instant).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derOid(int... encodedBytes) {
        byte[] content = new byte[encodedBytes.length];
        for (int i = 0; i < encodedBytes.length; i++) {
            content[i] = (byte) encodedBytes[i];
        }
        return derTag(0x06, content);
    }

    private static byte[] derBitString(byte[] content) {
        byte[] wrapped = new byte[content.length + 1];
        wrapped[0] = 0x00;
        System.arraycopy(content, 0, wrapped, 1, content.length);
        return derTag(0x03, wrapped);
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return Arrays.copyOf(out, pos);
    }
}
