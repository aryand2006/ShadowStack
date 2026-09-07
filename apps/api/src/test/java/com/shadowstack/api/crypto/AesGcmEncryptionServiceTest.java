package com.shadowstack.api.crypto;

import com.shadowstack.api.config.ShadowStackConfig;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmEncryptionServiceTest {

    @Test
    void disabledWhenKeyUnset_passthrough() {
        AesGcmEncryptionService svc = newService(null, "");
        svc.init();
        assertFalse(svc.isEnabled());
        assertEquals("plain-diff", svc.encrypt("plain-diff"));
        assertEquals("plain-diff", svc.decrypt("plain-diff"));
    }

    @Test
    void roundTripWithV1Prefix() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        AesGcmEncryptionService svc = newService(key, "");
        svc.init();
        assertTrue(svc.isEnabled());

        String cipher = svc.encrypt("unified-diff-body");
        assertTrue(cipher.startsWith(AesGcmEncryptionService.VERSION_PREFIX));
        assertNotEquals("unified-diff-body", cipher);
        assertEquals("unified-diff-body", svc.decrypt(cipher));
    }

    @Test
    void decryptLeavesLegacyPlaintextUnchanged() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        AesGcmEncryptionService svc = newService(null, key);
        svc.init();
        assertEquals("{\"ok\":true}", svc.decrypt("{\"ok\":true}"));
    }

    private static AesGcmEncryptionService newService(String configKey, String envKey) {
        ShadowStackConfig config = new ShadowStackConfig(
                null,
                null,
                new ShadowStackConfig.SecurityProperties("x".repeat(32), 86400000L, "*", configKey),
                null
        );
        return new AesGcmEncryptionService(config, envKey);
    }
}
