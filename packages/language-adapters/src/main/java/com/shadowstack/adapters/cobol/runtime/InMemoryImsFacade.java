package com.shadowstack.adapters.cobol.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory IMS DL/I façade for host embedding / generated MVP helpers.
 */
public final class InMemoryImsFacade implements ImsFacade {

    private final Map<String, byte[]> current = new ConcurrentHashMap<>();

    @Override
    public byte[] gu(String pcb, byte[] ssa) {
        return current.getOrDefault(pcb, new byte[0]).clone();
    }

    @Override
    public byte[] gn(String pcb, byte[] ssa) {
        return gu(pcb, ssa);
    }

    @Override
    public void isrt(String pcb, byte[] segment) {
        current.put(pcb, segment == null ? new byte[0] : segment.clone());
    }

    @Override
    public void repl(String pcb, byte[] segment) {
        isrt(pcb, segment);
    }

    @Override
    public void dlet(String pcb) {
        current.remove(pcb);
    }
}
