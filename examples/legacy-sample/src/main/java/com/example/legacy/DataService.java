package com.example.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Legacy data service demonstrating patterns where anonymous class to lambda
 * conversion is NOT always safe. Contains a mix of safe and unsafe candidates.
 *
 * <p>ShadowStack should correctly identify:</p>
 * <ul>
 *   <li>SAFE: Simple Comparator with no captures</li>
 *   <li>SAFE: Callable in executor (flagged as concurrent context, but still convertible)</li>
 *   <li>UNSAFE: Anonymous class that references outer 'this'</li>
 *   <li>UNSAFE: Anonymous class that captures and mutates a variable</li>
 * </ul>
 */
public class DataService {

    private final List<DataRecord> records = new ArrayList<DataRecord>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String lastSortField = "none";

    /**
     * SAFE CANDIDATE: Simple Comparator with no complications.
     *
     * Expected conversion:
     *   Collections.sort(records, (a, b) -> Double.compare(a.getValue(), b.getValue()));
     */
    public void sortByValue() {
        Collections.sort(records, new Comparator<DataRecord>() {
            @Override
            public int compare(DataRecord a, DataRecord b) {
                return Double.compare(a.getValue(), b.getValue());
            }
        });
    }

    /**
     * SAFE CANDIDATE: Callable in executor context.
     *
     * ShadowStack should flag this as "concurrent context" (risk bump to MEDIUM)
     * but all invariants pass, so it IS convertible — just with lower confidence.
     *
     * Expected conversion:
     *   return executor.submit(() -> {
     *       Thread.sleep(100);
     *       return computeTotal();
     *   });
     *
     * Confidence: ~0.80 (base 0.95 - 0.15 concurrent)
     */
    public Future<Double> computeTotalAsync() {
        return executor.submit(new Callable<Double>() {
            @Override
            public Double call() throws Exception {
                Thread.sleep(100);
                return computeTotal();
            }
        });
    }

    /**
     * UNSAFE: Anonymous class uses 'this' to pass itself as an argument.
     *
     * In the anonymous class, 'this' refers to the Runnable instance.
     * In a lambda, 'this' would refer to the enclosing DataService instance.
     * Converting would change the semantics of registerWorker(this).
     *
     * ShadowStack invariant "no_outer_this_capture" should BLOCK this:
     *   VIOLATED: 'this' passed as method argument
     */
    public void startPeriodicRefresh() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                refreshData();
                registerWorker(this);
            }
        });
    }

    /**
     * UNSAFE: Anonymous class captures and MUTATES a local variable.
     *
     * The variable 'count' is mutated inside the anonymous class (count[0]++).
     * While this uses an array to work around the effectively-final restriction,
     * ShadowStack should still flag this as mutable capture pattern because
     * converting to a lambda doesn't change the behavior here, but ShadowStack's
     * conservative approach flags mutable array element access.
     *
     * ShadowStack invariant "no_mutable_capture" should report this with
     * reduced confidence due to the mutable pattern.
     */
    public void processWithCounter() {
        final int[] count = {0};
        filterRecords(new RecordFilter() {
            @Override
            public boolean accept(DataRecord record) {
                count[0]++;
                return record.getValue() > 0;
            }
        });
        System.out.println("Processed " + count[0] + " records");
    }

    /**
     * UNSAFE: Anonymous class references outer 'this' via field access.
     *
     * The anonymous class reads and writes 'lastSortField' which is a field
     * of the enclosing DataService. While this technically works with lambdas
     * (field access goes through the enclosing 'this'), the explicit
     * DataService.this.lastSortField pattern signals the developer's intent
     * to reference the outer class, making conversion a potential confusion point.
     *
     * ShadowStack should convert this but with reduced confidence.
     */
    public void sortByLabel() {
        Collections.sort(records, new Comparator<DataRecord>() {
            @Override
            public int compare(DataRecord a, DataRecord b) {
                DataService.this.lastSortField = "label";
                return a.getLabel().compareTo(b.getLabel());
            }
        });
    }

    // ─── Helper methods ───────────────────────────────────────────────

    private double computeTotal() {
        double total = 0;
        for (DataRecord record : records) {
            total += record.getValue();
        }
        return total;
    }

    private void refreshData() {
        // Simulate data refresh
        System.out.println("Refreshing data...");
    }

    private void registerWorker(Runnable worker) {
        // Register for periodic execution
        System.out.println("Worker registered: " + worker.getClass().getName());
    }

    private List<DataRecord> filterRecords(RecordFilter filter) {
        List<DataRecord> result = new ArrayList<DataRecord>();
        for (DataRecord record : records) {
            if (filter.accept(record)) {
                result.add(record);
            }
        }
        return result;
    }

    public void addRecord(DataRecord record) {
        records.add(record);
    }

    public List<DataRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public String getLastSortField() {
        return lastSortField;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ─── Inner types ──────────────────────────────────────────────────

    @FunctionalInterface
    public interface RecordFilter {
        boolean accept(DataRecord record);
    }

    public static class DataRecord {
        private final String label;
        private final double value;

        public DataRecord(String label, double value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public double getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "DataRecord{label='" + label + "', value=" + value + "}";
        }
    }
}
