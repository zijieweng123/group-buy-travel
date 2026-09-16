package com.wzj.domain.trade.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * @author wzj
 * @date 2026-01-22 14:25
 * @description 拼团交易命令实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeLockRuleCommandEntity {

    /** 用户ID */
    private String userId;
    /** 活动ID */
    private Long activityId;
    /** 组队ID */
    private String teamId;
    /** 商品ID */
    private String goodsId;
    /** 外部交易号，作为请求幂等键 */
    private String outTradeNo;
    /** 游玩日期 */
    private LocalDate travelDate;
    /** 游客姓名 */
    private String visitorName;
    /** 证件类型 */
    private String visitorIdType;
    /** 证件号码 */
    private String visitorIdNo;
}
