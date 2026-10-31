package com.packsync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
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
 * RSA-3072 / SHA256withRSA signatures. Lives entirely in java.base, so it works under
 * Forge's module layers on every Java 17 runtime. The private key never leaves the server.
 */
public final class Signing {
    private static final String ALG = "SHA256withRSA";

    private Signing() {}

    public static KeyPair loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        Path priv = dir.resolve("private.key");
        Path pub = dir.resolve("public.key");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (Files.isRegularFile(priv) && Files.isRegularFile(pub)) {
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(decode(Files.readString(priv))));
            PublicKey pu = decodePublic(Files.readString(pub));
            return new KeyPair(pu, pk);
        }
        Files.createDirectories(dir);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(3072);
        KeyPair kp = gen.generateKeyPair();
        IO.atomicWrite(priv, Base64.getEncoder().encode(kp.getPrivate().getEncoded()));
        IO.atomicWrite(pub, encodePublic(kp.getPublic()).getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows hosts: rely on folder permissions.
        }
        return kp;
    }

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String b64) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(b64)));
    }

    public static byte[] sign(PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature s = Signature.getInstance(ALG);
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    public static boolean verify(PublicKey key, byte[] data, byte[] sig) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static byte[] decode(String b64) {
        return Base64.getDecoder().decode(b64.replaceAll("\\s+", ""));
    }
}
