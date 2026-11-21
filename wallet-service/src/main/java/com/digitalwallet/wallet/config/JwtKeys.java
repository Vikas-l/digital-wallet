package com.digitalwallet.wallet.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the RSA key pair that signs JWTs (RS256). The private key never leaves this
 * service; other services only get the public half from {@code /.well-known/jwks.json}.
 * The key id ({@code kid}) is the key's RFC 7638 thumbprint, so a rotated key gets a new
 * id and verifiers fetch it automatically.
 */
final class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private JwtKeys() {
    }

    static RSAKey load(String privateKeyPem) {
        try {
            if (privateKeyPem == null || privateKeyPem.isBlank()) {
                log.warn("No JWT_PRIVATE_KEY configured: generated a temporary RSA key. Tokens become invalid "
                        + "when this service restarts. Configure a key outside local development.");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                return build((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privateKeyPem)));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return build(publicKey, privateKey);
        } catch (GeneralSecurityException | JOSEException | ClassCastException | IllegalArgumentException e) {
            throw new IllegalStateException("JWT_PRIVATE_KEY must be an RSA private key in PKCS#8 PEM format "
                    + "(-----BEGIN PRIVATE KEY-----)", e);
        }
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) throws JOSEException {
        if (publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("RSA key must be at least 2048 bits");
        }
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint()
                .build();
    }

    /** Accepts real newlines or "\n" escapes (common when a PEM is put in an environment variable). */
    static byte[] decodePem(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
