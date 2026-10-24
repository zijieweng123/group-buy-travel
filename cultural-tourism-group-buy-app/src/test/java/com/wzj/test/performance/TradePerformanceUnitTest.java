package com.wzj.test.performance;

import com.wzj.config.ThreadPoolConfig;
import com.wzj.config.ThreadPoolConfigProperties;
import com.wzj.domain.activity.adapter.repository.IActivityRepository;
import com.wzj.domain.activity.model.entity.MarketProductEntity;
import com.wzj.domain.activity.model.valobj.SkuVO;
import com.wzj.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import com.wzj.domain.activity.service.trial.node.MarketNode;
import com.wzj.infrastructure.adapter.repository.SkuCatalogCache;
import com.wzj.infrastructure.dao.ISkuDao;
import com.wzj.infrastructure.dao.po.Sku;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TradePerformanceUnitTest {
    private Sku sku(String price) {
        Sku value = new Sku();
        value.setGoodsId("ticket"); value.setGoodsName("Ticket");
        value.setOriginalPrice(new BigDecimal(price));
        return value;
    }

    @Test
    @Timeout(10)
    public void concurrentCatalogMissesLoadOnce() throws Exception {
        ISkuDao dao = mock(ISkuDao.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(dao.querySkuByGoodsId("ticket")).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test loader timeout");
            return sku("100");
        });
        SkuCatalogCache cache = new SkuCatalogCache(dao, true, 5000, 32);
        ExecutorService clients = Executors.newFixedThreadPool(20);
        try {
            List<Future<SkuVO>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(clients.submit(() -> cache.query("ticket", true)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            for (Future<SkuVO> future : futures)
                assertEquals(new BigDecimal("100"), future.get(3, TimeUnit.SECONDS).getOriginalPrice());
            verify(dao, times(1)).querySkuByGoodsId("ticket");
        } finally {
            release.countDown(); clients.shutdownNow();
        }
    }

    @Test
    public void invalidationReloadsChangedCatalog() {
        ISkuDao dao = mock(ISkuDao.class);
        when(dao.querySkuByGoodsId("ticket")).thenReturn(sku("100"), sku("200"));
        SkuCatalogCache cache = new SkuCatalogCache(dao, true, 5000, 32);
        assertEquals(new BigDecimal("100"), cache.query("ticket", true).getOriginalPrice());
        assertEquals(new BigDecimal("100"), cache.query("ticket", true).getOriginalPrice());
        cache.invalidate("ticket");
        assertEquals(new BigDecimal("200"), cache.query("ticket", true).getOriginalPrice());
        verify(dao, times(2)).querySkuByGoodsId("ticket");
    }

    @Test
    public void cacheSwitchOffUsesFreshReads() {
        ISkuDao dao = mock(ISkuDao.class);
        when(dao.querySkuByGoodsId("ticket")).thenReturn(sku("100"), sku("200"));
        SkuCatalogCache cache = new SkuCatalogCache(dao, true, 5000, 32);
        assertEquals(new BigDecimal("100"), cache.query("ticket", false).getOriginalPrice());
        assertEquals(new BigDecimal("200"), cache.query("ticket", false).getOriginalPrice());
        verify(dao, times(2)).querySkuByGoodsId("ticket");
    }

    @Test
    public void failedLoadDoesNotRemainCached() {
        ISkuDao dao = mock(ISkuDao.class);
        when(dao.querySkuByGoodsId("ticket")).thenThrow(new IllegalStateException("Database unavailable"))
                .thenReturn(sku("100"));
        SkuCatalogCache cache = new SkuCatalogCache(dao, true, 5000, 32);
        try {
            cache.query("ticket", true);
            fail("Failed load must propagate");
        } catch (RuntimeException expected) {
            assertNotNull(expected);
        }
        assertEquals(new BigDecimal("100"), cache.query("ticket", true).getOriginalPrice());
        verify(dao, times(2)).querySkuByGoodsId("ticket");
    }

    public static class MarketProbe extends MarketNode {
        void load() throws Exception {
            multiThread(MarketProductEntity.builder().userId("u").source("s").channel("c")
                    .goodsId("ticket").build(), new DefaultActivityStrategyFactory.DynamicContext());
        }
    }

    @Test
    @Timeout(5)
    public void expiredQueryBudgetCancelsSiblingWork() throws Exception {
        IActivityRepository repository = mock(IActivityRepository.class);
        CountDownLatch interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        when(repository.querySkuByGoodsId("ticket")).thenAnswer(call -> {
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            return null;
        });
        ThreadPoolExecutor workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        MarketProbe probe = new MarketProbe();
        ReflectionTestUtils.setField(probe, "repository", repository);
        ReflectionTestUtils.setField(probe, "threadPoolExecutor", workers);
        ReflectionTestUtils.setField(probe, "queryTimeoutMillis", 100L);
        try {
            try { probe.load(); fail("Expected timeout"); }
            catch (TimeoutException expected) { /* Budget must expire, not wait 500 minutes. */ }
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "Outstanding query must be cancelled");
        } finally {
            release.countDown(); workers.shutdownNow();
        }
    }

    @Test
    public void bothNamedPoolsExistAndDefaultInjectionSelectsQueries() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ThreadPoolConfig.class)) {
            ThreadPoolExecutor queries = context.getBean("threadPoolExecutor", ThreadPoolExecutor.class);
            ThreadPoolExecutor notifications = context.getBean("notifyThreadPoolExecutor", ThreadPoolExecutor.class);
            assertNotSame(queries, notifications);
            assertSame(queries, context.getBean(ThreadPoolExecutor.class));
            assertEquals(2, context.getBeansOfType(ThreadPoolExecutor.class).size());
        }
    }

    @Test
    @Timeout(5)
    public void blockedNotificationsLeaveQueryWorkersAvailable() throws Exception {
        ThreadPoolConfig configuration = new ThreadPoolConfig();
        ThreadPoolConfigProperties settings = new ThreadPoolConfigProperties();
        settings.setCorePoolSize(1); settings.setMaxPoolSize(1); settings.setBlockQueueSize(1);
        ThreadPoolExecutor queries = configuration.threadPoolExecutor(settings);
        ThreadPoolExecutor notifications = configuration.notifyThreadPoolExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            notifications.submit(() -> { release.await(); return null; });
            notifications.submit(() -> null);
            assertEquals(Integer.valueOf(42), queries.submit(() -> 42).get(1, TimeUnit.SECONDS));
            assertNotSame(queries, notifications);
        } finally {
            release.countDown(); notifications.shutdownNow(); queries.shutdownNow();
        }
    }
}
