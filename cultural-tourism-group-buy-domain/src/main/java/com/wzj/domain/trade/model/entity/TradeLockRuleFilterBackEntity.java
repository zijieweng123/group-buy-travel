package com.wzj.domain.trade.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author wzj
 * @date 2026-01-22 14:26
 * @description 拼团交易，过滤反馈实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeLockRuleFilterBackEntity {

    // 用户参与活动的订单量
    private Integer userTakeOrderCount;

    // 恢复组队库存缓存key
    private String recoveryTeamStockKey;

    // 日历库存缓存 key，落单失败时用于释放
    private String calendarStockKey;

    // 请求防重 key，落单失败时释放，允许客户端安全重试
    private String requestLockKey;
}
