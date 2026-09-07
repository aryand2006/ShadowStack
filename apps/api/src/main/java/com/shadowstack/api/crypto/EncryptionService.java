package com.shadowstack.api.crypto;

/**
 * Application-level encryption for sensitive patch artifacts at rest.
 * When no key is configured, implementations must passthrough plaintext (demo / local).
 */
public interface EncryptionService {

    /**
     * @return true when a valid encryption key is loaded and ciphertext will be produced
     */
    boolean isEnabled();

    /**
     * Encrypt plaintext to a version-prefixed ciphertext blob, or return plaintext when disabled.
     * Null/blank input is returned unchanged.
     */
    String encrypt(String plaintext);

    /**
     * Decrypt a version-prefixed ciphertext blob, or return the value unchanged when disabled
     * or when the value is legacy plaintext (no {@code v1:} prefix).
     */
    String decrypt(String ciphertextOrPlaintext);
}
