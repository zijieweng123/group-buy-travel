package com.wzj.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class ThreadPoolConfig {

    @Bean(destroyMethod = "shutdown")
    @Primary
    @ConditionalOnMissingBean(name = "threadPoolExecutor")
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolConfigProperties properties) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        // 实例化策略
        RejectedExecutionHandler handler;
        switch (properties.getPolicy()){
            case "AbortPolicy":
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
            case "DiscardPolicy":
                handler = new ThreadPoolExecutor.DiscardPolicy();
                break;
            case "DiscardOldestPolicy":
                handler = new ThreadPoolExecutor.DiscardOldestPolicy();
                break;
            case "CallerRunsPolicy":
                handler = new ThreadPoolExecutor.CallerRunsPolicy();
                break;
            default:
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
        }
        // 创建线程池
        return new ThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getBlockQueueSize()),
                Executors.defaultThreadFactory(),
                handler);
    }

    @Bean(name = "notifyThreadPoolExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "notifyThreadPoolExecutor")
    public ThreadPoolExecutor notifyThreadPoolExecutor(
            @Value("${trade.notify-threads:4}") int threads,
            @Value("${trade.notify-queue-capacity:128}") int capacity) {
        if (threads < 1 || capacity < 1) throw new IllegalArgumentException("Invalid notification pool capacity");
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                task -> new Thread(task, "trade-notify-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

}
