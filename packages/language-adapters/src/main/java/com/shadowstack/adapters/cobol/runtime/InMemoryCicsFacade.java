package com.shadowstack.adapters.cobol.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CICS façade for host embedding (Phase 3).
 * LINK/XCTL reflectively invoke {@code TranslatedPROGRAM.main}; TS queues are heap maps.
 */
public final class InMemoryCicsFacade implements CicsFacade {

    private final Map<String, byte[]> tsQueues = new ConcurrentHashMap<>();
    private final ClassLoader classLoader;

    public InMemoryCicsFacade() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public InMemoryCicsFacade(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
    }

    @Override
    public void link(String program) {
        invokeTranslated(program);
    }

    @Override
    public void xctl(String program) {
        invokeTranslated(program);
    }

    @Override
    public void returnTransid(String transId) {
        // MVP: no-op (caller returns from paragraph).
    }

    @Override
    public void writeQTs(String queue, byte[] data) {
        tsQueues.put(queue, data == null ? new byte[0] : data.clone());
    }

    @Override
    public byte[] readQTs(String queue) {
        byte[] b = tsQueues.get(queue);
        return b == null ? new byte[0] : b.clone();
    }

    @Override
    public void syncpoint() {
        // no-op MVP
    }

    @Override
    public void rollback() {
        throw new UnsupportedCobolFeatureException("CICS SYNCPOINT ROLLBACK");
    }

    private void invokeTranslated(String program) {
        if (program == null || program.isBlank()) {
            throw new UnsupportedCobolFeatureException("CICS LINK/XCTL with empty PROGRAM");
        }
        String className = "Translated" + program.trim().toUpperCase().replace('-', '_');
        try {
            Class<?> c = Class.forName(className, true, classLoader);
            c.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedCobolFeatureException(
                    "CICS LINK/XCTL " + program + ": " + e.getMessage(), e);
        }
    }

    public void writeQTs(String queue, String data) {
        writeQTs(queue, data == null ? null : data.getBytes(StandardCharsets.UTF_8));
    }

    public String readQTsString(String queue) {
        return new String(readQTs(queue), StandardCharsets.UTF_8);
    }
}
