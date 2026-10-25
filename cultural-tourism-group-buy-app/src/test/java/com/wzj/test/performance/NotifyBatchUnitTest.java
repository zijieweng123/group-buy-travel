package com.wzj.test.performance;

import com.wzj.domain.trade.adapter.port.INotifyTaskStatusWriter;
import com.wzj.domain.trade.model.entity.NotifyTaskEntity;
import com.wzj.domain.trade.model.valobj.NotifyTypeEnumVO;
import com.wzj.domain.trade.service.settlement.TradeSettlementOrderService;
import com.wzj.infrastructure.adapter.port.TradePort;
import com.wzj.infrastructure.adapter.repository.NotifyTaskStatusWriter;
import com.wzj.infrastructure.adapter.repository.TradeTransactions;
import com.wzj.infrastructure.dao.INotifyTaskDao;
import com.wzj.infrastructure.event.EventPublisher;
import com.wzj.infrastructure.redis.IRedisService;
import com.wzj.types.enums.NotifyTaskHTTPEnumVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class NotifyBatchUnitTest {
    private INotifyTaskDao successfulDao(Set<String> stored) {
        INotifyTaskDao dao = mock(INotifyTaskDao.class);
        when(dao.updateNotifyTaskStatusSuccess(anyString())).thenAnswer(call -> {
            stored.add(call.getArgument(0)); return 1;
        });
        when(dao.updateNotifyTaskStatusSuccessBatch(anyList())).thenAnswer(call -> {
            List<String> ids = call.getArgument(0); stored.addAll(ids); return ids.size();
        });
        return dao;
    }

    @Test
    @Timeout(10)
    public void concurrentReceiptsForOneTeamWriteOnce() throws Exception {
        Set<String> stored = ConcurrentHashMap.newKeySet();
        INotifyTaskDao dao = successfulDao(stored);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, registry, true, 32, 16, 100)) {
            ExecutorService clients = Executors.newFixedThreadPool(20);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 20; i++) tasks.add(clients.submit(() -> {
                    start.await(); return writer.recordSuccess("team");
                }));
                start.countDown();
                for (Future<?> task : tasks) task.get(3, TimeUnit.SECONDS);
                assertEquals(1, writer.pendingCount());
                assertEquals(1, writer.flushNow());
                assertEquals(Collections.singleton("team"), stored);
                verify(dao, times(1)).updateNotifyTaskStatusSuccessBatch(Collections.singletonList("team"));
                verify(dao, never()).updateNotifyTaskStatusSuccess(anyString());
                assertEquals(19, registry.get("trade.notify.status.coalesced").counter().count());
            } finally { start.countDown(); clients.shutdownNow(); }
        }
    }

    @Test
    public void fullQueueAppliesBackpressureWithoutDroppingReceipts() {
        Set<String> stored = new HashSet<>();
        INotifyTaskDao dao = successfulDao(stored);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, registry, true, 2, 2, 100)) {
            assertEquals(INotifyTaskStatusWriter.Disposition.QUEUED, writer.recordSuccess("a"));
            assertEquals(INotifyTaskStatusWriter.Disposition.QUEUED, writer.recordSuccess("b"));
            assertEquals(INotifyTaskStatusWriter.Disposition.PERSISTED, writer.recordSuccess("c"));
            assertEquals(2, writer.pendingCount());
            writer.flushNow();
            assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), stored);
            assertEquals(1, registry.get("trade.notify.status.fallback").counter().count());
        }
    }

    @Test
    @Timeout(10)
    public void boundAndDeduplicationIncludeTheInFlightBatch() throws Exception {
        INotifyTaskDao dao = mock(INotifyTaskDao.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(dao.updateNotifyTaskStatusSuccessBatch(anyList())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            return 1;
        });
        NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, new SimpleMeterRegistry(), true, 1, 1, 100);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            writer.recordSuccess("a");
            Future<Integer> flushing = worker.submit(writer::flushNow);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(INotifyTaskStatusWriter.Disposition.COALESCED, writer.recordSuccess("a"));
            assertEquals(INotifyTaskStatusWriter.Disposition.PERSISTED, writer.recordSuccess("b"));
            assertEquals(1, writer.pendingCount());
            verify(dao).updateNotifyTaskStatusSuccess("b");
            release.countDown(); assertEquals(1, flushing.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); worker.shutdownNow(); writer.close(); }
    }

    @Test
    public void failedBatchAllowsPersistedTasksToBeRetried() {
        INotifyTaskDao dao = mock(INotifyTaskDao.class);
        when(dao.updateNotifyTaskStatusSuccessBatch(anyList()))
                .thenThrow(new IllegalStateException("Database unavailable")).thenReturn(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, registry, true, 8, 4, 100)) {
            writer.recordSuccess("a"); writer.recordSuccess("b");
            assertThrows(IllegalStateException.class, writer::flushNow);
            assertEquals(0, writer.pendingCount());
            assertFalse(writer.isPending("a"));
            assertEquals(2, registry.get("trade.notify.status.deferred").counter().count());
            writer.recordSuccess("a"); writer.recordSuccess("b");
            assertEquals(2, writer.flushNow());
            assertEquals(1, registry.get("trade.notify.status.batch.failures").counter().count());
        }
    }

    @Test
    public void shutdownFlushesPartialBatchAndLateReceiptsWriteSynchronously() {
        Set<String> stored = new HashSet<>();
        INotifyTaskDao dao = successfulDao(stored);
        NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, new SimpleMeterRegistry(), true, 16, 8, 100);
        writer.recordSuccess("a"); writer.recordSuccess("b");
        writer.close(); writer.close();
        assertEquals(0, writer.pendingCount());
        assertEquals(INotifyTaskStatusWriter.Disposition.PERSISTED, writer.recordSuccess("c"));
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), stored);
        verify(dao, times(1)).updateNotifyTaskStatusSuccessBatch(Arrays.asList("a", "b"));
    }

    @Test
    @Timeout(5)
    public void lowTrafficIsFlushedByTheTimer() throws Exception {
        INotifyTaskDao dao = mock(INotifyTaskDao.class);
        CountDownLatch flushed = new CountDownLatch(1);
        when(dao.updateNotifyTaskStatusSuccessBatch(anyList())).thenAnswer(call -> {
            flushed.countDown(); return 1;
        });
        NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, new SimpleMeterRegistry(), true, 16, 8, 30);
        try {
            writer.start(); writer.recordSuccess("a");
            assertTrue(flushed.await(2, TimeUnit.SECONDS));
        } finally { writer.close(); }
        assertEquals(0, writer.pendingCount());
    }

    @Test
    public void disabledBatchKeepsSynchronousPersistence() {
        INotifyTaskDao dao = mock(INotifyTaskDao.class);
        try (NotifyTaskStatusWriter writer = new NotifyTaskStatusWriter(dao, new SimpleMeterRegistry(), false, 16, 8, 100)) {
            writer.start();
            assertEquals(INotifyTaskStatusWriter.Disposition.PERSISTED, writer.recordSuccess("a"));
            assertEquals(0, writer.pendingCount());
            verify(dao).updateNotifyTaskStatusSuccess("a");
            verify(dao, never()).updateNotifyTaskStatusSuccessBatch(anyList());
        }
    }

    private AnnotationConfigApplicationContext statusContext(INotifyTaskDao dao, boolean enableExplicitly) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (enableExplicitly) context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("batch-test", Collections.singletonMap("trade.notify.batch.enabled", "true")));
        context.registerBean(INotifyTaskDao.class, () -> dao);
        context.registerBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new);
        context.register(NotifyTaskStatusWriter.class);
        context.refresh();
        return context;
    }

    @Test
    public void defaultSpringConfigurationUsesSynchronousStatusWrites() {
        INotifyTaskDao dao = mock(INotifyTaskDao.class);
        try (AnnotationConfigApplicationContext context = statusContext(dao, false)) {
            NotifyTaskStatusWriter writer = context.getBean(NotifyTaskStatusWriter.class);
            assertEquals(INotifyTaskStatusWriter.Disposition.PERSISTED, writer.recordSuccess("default"));
            assertEquals(0, writer.pendingCount());
            verify(dao).updateNotifyTaskStatusSuccess("default");
            verify(dao, never()).updateNotifyTaskStatusSuccessBatch(anyList());
        }
    }

    @Test
    public void explicitSpringPropertyEnablesBatchingAndShutdownDrainsIt() {
        Set<String> stored = ConcurrentHashMap.newKeySet();
        INotifyTaskDao dao = successfulDao(stored);
        try (AnnotationConfigApplicationContext context = statusContext(dao, true)) {
            NotifyTaskStatusWriter writer = context.getBean(NotifyTaskStatusWriter.class);
            assertEquals(INotifyTaskStatusWriter.Disposition.QUEUED, writer.recordSuccess("explicit"));
        }
        assertEquals(Collections.singleton("explicit"), stored);
        verify(dao, never()).updateNotifyTaskStatusSuccess(anyString());
    }

    @Test
    public void transactionSuccessCommitsExactlyOnceAndSeparatesPhases() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus(true);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TradeTransactions transactions = new TradeTransactions(manager, registry, 500);
        assertEquals("stored", transactions.execute(TradeTransactions.Operation.LOCK, () -> "stored"));
        verify(manager).commit(status); verify(manager, never()).rollback(any());
        for (String phase : Arrays.asList("begin", "body", "complete", "total"))
            assertEquals(1, registry.get("trade.transaction.phase").tags("operation", "lock", "phase", phase).timer().count());
    }

    @Test
    public void failedTransactionRollsBackAndPropagatesOriginalFailure() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus(true);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TradeTransactions transactions = new TradeTransactions(manager, registry, 500);
        IllegalStateException original = new IllegalStateException("Order insert failed");
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> transactions.execute(TradeTransactions.Operation.SETTLEMENT, () -> { throw original; })));
        verify(manager).rollback(status); verify(manager, never()).commit(any());
        assertEquals(1, registry.get("trade.transaction.outcome").tags("operation", "settlement", "result", "failure").counter().count());
    }

    @Test
    public void commitFailureIsNotReportedAsSuccessfulOrder() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus(true);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        doThrow(new IllegalStateException("Commit failed")).when(manager).commit(status);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TradeTransactions transactions = new TradeTransactions(manager, registry, 500);
        assertThrows(IllegalStateException.class, () -> transactions.execute(TradeTransactions.Operation.LOCK, () -> "stored"));
        assertEquals(0, registry.get("trade.transaction.outcome").tags("operation", "lock", "result", "success").counter().count());
        assertEquals(1, registry.get("trade.transaction.outcome").tags("operation", "lock", "result", "failure").counter().count());
    }

    @Test
    public void queuedDeliveryIsNotReportedAsPersistedStatus() throws Exception {
        TradeSettlementOrderService service = new TradeSettlementOrderService();
        com.wzj.domain.trade.adapter.port.ITradePort port = mock(com.wzj.domain.trade.adapter.port.ITradePort.class);
        INotifyTaskStatusWriter writer = mock(INotifyTaskStatusWriter.class);
        NotifyTaskEntity task = NotifyTaskEntity.builder().teamId("a").build();
        when(port.groupBuyNotify(task)).thenReturn(NotifyTaskHTTPEnumVO.SUCCESS.getCode());
        when(writer.recordSuccess("a")).thenReturn(INotifyTaskStatusWriter.Disposition.QUEUED);
        ReflectionTestUtils.setField(service, "port", port);
        ReflectionTestUtils.setField(service, "statusWriter", writer);
        Map<String,Integer> result = service.execSettlementNotifyJob(task);
        assertEquals(0, result.get("successCount"));
        assertEquals(1, result.get("queuedSuccessCount"));
    }

    @Test
    public void pendingConfirmationIsNotSentAgainByCompensation() throws Exception {
        TradeSettlementOrderService service = new TradeSettlementOrderService();
        com.wzj.domain.trade.adapter.port.ITradePort port = mock(com.wzj.domain.trade.adapter.port.ITradePort.class);
        INotifyTaskStatusWriter writer = mock(INotifyTaskStatusWriter.class);
        NotifyTaskEntity task = NotifyTaskEntity.builder().teamId("a").build();
        when(writer.isPending("a")).thenReturn(true);
        ReflectionTestUtils.setField(service, "port", port);
        ReflectionTestUtils.setField(service, "statusWriter", writer);
        assertEquals(1, service.execSettlementNotifyJob(task).get("alreadyPendingCount"));
        verifyNoInteractions(port);
    }

    @Test
    public void deliveryFailureDoesNotInterruptThePooledWorker() throws Exception {
        TradePort port = new TradePort();
        IRedisService redis = mock(IRedisService.class);
        RLock lock = mock(RLock.class);
        EventPublisher publisher = mock(EventPublisher.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3, 0, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new IllegalStateException("Broker unavailable")).when(publisher).publish(anyString(), anyString());
        ReflectionTestUtils.setField(port, "redisService", redis);
        ReflectionTestUtils.setField(port, "publisher", publisher);
        NotifyTaskEntity task = NotifyTaskEntity.builder().teamId("a").notifyType(NotifyTypeEnumVO.MQ.getCode())
                .notifyMQ("topic").parameterJson("{}").build();
        try {
            assertEquals(NotifyTaskHTTPEnumVO.ERROR.getCode(), port.groupBuyNotify(task));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
