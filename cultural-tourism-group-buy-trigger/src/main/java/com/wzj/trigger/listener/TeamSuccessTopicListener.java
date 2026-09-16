package com.wzj.trigger.listener;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @author wzj
 * @date 2026-02-26 10:19
 * @description 结算完成消息监听
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {
    @Resource
    private RedissonClient redissonClient;

    @KafkaListener(topics = "${spring.kafka.topics.team-success}", groupId = "ticket-issuing-service")
    public void listener(String message) {
        JSONObject payload = JSON.parseObject(message);
        String teamId = payload.getString("teamId");
        if (!redissonClient.getBucket("travel:kafka:consumed:team-success:" + teamId)
                .trySet("consumed", 7, TimeUnit.DAYS)) {
            log.info("忽略重复的成团消息 teamId:{}", teamId);
            return;
        }
        // 真实项目中此处调用出票防腐层；teamId 是业务幂等键。
        log.info("接收消息（组队成功）:{}", message);
    }

}
