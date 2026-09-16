package com.wzj.domain.activity.model.valobj;

import lombok.*;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @author wzj
 * @date 2026-01-08 19:08
 * @description
 */

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkuVO {

    /** 商品ID */
    private String goodsId;
    /** 商品名称 */
    private String goodsName;
    /** 原始价格 */
    private BigDecimal originalPrice;


}
