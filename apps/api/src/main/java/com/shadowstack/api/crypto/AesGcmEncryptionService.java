package com.shadowstack.api.crypto;

import com.shadowstack.api.config.ShadowStackConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for patch artifacts. Enabled when
 * {@code ENCRYPTION_KEY_BASE64} / {@code shadowstack.security.encryption-key-base64}
 * provides a 32-byte key. Ciphertext format: {@code v1:} + Base64(IV || ciphertext+tag).
 */
@Service
public class AesGcmEncryptionService implements EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(AesGcmEncryptionService.class);

    static final String VERSION_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final String configKeyBase64;
    private final String envKeyBase64;

    private SecretKey secretKey;
    private boolean enabled;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEncryptionService(
            ShadowStackConfig config,
            @Value("${ENCRYPTION_KEY_BASE64:}") String envKeyBase64) {
        String fromConfig = config.security() != null ? config.security().encryptionKeyBase64() : null;
        this.configKeyBase64 = fromConfig;
        this.envKeyBase64 = envKeyBase64;
    }

    @PostConstruct
    void init() {
        String raw = firstNonBlank(envKeyBase64, configKeyBase64);
        if (raw == null) {
            this.enabled = false;
            this.secretKey = null;
            log.info("EncryptionService: disabled (no ENCRYPTION_KEY_BASE64); patch artifacts stored as plaintext");
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ENCRYPTION_KEY_BASE64 / encryption-key-base64 is not valid Base64", e);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "Encryption key must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes (AES-256); got " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.enabled = true;
        log.info("EncryptionService: AES-GCM enabled for patch artifacts (unifiedDiff, verificationJson, reviewJson)");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String encrypt(String plaintext) {
        if (!enabled || plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (plaintext.startsWith(VERSION_PREFIX)) {
            // Already encrypted — avoid double-encryption on update paths
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt patch artifact", e);
        }
    }

    @Override
    public String decrypt(String ciphertextOrPlaintext) {
        if (!enabled || ciphertextOrPlaintext == null || ciphertextOrPlaintext.isBlank()) {
            return ciphertextOrPlaintext;
        }
        if (!ciphertextOrPlaintext.startsWith(VERSION_PREFIX)) {
            // Legacy plaintext row written before encryption was enabled
            return ciphertextOrPlaintext;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertextOrPlaintext.substring(VERSION_PREFIX.length()));
            if (combined.length < GCM_IV_LENGTH + 1) {
                throw new IllegalStateException("Ciphertext too short");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt patch artifact", e);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
