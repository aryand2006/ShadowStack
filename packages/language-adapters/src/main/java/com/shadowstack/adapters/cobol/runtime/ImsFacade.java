package com.shadowstack.adapters.cobol.runtime;

/**
 * IMS DL/I–shaped data façade (roadmap Phase 4). Supported subset only;
 * everything else fails closed.
 */
public interface ImsFacade {

    default byte[] gu(String pcb, byte[] ssa) {
        throw new UnsupportedCobolFeatureException("IMS GU on " + pcb);
    }

    default byte[] gn(String pcb, byte[] ssa) {
        throw new UnsupportedCobolFeatureException("IMS GN on " + pcb);
    }

    default void isrt(String pcb, byte[] segment) {
        throw new UnsupportedCobolFeatureException("IMS ISRT on " + pcb);
    }

    default void repl(String pcb, byte[] segment) {
        throw new UnsupportedCobolFeatureException("IMS REPL on " + pcb);
    }

    default void dlet(String pcb) {
        throw new UnsupportedCobolFeatureException("IMS DLET on " + pcb);
    }
}
