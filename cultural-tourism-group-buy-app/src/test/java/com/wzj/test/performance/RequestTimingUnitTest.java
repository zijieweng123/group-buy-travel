package com.wzj.test.performance;

import com.wzj.types.observability.TradeRequestTiming;
import com.wzj.types.observability.TradeRequestTiming.Stage;
import com.wzj.infrastructure.observability.TimedDataSource;
import com.wzj.infrastructure.observability.TradeTimingConfiguration;
import com.wzj.infrastructure.redis.IRedisService;
import com.wzj.config.ThreadPoolConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.wzj.trigger.http.TradeTimingAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RequestTimingUnitTest {
    @AfterEach public void cleanup() { TradeRequestTiming.clear(); }
    @Test public void disabledTracingPreservesConnectionIdentity() throws Exception {
        DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        assertSame(connection,new TimedDataSource(source).getConnection());
        assertNull(TradeRequestTiming.current());
    }
    @Test public void jdbcProxyPreservesFailuresUnwrapAndTransactionCalls() throws Exception {
        DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
        PreparedStatement statement=mock(PreparedStatement.class); SQLException failure=new SQLException("test","40001");
        when(source.getConnection()).thenReturn(connection); when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(failure);
        TradeRequestTiming.Trace trace=TradeRequestTiming.start();
        Connection timed=new TimedDataSource(source).getConnection();
        assertSame(timed,timed.unwrap(Connection.class)); assertTrue(timed.isWrapperFor(Connection.class));
        assertSame(failure,assertThrows(SQLException.class,()->timed.prepareStatement("SELECT 1").executeQuery()));
        timed.setAutoCommit(false);timed.commit();timed.rollback();timed.close();
        verify(connection).commit(); verify(connection).rollback();verify(connection).close();verify(connection).setAutoCommit(false);
        assertEquals(1,trace.count(Stage.SQL));assertEquals(1,trace.count(Stage.COMMIT));
        assertEquals(1,trace.count(Stage.ROLLBACK));assertEquals(1,trace.count(Stage.POOL));
        assertEquals(1,trace.count(Stage.JDBC_STATE));
    }
    @Test @Timeout(5) public void asyncTaskRestoresReusedWorkerContextEvenOnFailure() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            assertNull(executor.submit(TradeRequestTiming::current).get());
            TradeRequestTiming.Trace trace=TradeRequestTiming.start();
            Callable<Integer> task=TradeRequestTiming.marketTask(()-> {
                assertSame(trace,TradeRequestTiming.current());
                return TradeRequestTiming.call(Stage.SQL,()-> { throw new SQLException("test"); });
            });
            assertThrows(ExecutionException.class,()->executor.submit(task).get());
            assertNull(executor.submit(TradeRequestTiming::current).get());
            assertEquals(1,trace.count(Stage.SQL));assertEquals(1,trace.count(Stage.MARKET_TASK));
            assertEquals(1,trace.count(Stage.MARKET_QUEUE));
        } finally { executor.shutdownNow(); }
    }
    @Test public void redisProxyPreservesResultsAndExceptions() {
        IRedisService original=mock(IRedisService.class); when(original.incr("test")).thenReturn(5L);
        IllegalStateException failure=new IllegalStateException("test"); when(original.getValue("test")).thenThrow(failure);
        IRedisService timed=TradeTimingConfiguration.timedRedis(original); TradeRequestTiming.Trace trace=TradeRequestTiming.start();
        assertEquals(5L,timed.incr("test"));
        assertSame(failure,assertThrows(IllegalStateException.class,()->timed.getValue("test")));
        assertEquals(2,trace.count(Stage.REDIS));
    }
    @Test public void notificationPoolGaugeReflectsConfiguredBound() {
        ThreadPoolConfig config=new ThreadPoolConfig();
        ThreadPoolExecutor executor=config.notifyThreadPoolExecutor(12,128);
        SimpleMeterRegistry registry=new SimpleMeterRegistry();
        try {
            config.notifyExecutorMetrics(executor).bindTo(registry);
            assertEquals(12d,registry.get("executor.pool.max").tag("name","trade.notify").gauge().value());
            assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class,executor.getRejectedExecutionHandler());
            assertEquals(128,executor.getQueue().remainingCapacity());
        } finally { registry.close(); executor.shutdownNow(); }
    }
    @Test public void adviceAddsHeaderWithoutChangingBusinessBody() throws Exception {
        Object body=new Object(); MockHttpServletResponse servlet=new MockHttpServletResponse();
        TradeTimingAdvice advice=new TradeTimingAdvice(); TradeRequestTiming.start();
        ServletServerHttpResponse response=new ServletServerHttpResponse(servlet);
        assertSame(body,advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,null,response));
        response.getBody();
        assertTrue(servlet.getHeader("Server-Timing").startsWith("app;dur="));
        TradeRequestTiming.clear(); MockHttpServletResponse disabled=new MockHttpServletResponse();
        advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,null,new ServletServerHttpResponse(disabled));
        assertNull(disabled.getHeader("Server-Timing"));
    }
}
