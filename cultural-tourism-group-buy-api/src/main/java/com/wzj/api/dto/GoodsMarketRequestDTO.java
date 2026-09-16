package com.wzj.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author wzj
 * @date 2026-01-29 10:29
 * @description 商品营销请求对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GoodsMarketRequestDTO {

    // 用户ID
    private String userId;
    // 渠道
    private String source;
    // 来源
    private String channel;
    // 商品ID
    private String goodsId;
}
