package com.wzj.domain.activity.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @author wzj
 * @date 2026-01-13 14:08
 * @description
 */

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SCSkuActivityVO {

    /* 渠道 */
    private String source;
    /* 来源 */
    private String channel;
    /* 活动ID */
    private Long activityId;
    /* 商品ID */
    private String goodsId;
}
