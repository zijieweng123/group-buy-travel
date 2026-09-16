package com.wzj.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @author wzj
 * @date 2026-02-26 10:18
 * @description 消息发送
 */
@Slf4j
@Component
public class EventPublisher {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void publish(String routingKey, String message) {
        try {
            // 等待 broker ack；失败交给 notify_task 定时重试，避免误标记为已投递。
            kafkaTemplate.send(routingKey, message).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("发送MQ消息失败 team_success message:{}", message, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka message delivery failed", e);
        }
    }
}
