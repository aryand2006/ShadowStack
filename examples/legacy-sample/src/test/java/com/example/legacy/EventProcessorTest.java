package com.example.legacy;

import com.example.legacy.EventProcessor.Event;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link EventProcessor}.
 *
 * <p>These tests serve as the behavioral baseline for ShadowStack's verification
 * pipeline. After lambda conversion, all these tests must still pass — this is
 * enforced by the TestExecutionVerifier layer.</p>
 */
public class EventProcessorTest {

    private EventProcessor processor;

    @Before
    public void setUp() {
        processor = new EventProcessor();
        processor.addEvent(new Event("deploy", 1000L, 3));
        processor.addEvent(new Event("alert", 500L, 8));
        processor.addEvent(new Event("build", 750L, 1));
        processor.addEvent(new Event("crash", 200L, 10));
        processor.addEvent(new Event("login", 900L, 2));
    }

    @After
    public void tearDown() {
        processor.shutdown();
    }

    @Test
    public void testSortEventsByTimestamp() {
        processor.sortEventsByTimestamp();

        List<Event> events = processor.getEvents();
        assertEquals(5, events.size());

        // Should be sorted ascending by timestamp
        assertEquals("crash", events.get(0).getName());   // 200
        assertEquals("alert", events.get(1).getName());    // 500
        assertEquals("build", events.get(2).getName());    // 750
        assertEquals("login", events.get(3).getName());    // 900
        assertEquals("deploy", events.get(4).getName());   // 1000
    }

    @Test
    public void testSortEventsByName() {
        processor.sortEventsByName();

        List<Event> events = processor.getEvents();
        assertEquals(5, events.size());

        // Should be sorted case-insensitive by name
        assertEquals("alert", events.get(0).getName());
        assertEquals("build", events.get(1).getName());
        assertEquals("crash", events.get(2).getName());
        assertEquals("deploy", events.get(3).getName());
        assertEquals("login", events.get(4).getName());
    }

    @Test
    public void testGetHighSeverityEvents() {
        List<Event> high = processor.getHighSeverityEvents(5);

        assertEquals(2, high.size());
        // alert (severity 8) and crash (severity 10)
        assertTrue(high.stream().allMatch(e -> e.getSeverity() >= 5));
    }

    @Test
    public void testGetHighSeverityEventsNoneMatch() {
        List<Event> extreme = processor.getHighSeverityEvents(100);
        assertTrue(extreme.isEmpty());
    }

    @Test
    public void testGetHighSeverityEventsAllMatch() {
        List<Event> all = processor.getHighSeverityEvents(0);
        assertEquals(5, all.size());
    }

    @Test
    public void testProcessAsync() throws InterruptedException {
        // Just verify it doesn't throw — async processing is fire-and-forget
        processor.processAsync("test-message");
        Thread.sleep(200); // Allow async task to complete
    }

    @Test
    public void testRegisterHandler() {
        // Verify handler registration doesn't throw
        processor.registerHandler(new EventProcessor.EventHandler() {
            @Override
            public void handle(String command) {
                // no-op for testing
            }
        });
    }

    @Test
    public void testEventProperties() {
        Event event = new Event("test", 12345L, 5);

        assertEquals("test", event.getName());
        assertEquals(Long.valueOf(12345L), event.getTimestamp());
        assertEquals(5, event.getSeverity());
        assertTrue(event.toString().contains("test"));
    }

    @Test
    public void testEmptyProcessorSort() {
        EventProcessor empty = new EventProcessor();
        // Should not throw on empty list
        empty.sortEventsByTimestamp();
        empty.sortEventsByName();
        assertTrue(empty.getEvents().isEmpty());
        empty.shutdown();
    }
}
