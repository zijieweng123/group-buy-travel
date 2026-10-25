package com.wzj.infrastructure.adapter.repository;

import com.wzj.domain.trade.adapter.port.INotifyTaskStatusWriter;
import com.wzj.infrastructure.dao.INotifyTaskDao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded success-receipt batching. Failed writes remain recoverable from notify_task. */
@Slf4j
@Component
public class NotifyTaskStatusWriter implements INotifyTaskStatusWriter, AutoCloseable {
    private final INotifyTaskDao dao;
    private final boolean enabled;
    private final int capacity;
    private final int batchSize;
    private final long flushMillis;
    private final Object monitor = new Object();
    private final LinkedHashSet<String> waiting = new LinkedHashSet<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, Long> acceptedAt = new HashMap<>();
    private final ReentrantLock flushLock = new ReentrantLock();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private final Counter queued, coalesced, fallback, failures, deferred, batchCalls, batchRows, singleCalls;
    private final Timer batchTimer, singleTimer, confirmationLag;
    private final DistributionSummary batchSizes;

    public NotifyTaskStatusWriter(INotifyTaskDao dao, MeterRegistry registry,
            @Value("${trade.notify.batch.enabled:false}") boolean enabled,
            @Value("${trade.notify.batch.capacity:1024}") int capacity,
            @Value("${trade.notify.batch.size:32}") int batchSize,
            @Value("${trade.notify.batch.flush-ms:100}") long flushMillis) {
        if (capacity < 1 || batchSize < 1 || batchSize > capacity || flushMillis < 1)
            throw new IllegalArgumentException("Invalid notification batch limits");
        this.dao = dao; this.enabled = enabled; this.capacity = capacity;
        this.batchSize = batchSize; this.flushMillis = flushMillis;
        queued = registry.counter("trade.notify.status.queued");
        coalesced = registry.counter("trade.notify.status.coalesced");
        fallback = registry.counter("trade.notify.status.fallback");
        failures = registry.counter("trade.notify.status.batch.failures");
        deferred = registry.counter("trade.notify.status.deferred");
        batchCalls = registry.counter("trade.notify.status.batch.calls");
        batchRows = registry.counter("trade.notify.status.batch.rows");
        singleCalls = registry.counter("trade.notify.status.single.calls");
        confirmationLag = registry.timer("trade.notify.status.confirmation.lag");
        batchTimer = registry.timer("trade.notify.status.write", "mode", "batch");
        singleTimer = registry.timer("trade.notify.status.write", "mode", "single");
        batchSizes = registry.summary("trade.notify.status.batch.size");
        Gauge.builder("trade.notify.status.pending", this, NotifyTaskStatusWriter::pendingCount).register(registry);
    }

    @PostConstruct
    public synchronized void start() {
        if (!enabled || closing.get() || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "notify-status-writer");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::flushSafely, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public Disposition recordSuccess(String teamId) {
        if (teamId == null || teamId.isEmpty()) throw new IllegalArgumentException("Missing team id");
        if (!enabled || closing.get()) {
            writeSingle(teamId);
            return Disposition.PERSISTED;
        }
        boolean accepted = false, ready = false;
        synchronized (monitor) {
            if (waiting.contains(teamId) || inFlight.contains(teamId)) {
                coalesced.increment();
                return Disposition.COALESCED;
            }
            // The bound includes the batch currently being written.
            if (!closing.get() && waiting.size() + inFlight.size() < capacity) {
                waiting.add(teamId);
                acceptedAt.put(teamId, System.nanoTime());
                accepted = true;
                ready = waiting.size() >= batchSize;
            }
        }
        if (!accepted) {
            fallback.increment();
            writeSingle(teamId); // Producer backpressure; the durable task also survives a write failure.
            return Disposition.PERSISTED;
        }
        queued.increment();
        if (ready) requestFlush();
        return Disposition.QUEUED;
    }

    @Override
    public boolean isPending(String teamId) {
        synchronized (monitor) { return waiting.contains(teamId) || inFlight.contains(teamId); }
    }

    public int pendingCount() {
        synchronized (monitor) { return waiting.size() + inFlight.size(); }
    }

    private void requestFlush() {
        ScheduledExecutorService worker = scheduler;
        if (worker == null || closing.get() || !requested.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { flushSafely(); }
                finally { requested.set(false); }
                synchronized (monitor) {
                    if (waiting.size() >= batchSize) requestFlush();
                }
            });
        } catch (RejectedExecutionException stopped) {
            requested.set(false); // Shutdown drains the remaining bounded queue.
        }
    }

    /** Flushes at most one batch; useful for explicit draining and deterministic verification. */
    public int flushNow() {
        if (!flushLock.tryLock()) return 0;
        List<String> batch = new ArrayList<>(batchSize);
        try {
            synchronized (monitor) {
                Iterator<String> iterator = waiting.iterator();
                while (iterator.hasNext() && batch.size() < batchSize) {
                    String teamId = iterator.next();
                    batch.add(teamId); iterator.remove(); inFlight.add(teamId);
                }
            }
            if (batch.isEmpty()) return 0;
            batchCalls.increment(); batchSizes.record(batch.size());
            long started = System.nanoTime();
            try {
                int updated = dao.updateNotifyTaskStatusSuccessBatch(batch);
                batchRows.increment(updated);
                long finished = System.nanoTime();
                synchronized (monitor) {
                    for (String teamId : batch)
                        confirmationLag.record(finished - acceptedAt.get(teamId), TimeUnit.NANOSECONDS);
                }
                return updated;
            } catch (RuntimeException failed) {
                failures.increment(); deferred.increment(batch.size());
                throw failed;
            } finally { batchTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
        } finally {
            synchronized (monitor) {
                inFlight.removeAll(batch);
                for (String teamId : batch) acceptedAt.remove(teamId);
            }
            flushLock.unlock();
        }
    }

    private void flushSafely() {
        try { flushNow(); }
        catch (RuntimeException failure) {
            log.warn("Notification status batch write failed; persisted outbox tasks will retry", failure);
        }
    }

    private void writeSingle(String teamId) {
        singleCalls.increment();
        long started = System.nanoTime();
        try { dao.updateNotifyTaskStatusSuccess(teamId); }
        finally { singleTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        ScheduledExecutorService worker = scheduler;
        if (worker != null) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Status writer shutdown timed out; {} tasks retained in the outbox", pendingCount());
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pendingCount() > 0 && System.nanoTime() < deadline) flushSafely();
    }
}
