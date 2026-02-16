package com.example.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Complex callback handler demonstrating a mix of safe and unsafe anonymous class
 * patterns commonly found in enterprise Java codebases.
 *
 * <p>This class simulates a message processing system with various callback
 * registration patterns. ShadowStack should analyze each and determine
 * convertibility independently.</p>
 *
 * <p>Summary:</p>
 * <ul>
 *   <li>SAFE: Simple MessageHandler callback</li>
 *   <li>SAFE: CompletionCallback (void, single expression)</li>
 *   <li>SAFE: ErrorHandler with effectively final capture</li>
 *   <li>UNSAFE: MessageHandler that uses 'this' for identity</li>
 *   <li>UNSAFE: Callback with getClass() reflection dependency</li>
 *   <li>SAFE: Predicate-style filter callback</li>
 * </ul>
 */
public class LegacyCallbackHandler {

    private final Map<String, List<MessageHandler>> handlers = new HashMap<String, List<MessageHandler>>();
    private final List<CompletionCallback> completionCallbacks = new ArrayList<CompletionCallback>();
    private final List<ErrorHandler> errorHandlers = new ArrayList<ErrorHandler>();

    /**
     * SAFE CANDIDATE: Simple MessageHandler callback.
     *
     * Clean functional interface usage, no captures, single expression body.
     *
     * Expected conversion:
     *   registerHandler("greeting", message -> System.out.println("Hello: " + message));
     */
    public void setupGreetingHandler() {
        registerHandler("greeting", new MessageHandler() {
            @Override
            public void onMessage(String message) {
                System.out.println("Hello: " + message);
            }
        });
    }

    /**
     * SAFE CANDIDATE: CompletionCallback with no complications.
     *
     * Expected conversion:
     *   onComplete((success, result) -> {
     *       if (success) {
     *           System.out.println("Completed: " + result);
     *       } else {
     *           System.err.println("Failed: " + result);
     *       }
     *   });
     */
    public void setupCompletionHandler() {
        onComplete(new CompletionCallback() {
            @Override
            public void onComplete(boolean success, String result) {
                if (success) {
                    System.out.println("Completed: " + result);
                } else {
                    System.err.println("Failed: " + result);
                }
            }
        });
    }

    /**
     * SAFE CANDIDATE: ErrorHandler capturing effectively final variable.
     *
     * The 'context' parameter is effectively final, so capture is safe.
     * Multiple statements → block lambda.
     *
     * Expected conversion:
     *   onError(error -> {
     *       System.err.println("[" + context + "] Error: " + error.getMessage());
     *       error.printStackTrace();
     *   });
     */
    public void setupErrorHandler(final String context) {
        onError(new ErrorHandler() {
            @Override
            public void onError(Exception error) {
                System.err.println("[" + context + "] Error: " + error.getMessage());
                error.printStackTrace();
            }
        });
    }

    /**
     * UNSAFE: MessageHandler that passes 'this' as argument.
     *
     * The anonymous class passes 'this' (the anonymous Runnable/MessageHandler
     * instance itself) to the trackHandler method. In a lambda, 'this' would
     * refer to LegacyCallbackHandler, completely changing semantics.
     *
     * ShadowStack invariant "no_outer_this_capture" should BLOCK this:
     *   VIOLATED: 'this' passed as method argument
     */
    public void setupTrackedHandler() {
        registerHandler("tracked", new MessageHandler() {
            @Override
            public void onMessage(String message) {
                System.out.println("Tracked: " + message);
                trackHandler(this);
            }
        });
    }

    /**
     * UNSAFE: Callback that uses getClass() — reflection dependency.
     *
     * The anonymous class calls this.getClass().getSimpleName() for logging.
     * In an anonymous class, getClass() returns the anonymous class type
     * (e.g., LegacyCallbackHandler$5). In a lambda, getClass() returns
     * the enclosing class. Converting would change the log output.
     *
     * ShadowStack invariant "no_reflection_dependency" should BLOCK this:
     *   VIOLATED: getClass() at position N
     */
    public void setupLoggingHandler() {
        registerHandler("logging", new MessageHandler() {
            @Override
            public void onMessage(String message) {
                String handlerName = this.getClass().getSimpleName();
                System.out.println("[" + handlerName + "] " + message);
            }
        });
    }

    /**
     * SAFE CANDIDATE: Predicate-style filter using MessageFilter.
     *
     * Clean single-expression return, no captures, no complications.
     *
     * Expected conversion:
     *   return filterMessages(messages, msg -> msg != null && msg.length() > minLength);
     */
    public List<String> getValidMessages(List<String> messages, final int minLength) {
        return filterMessages(messages, new MessageFilter() {
            @Override
            public boolean accept(String message) {
                return message != null && message.length() > minLength;
            }
        });
    }

    /**
     * SAFE CANDIDATE: Runnable with CountDownLatch (concurrent context).
     *
     * All invariants pass, but ShadowStack should flag "concurrent context"
     * and reduce confidence score.
     *
     * Expected conversion:
     *   Thread t = new Thread(() -> {
     *       try {
     *           processMessage(message);
     *       } finally {
     *           latch.countDown();
     *       }
     *   });
     *
     * Confidence: ~0.75 (base 0.95 - 0.10 outerVars - 0.15 concurrent + 0.05 multi-stmt)
     */
    public void processWithLatch(final String message, final CountDownLatch latch) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    processMessage(message);
                } finally {
                    latch.countDown();
                }
            }
        });
        t.start();
    }

    // ─── Helper methods ───────────────────────────────────────────────

    private void registerHandler(String topic, MessageHandler handler) {
        List<MessageHandler> topicHandlers = handlers.get(topic);
        if (topicHandlers == null) {
            topicHandlers = new ArrayList<MessageHandler>();
            handlers.put(topic, topicHandlers);
        }
        topicHandlers.add(handler);
    }

    private void onComplete(CompletionCallback callback) {
        completionCallbacks.add(callback);
    }

    private void onError(ErrorHandler handler) {
        errorHandlers.add(handler);
    }

    private void trackHandler(MessageHandler handler) {
        System.out.println("Tracking handler: " + handler.hashCode());
    }

    private void processMessage(String message) {
        System.out.println("Processing: " + message);
    }

    private List<String> filterMessages(List<String> messages, MessageFilter filter) {
        List<String> result = new ArrayList<String>();
        for (String msg : messages) {
            if (filter.accept(msg)) {
                result.add(msg);
            }
        }
        return result;
    }

    public void dispatch(String topic, String message) {
        List<MessageHandler> topicHandlers = handlers.get(topic);
        if (topicHandlers != null) {
            for (MessageHandler handler : topicHandlers) {
                try {
                    handler.onMessage(message);
                } catch (Exception e) {
                    for (ErrorHandler errorHandler : errorHandlers) {
                        errorHandler.onError(e);
                    }
                }
            }
        }

        for (CompletionCallback callback : completionCallbacks) {
            callback.onComplete(true, "Dispatched to " + topic);
        }
    }

    // ─── Callback interfaces ──────────────────────────────────────────

    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(String message);
    }

    @FunctionalInterface
    public interface CompletionCallback {
        void onComplete(boolean success, String result);
    }

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(Exception error);
    }

    @FunctionalInterface
    public interface MessageFilter {
        boolean accept(String message);
    }
}
