package com.shadowstack.adapters.cobol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CobolFailOnGapsResolveTest {

    @AfterEach
    void clearProp() {
        System.clearProperty("shadowstack.cobol.fail-on-gaps");
    }

    @Test
    void sysprop_true_wins() {
        System.setProperty("shadowstack.cobol.fail-on-gaps", "true");
        assertTrue(CobolAdapter.resolveFailOnGaps());
    }

    @Test
    void sysprop_false() {
        System.setProperty("shadowstack.cobol.fail-on-gaps", "false");
        assertFalse(CobolAdapter.resolveFailOnGaps());
    }

    @Test
    void default_false_when_unset() {
        System.clearProperty("shadowstack.cobol.fail-on-gaps");
        assertFalse(CobolAdapter.resolveFailOnGaps());
    }
}
