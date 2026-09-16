package com.wzj.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author wzj
 * @date 2026-01-13 13:50
 * @description 渠道商品活动配置关联表
 */

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ScSkuActivity {

    /* 自增ID */
    private Long id;
    /* 渠道 */
    private String source;
    /* 来源 */
    private String channel;
    /* 活动ID */
    private Long activityId;
    /* 商品ID */
    private String goodsId;
    /* 创建时间 */
    private Date createTime;
    /* 更新时间 */
    private Date updateTime;
}
