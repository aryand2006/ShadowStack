package com.shadowstack.adapters.cobol.runtime;

/**
 * File / batch I/O façade for COBOL→Java rehost (roadmap Phase 2).
 * Not a VSAM clone — supported ops succeed; unsupported ops throw
 * {@link UnsupportedCobolFeatureException} (fail-closed).
 */
public interface CobolFileFacade {

    enum Organization { SEQUENTIAL, INDEXED, RELATIVE, UNKNOWN }

    void openInput(String ddName);

    void openOutput(String ddName);

    void openExtend(String ddName);

    /** @return false on end-of-file */
    boolean read(String ddName, byte[] recordBuffer);

    void write(String ddName, byte[] record);

    void rewrite(String ddName, byte[] record);

    void close(String ddName);

    Organization organization(String ddName);
}
