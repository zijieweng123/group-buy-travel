package com.wzj.domain.activity.service.discount;

import com.wzj.domain.activity.model.valobj.GroupBuyActivityDiscountVO;

import java.math.BigDecimal;

/**
 * @author wzj
 * @date 2026-01-09 15:07
 * @description 折扣计算服务
 */
public interface IDiscountCalculateService {

    /**
     *
     * @param userId 用户id
     * @param originalPrice 商品原始价格
     * @param groupBuyDiscount 折扣计划配置
     * @return 商品优惠价格
     */
    BigDecimal calculate(String userId, BigDecimal originalPrice, GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount);
}
