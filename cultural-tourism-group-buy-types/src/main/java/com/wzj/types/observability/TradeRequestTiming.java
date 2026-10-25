package com.wzj.types.observability;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLongArray;

/** Opt-in, fixed-size request timings; never contains business identifiers or SQL text. */
public final class TradeRequestTiming {
    public enum Stage {
        LOOKUP, PROGRESS, MARKET, RULES, TX_BEGIN, TX_BODY, TX_COMPLETE,
        POOL, SQL, REDIS, JDBC_PREPARE, JDBC_STATE, COMMIT, ROLLBACK, JDBC_CLOSE,
        MARKET_QUEUE, MARKET_TASK, NOTIFY_DISPATCH
    }
    @FunctionalInterface public interface Work<T, E extends Exception> { T run() throws E; }
    public static final class Trace {
        private final long started = System.nanoTime();
        private final AtomicLongArray nanos = new AtomicLongArray(Stage.values().length);
        private final AtomicLongArray counts = new AtomicLongArray(Stage.values().length);
        private void add(Stage stage, long duration) {
            nanos.addAndGet(stage.ordinal(), Math.max(0, duration));
            counts.incrementAndGet(stage.ordinal());
        }
        public String header() {
            StringBuilder value = new StringBuilder("app;dur=").append(ms(System.nanoTime()-started));
            for (Stage stage : Stage.values()) {
                int i = stage.ordinal();
                value.append(",").append(stage.name().toLowerCase(Locale.ROOT)).append(";dur=")
                     .append(ms(nanos.get(i))).append(";desc=\"").append(counts.get(i)).append("\"");
            }
            return value.toString();
        }
        public long count(Stage stage) { return counts.get(stage.ordinal()); }
    }
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private TradeRequestTiming() { }
    private static String ms(long nanos) {
        long micros = nanos / 1000; long fraction = micros % 1000;
        return (micros / 1000) + "." + (fraction < 100 ? (fraction < 10 ? "00" : "0") : "") + fraction;
    }
    public static Trace start() { Trace trace = new Trace(); CURRENT.set(trace); return trace; }
    public static Trace current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
    public static void record(Stage stage, long duration) {
        Trace trace = CURRENT.get(); if (trace != null) trace.add(stage, duration);
    }
    public static <T, E extends Exception> T call(Stage stage, Work<T, E> work) throws E {
        Trace trace = CURRENT.get();
        if (trace == null) return work.run();
        long start = System.nanoTime();
        try { return work.run(); } finally { trace.add(stage, System.nanoTime()-start); }
    }
    /** Captures only the opt-in trace; restores a reused worker's prior context. */
    public static <T> Callable<T> marketTask(Callable<T> work) {
        Trace captured = CURRENT.get();
        if (captured == null) return work;
        long submitted = System.nanoTime();
        return () -> {
            Trace previous = CURRENT.get(); CURRENT.set(captured);
            captured.add(Stage.MARKET_QUEUE, System.nanoTime()-submitted);
            try { return call(Stage.MARKET_TASK, work::call); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        };
    }
}
