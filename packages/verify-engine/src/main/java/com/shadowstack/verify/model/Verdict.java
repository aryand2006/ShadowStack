package com.shadowstack.verify.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Overall verification verdict for a patch unit.
 *
 * <ul>
 *   <li>{@link #PASS} — All verification layers passed; patch is safe to apply</li>
 *   <li>{@link #WARN} — Some layers raised concerns but no hard failures; human review recommended</li>
 *   <li>{@link #FAIL} — One or more critical verification layers failed; patch must not be applied</li>
 * </ul>
 */
public enum Verdict {
    PASS("pass"),
    WARN("warn"),
    FAIL("fail");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    /**
     * Returns the more severe of two verdicts.
     */
    public static Verdict worst(Verdict a, Verdict b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
