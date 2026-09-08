package com.shadowstack.adapters.cobol.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory BMS map façade for host embedding. Stores last sent map data
 * by map name; not bit-identical IBM BMS / 3270 semantics.
 */
public final class InMemoryBmsFacade implements BmsFacade {

    private final Map<String, String> maps = new ConcurrentHashMap<>();

    @Override
    public void sendMap(String map, String data) {
        if (map == null || map.isBlank()) {
            throw new UnsupportedCobolFeatureException("BMS SEND MAP with empty map name");
        }
        maps.put(map, data == null ? "" : data);
    }

    @Override
    public String receiveMap(String map) {
        if (map == null || map.isBlank()) {
            throw new UnsupportedCobolFeatureException("BMS RECEIVE MAP with empty map name");
        }
        return maps.getOrDefault(map, "");
    }
}
