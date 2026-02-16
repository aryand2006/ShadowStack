package com.example.legacy;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Legacy event processing class that demonstrates several anonymous inner class
 * patterns commonly found in Java 8-era codebases.
 *
 * <p>ShadowStack should identify the following conversion candidates:</p>
 * <ul>
 *   <li>Candidate 1: Comparator anonymous class → lambda (SAFE)</li>
 *   <li>Candidate 2: Runnable anonymous class → lambda (SAFE)</li>
 *   <li>Candidate 3: ActionListener callback → lambda (SAFE)</li>
 *   <li>Candidate 4: Custom EventHandler callback → lambda (SAFE)</li>
 *   <li>Anti-pattern: Comparator that overrides toString() → NOT safe to convert</li>
 * </ul>
 */
public class EventProcessor {

    private final List<Event> events = new ArrayList<Event>();
    private final List<ActionListener> listeners = new ArrayList<ActionListener>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * CANDIDATE 1: Anonymous Comparator → Lambda
     *
     * This is a textbook lambda conversion candidate:
     * - Comparator is a functional interface (SAM: compare)
     * - No outer 'this' capture
     * - No mutable variable capture
     * - No Object method overrides
     * - Single return statement → expression lambda
     *
     * Expected conversion:
     *   Collections.sort(events, (e1, e2) -> e1.getTimestamp().compareTo(e2.getTimestamp()));
     */
    public void sortEventsByTimestamp() {
        Collections.sort(events, new Comparator<Event>() {
            @Override
            public int compare(Event e1, Event e2) {
                return e1.getTimestamp().compareTo(e2.getTimestamp());
            }
        });
    }

    /**
     * CANDIDATE 2: Anonymous Runnable → Lambda
     *
     * Safe to convert:
     * - Runnable is a functional interface (SAM: run)
     * - No outer 'this' capture
     * - Captures 'message' but it's effectively final
     * - Void-compatible single expression
     *
     * Expected conversion:
     *   executor.submit(() -> System.out.println("Processing: " + message));
     */
    public void processAsync(final String message) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println("Processing: " + message);
            }
        });
    }

    /**
     * CANDIDATE 3: Anonymous ActionListener → Lambda
     *
     * Safe to convert:
     * - ActionListener is a functional interface (SAM: actionPerformed)
     * - No outer 'this' capture
     * - Captures 'handler' but it's effectively final
     * - Multiple statements → block lambda
     *
     * Expected conversion:
     *   addListener((ActionEvent e) -> {
     *       String command = e.getActionCommand();
     *       handler.handle(command);
     *   });
     */
    public void registerHandler(final EventHandler handler) {
        addListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String command = e.getActionCommand();
                handler.handle(command);
            }
        });
    }

    /**
     * CANDIDATE 4: Custom functional interface callback → Lambda
     *
     * Safe to convert:
     * - EventFilter is a functional interface (SAM: matches)
     * - No outer 'this' capture
     * - No mutable variable capture
     * - Single return statement → expression lambda
     *
     * Expected conversion:
     *   return filterEvents(event -> event.getSeverity() >= minSeverity);
     */
    public List<Event> getHighSeverityEvents(final int minSeverity) {
        return filterEvents(new EventFilter() {
            @Override
            public boolean matches(Event event) {
                return event.getSeverity() >= minSeverity;
            }
        });
    }

    /**
     * ANTI-PATTERN: Anonymous Comparator that overrides toString()
     *
     * NOT safe to convert because:
     * - Overrides toString() in addition to compare()
     * - Lambda expressions cannot override Object methods
     * - ShadowStack invariant check "no_object_method_override" should BLOCK this
     *
     * ShadowStack should flag this with:
     *   Invariant VIOLATED: no_object_method_override
     *   "Method 'toString' is an Object method override — cannot convert to lambda"
     */
    public void sortEventsByName() {
        Collections.sort(events, new Comparator<Event>() {
            @Override
            public int compare(Event e1, Event e2) {
                return e1.getName().compareToIgnoreCase(e2.getName());
            }

            @Override
            public String toString() {
                return "CaseInsensitiveNameComparator";
            }
        });
    }

    // ─── Helper methods ───────────────────────────────────────────────

    private void addListener(ActionListener listener) {
        listeners.add(listener);
    }

    private List<Event> filterEvents(EventFilter filter) {
        List<Event> result = new ArrayList<Event>();
        for (Event event : events) {
            if (filter.matches(event)) {
                result.add(event);
            }
        }
        return result;
    }

    public void addEvent(Event event) {
        events.add(event);
    }

    public List<Event> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ─── Inner types ──────────────────────────────────────────────────

    /**
     * Custom functional interface — a single abstract method interface
     * that ShadowStack should recognize as a lambda conversion target.
     */
    @FunctionalInterface
    public interface EventFilter {
        boolean matches(Event event);
    }

    /**
     * Custom callback interface for event handling.
     */
    @FunctionalInterface
    public interface EventHandler {
        void handle(String command);
    }

    /**
     * Simple event POJO used throughout the examples.
     */
    public static class Event {
        private final String name;
        private final long timestamp;
        private final int severity;

        public Event(String name, long timestamp, int severity) {
            this.name = name;
            this.timestamp = timestamp;
            this.severity = severity;
        }

        public String getName() {
            return name;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public int getSeverity() {
            return severity;
        }

        @Override
        public String toString() {
            return "Event{name='" + name + "', timestamp=" + timestamp + ", severity=" + severity + "}";
        }
    }
}
