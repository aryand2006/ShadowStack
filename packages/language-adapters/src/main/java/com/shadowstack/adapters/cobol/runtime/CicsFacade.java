package com.shadowstack.adapters.cobol.runtime;

/**
 * CICS-class transaction façade (roadmap Phase 3).
 * Default methods fail-closed; embedders override supported verbs.
 */
public interface CicsFacade {

    default void link(String program) {
        throw new UnsupportedCobolFeatureException("CICS LINK " + program);
    }

    default void xctl(String program) {
        throw new UnsupportedCobolFeatureException("CICS XCTL " + program);
    }

    default void returnTransid(String transId) {
        throw new UnsupportedCobolFeatureException("CICS RETURN TRANSID(" + transId + ")");
    }

    default void writeQTs(String queue, byte[] data) {
        throw new UnsupportedCobolFeatureException("CICS WRITEQ TS " + queue);
    }

    default byte[] readQTs(String queue) {
        throw new UnsupportedCobolFeatureException("CICS READQ TS " + queue);
    }

    default void syncpoint() {
        throw new UnsupportedCobolFeatureException("CICS SYNCPOINT");
    }

    default void rollback() {
        throw new UnsupportedCobolFeatureException("CICS SYNCPOINT ROLLBACK");
    }
}
