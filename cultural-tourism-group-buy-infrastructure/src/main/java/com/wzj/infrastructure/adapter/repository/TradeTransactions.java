package com.wzj.infrastructure.adapter.repository;

import com.wzj.types.observability.TradeRequestTiming;
import com.wzj.types.observability.TradeRequestTiming.Stage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** REQUIRED transactions with bounded operation/phase tags and separate completion timing. */
@Component
public class TradeTransactions {
    public enum Operation { LOCK, SETTLEMENT }
    private final TransactionTemplate template;
    private final EnumMap<Operation, Timer[]> timers = new EnumMap<>(Operation.class);
    private final EnumMap<Operation, Counter[]> outcomes = new EnumMap<>(Operation.class);

    public TradeTransactions(PlatformTransactionManager manager, MeterRegistry registry,
            @Value("${trade.transaction-timeout-seconds:500}") int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("Invalid transaction timeout");
        template = new TransactionTemplate(manager);
        template.setTimeout(timeoutSeconds);
        for (Operation operation : Operation.values()) {
            String name = operation.name().toLowerCase(java.util.Locale.ROOT);
            String[] phases = {"begin", "body", "complete", "total"};
            Timer[] values = new Timer[phases.length];
            for (int i = 0; i < phases.length; i++)
                values[i] = registry.timer("trade.transaction.phase", "operation", name, "phase", phases[i]);
            timers.put(operation, values);
            outcomes.put(operation, new Counter[]{
                    registry.counter("trade.transaction.outcome", "operation", name, "result", "success"),
                    registry.counter("trade.transaction.outcome", "operation", name, "result", "failure")});
        }
    }

    public <T> T execute(Operation operation, Supplier<T> action) {
        long started = System.nanoTime();
        long[] boundaries = new long[2];
        boolean[] owned = new boolean[1];
        boolean success = false;
        try {
            T result = template.execute(status -> {
                owned[0] = status.isNewTransaction();
                boundaries[0] = System.nanoTime();
                try { return action.get(); }
                finally { boundaries[1] = System.nanoTime(); }
            });
            success = true;
            return result;
        } finally {
            long finished = System.nanoTime();
            Timer[] stages = timers.get(operation);
            if (boundaries[0] != 0) {
                stages[0].record(boundaries[0] - started, TimeUnit.NANOSECONDS);
                TradeRequestTiming.record(Stage.TX_BEGIN, boundaries[0] - started);
            }
            if (boundaries[1] != 0) {
                stages[1].record(boundaries[1] - boundaries[0], TimeUnit.NANOSECONDS);
                TradeRequestTiming.record(Stage.TX_BODY, boundaries[1] - boundaries[0]);
                // Includes commit/rollback and connection cleanup, not only server-side COMMIT.
                if (owned[0]) {
                    stages[2].record(finished - boundaries[1], TimeUnit.NANOSECONDS);
                    TradeRequestTiming.record(Stage.TX_COMPLETE, finished - boundaries[1]);
                }
            }
            stages[3].record(finished - started, TimeUnit.NANOSECONDS);
            outcomes.get(operation)[success ? 0 : 1].increment();
        }
    }
}
