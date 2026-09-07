package com.shadowstack.adapters.cobol.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference sequential-file implementation of {@link CobolFileFacade}.
 * INDEXED/RELATIVE open → {@link UnsupportedCobolFeatureException}.
 */
public final class SequentialCobolFileFacade implements CobolFileFacade {

    private final Path dataRoot;
    private final Map<String, Handle> open = new ConcurrentHashMap<>();

    public SequentialCobolFileFacade(Path dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
    }

    @Override
    public void openInput(String ddName) {
        open.put(norm(ddName), Handle.input(resolve(ddName)));
    }

    @Override
    public void openOutput(String ddName) {
        open.put(norm(ddName), Handle.output(resolve(ddName), false));
    }

    @Override
    public void openExtend(String ddName) {
        open.put(norm(ddName), Handle.output(resolve(ddName), true));
    }

    @Override
    public boolean read(String ddName, byte[] recordBuffer) {
        Handle h = require(ddName);
        try {
            byte[] line = h.readLine();
            if (line == null) {
                return false;
            }
            int n = Math.min(recordBuffer.length, line.length);
            System.arraycopy(line, 0, recordBuffer, 0, n);
            return true;
        } catch (IOException e) {
            throw new UnsupportedCobolFeatureException("READ " + ddName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String ddName, byte[] record) {
        Handle h = require(ddName);
        try {
            h.writeLine(record);
        } catch (IOException e) {
            throw new UnsupportedCobolFeatureException("WRITE " + ddName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void rewrite(String ddName, byte[] record) {
        throw new UnsupportedCobolFeatureException("REWRITE not supported on sequential façade for " + ddName);
    }

    @Override
    public void close(String ddName) {
        Handle h = open.remove(norm(ddName));
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
        return Organization.SEQUENTIAL;
    }

    private Path resolve(String ddName) {
        return dataRoot.resolve(norm(ddName) + ".dat");
    }

    private Handle require(String ddName) {
        Handle h = open.get(norm(ddName));
        if (h == null) {
            throw new UnsupportedCobolFeatureException("File not OPEN: " + ddName);
        }
        return h;
    }

    private static String norm(String ddName) {
        return Objects.requireNonNull(ddName, "ddName").trim().toUpperCase();
    }

    private static final class Handle implements AutoCloseable {
        private final Path path;
        private final boolean input;
        private java.io.BufferedReader reader;
        private java.io.OutputStream writer;

        private Handle(Path path, boolean input) {
            this.path = path;
            this.input = input;
        }

        static Handle input(Path path) {
            Handle h = new Handle(path, true);
            try {
                h.reader = Files.newBufferedReader(path);
            } catch (IOException e) {
                throw new UnsupportedCobolFeatureException("OPEN INPUT " + path + ": " + e.getMessage(), e);
            }
            return h;
        }

        static Handle output(Path path, boolean append) {
            Handle h = new Handle(path, false);
            try {
                Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
                h.writer = Files.newOutputStream(path,
                        append
                                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE});
            } catch (IOException e) {
                throw new UnsupportedCobolFeatureException("OPEN OUTPUT " + path + ": " + e.getMessage(), e);
            }
            return h;
        }

        byte[] readLine() throws IOException {
            if (!input || reader == null) {
                throw new UnsupportedCobolFeatureException("Not open for INPUT: " + path);
            }
            String line = reader.readLine();
            return line == null ? null : line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        void writeLine(byte[] record) throws IOException {
            if (input || writer == null) {
                throw new UnsupportedCobolFeatureException("Not open for OUTPUT: " + path);
            }
            writer.write(record);
            writer.write('\n');
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
        }
    }
}
