package com.shadowstack.adapters.cobol.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory INDEXED (VSAM KSDS–shaped) façade — key→record map persisted as
 * {@code dd.idxdat} lines {@code key\\tpayload}. Not bit-identical IBM VSAM.
 */
public final class IndexedInMemoryFileFacade implements CobolFileFacade {

    private final Path dataRoot;
    private final Map<String, IndexedHandle> open = new ConcurrentHashMap<>();

    public IndexedInMemoryFileFacade(Path dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
    }

    @Override
    public void openInput(String ddName) {
        open.put(norm(ddName), IndexedHandle.load(resolve(ddName), false));
    }

    @Override
    public void openOutput(String ddName) {
        open.put(norm(ddName), IndexedHandle.empty(resolve(ddName), true));
    }

    @Override
    public void openExtend(String ddName) {
        open.put(norm(ddName), IndexedHandle.load(resolve(ddName), true));
    }

    public void openIo(String ddName) {
        open.put(norm(ddName), IndexedHandle.load(resolve(ddName), true));
    }

    @Override
    public boolean read(String ddName, byte[] recordBuffer) {
        return require(ddName).readNext(recordBuffer);
    }

    public boolean readKey(String ddName, String key, byte[] recordBuffer) {
        return require(ddName).readKey(key, recordBuffer);
    }

    @Override
    public void write(String ddName, byte[] record) {
        require(ddName).write(keyOf(record), record);
    }

    public void writeKey(String ddName, String key, byte[] record) {
        require(ddName).write(key, record);
    }

    @Override
    public void rewrite(String ddName, byte[] record) {
        require(ddName).rewrite(record);
    }

    public void deleteKey(String ddName, String key) {
        require(ddName).delete(key);
    }

    public void startKey(String ddName, String key) {
        require(ddName).start(key);
    }

    @Override
    public void close(String ddName) {
        IndexedHandle h = open.remove(norm(ddName));
        if (h != null) {
            try {
                h.close();
            } catch (IOException e) {
                throw new UnsupportedCobolFeatureException("CLOSE " + ddName + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public Organization organization(String ddName) {
        return Organization.INDEXED;
    }

    private Path resolve(String ddName) {
        return dataRoot.resolve(norm(ddName) + ".idxdat");
    }

    private IndexedHandle require(String ddName) {
        IndexedHandle h = open.get(norm(ddName));
        if (h == null) {
            throw new UnsupportedCobolFeatureException("File not OPEN: " + ddName);
        }
        return h;
    }

    private static String norm(String ddName) {
        return Objects.requireNonNull(ddName, "ddName").trim().toUpperCase();
    }

    private static String keyOf(byte[] record) {
        String s = new String(record, StandardCharsets.UTF_8).trim();
        int tab = s.indexOf('\t');
        if (tab > 0) return s.substring(0, tab);
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static final class IndexedHandle implements AutoCloseable {
        private final Path path;
        private final boolean writable;
        private final LinkedHashMap<String, byte[]> records = new LinkedHashMap<>();
        private Iterator<Map.Entry<String, byte[]>> cursor;
        private String lastKey;

        private IndexedHandle(Path path, boolean writable) {
            this.path = path;
            this.writable = writable;
        }

        static IndexedHandle empty(Path path, boolean writable) {
            return new IndexedHandle(path, writable);
        }

        static IndexedHandle load(Path path, boolean writable) {
            IndexedHandle h = new IndexedHandle(path, writable);
            if (Files.isRegularFile(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        if (line.isBlank()) continue;
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        String key = line.substring(0, tab);
                        String payload = line.substring(tab + 1);
                        h.records.put(key, payload.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    throw new UnsupportedCobolFeatureException("LOAD INDEXED " + path + ": " + e.getMessage(), e);
                }
            }
            h.cursor = h.records.entrySet().iterator();
            return h;
        }

        boolean readKey(String key, byte[] buf) {
            byte[] rec = records.get(key);
            if (rec == null) return false;
            lastKey = key;
            copy(rec, buf);
            return true;
        }

        boolean readNext(byte[] buf) {
            if (cursor == null) cursor = records.entrySet().iterator();
            if (!cursor.hasNext()) return false;
            Map.Entry<String, byte[]> e = cursor.next();
            lastKey = e.getKey();
            copy(e.getValue(), buf);
            return true;
        }

        void write(String key, byte[] record) {
            if (!writable) {
                throw new UnsupportedCobolFeatureException("Not open for OUTPUT/I-O: " + path);
            }
            records.put(key, record.clone());
            lastKey = key;
            cursor = records.entrySet().iterator();
        }

        void rewrite(byte[] record) {
            if (lastKey == null) {
                throw new UnsupportedCobolFeatureException("REWRITE without prior READ: " + path);
            }
            write(lastKey, record);
        }

        void delete(String key) {
            if (!writable) {
                throw new UnsupportedCobolFeatureException("Not open for I-O: " + path);
            }
            records.remove(key);
            if (key.equals(lastKey)) lastKey = null;
            cursor = records.entrySet().iterator();
        }

        void start(String key) {
            cursor = records.entrySet().iterator();
            while (cursor.hasNext()) {
                Map.Entry<String, byte[]> e = cursor.next();
                if (e.getKey().compareTo(key) >= 0) {
                    // re-seat: rebuild iterator from this key
                    LinkedHashMap<String, byte[]> rest = new LinkedHashMap<>();
                    rest.put(e.getKey(), e.getValue());
                    while (cursor.hasNext()) {
                        Map.Entry<String, byte[]> n = cursor.next();
                        rest.put(n.getKey(), n.getValue());
                    }
                    cursor = rest.entrySet().iterator();
                    return;
                }
            }
            cursor = java.util.Collections.emptyIterator();
        }

        @Override
        public void close() throws IOException {
            if (!writable) return;
            Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, byte[]> e : records.entrySet()) {
                sb.append(e.getKey()).append('\t')
                        .append(new String(e.getValue(), StandardCharsets.UTF_8).replace('\n', ' '))
                        .append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        }

        private static void copy(byte[] src, byte[] dest) {
            int n = Math.min(src.length, dest.length);
            System.arraycopy(src, 0, dest, 0, n);
        }
    }
}
