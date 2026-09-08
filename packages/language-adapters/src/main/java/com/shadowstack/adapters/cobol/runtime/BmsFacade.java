package com.shadowstack.adapters.cobol.runtime;

/**
 * BMS / 3270 map façade (roadmap stretch). Default methods fail-closed;
 * embedders override supported map send/receive. Not bit-identical IBM BMS.
 */
public interface BmsFacade {

    default void sendMap(String map, String data) {
        throw new UnsupportedCobolFeatureException("BMS SEND MAP " + map);
    }

    default String receiveMap(String map) {
        throw new UnsupportedCobolFeatureException("BMS RECEIVE MAP " + map);
    }
}
