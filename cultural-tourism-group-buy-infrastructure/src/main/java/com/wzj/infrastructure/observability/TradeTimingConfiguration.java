package com.wzj.infrastructure.observability;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import javax.sql.DataSource;
import com.wzj.infrastructure.redis.IRedisService;
import com.wzj.types.observability.TradeRequestTiming;
import com.wzj.types.observability.TradeRequestTiming.Stage;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trade.trace.enabled", havingValue="true")
public class TradeTimingConfiguration {
    @Bean public static BeanPostProcessor timedDataSourcePostProcessor() { return new TimingPostProcessor(); }
    // Only blocking methods used by the trading path; obtaining a Redisson handle is not a round trip.
    private static final Set<String> REDIS_CALLS = new HashSet<>(Arrays.asList(
            "reserveRequestAndCalendar", "getValue", "setValue", "getAtomicLong", "setAtomicLong", "incr", "decr", "setNx", "remove", "isExists"));
    public static IRedisService timedRedis(IRedisService delegate) {
        return (IRedisService) Proxy.newProxyInstance(TradeTimingConfiguration.class.getClassLoader(),
                new Class<?>[]{IRedisService.class}, (self, method, args) -> {
            boolean timed = REDIS_CALLS.contains(method.getName());
            long started = timed ? System.nanoTime() : 0;
            try { return method.invoke(delegate, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            finally { if (timed) TradeRequestTiming.record(Stage.REDIS, System.nanoTime()-started); }
        });
    }
    private static class TimingPostProcessor implements BeanPostProcessor, Ordered {
        @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof DataSource && !(bean instanceof TimedDataSource)) return new TimedDataSource((DataSource)bean);
            if (bean instanceof IRedisService) return timedRedis((IRedisService)bean);
            return bean;
        }
    }
}
