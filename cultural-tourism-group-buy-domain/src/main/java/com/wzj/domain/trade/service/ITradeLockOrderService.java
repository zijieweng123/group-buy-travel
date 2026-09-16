package com.wzj.domain.trade.service;

import com.wzj.domain.trade.model.entity.MarketPayOrderEntity;
import com.wzj.domain.trade.model.entity.PayActivityEntity;
import com.wzj.domain.trade.model.entity.PayDiscountEntity;
import com.wzj.domain.trade.model.entity.UserEntity;
import com.wzj.domain.trade.model.valobj.GroupBuyProgressVO;

/**
 * @author wzj
 * @date 2026-01-19 15:53
 * @description 拼团交易下单服务接口
 */
public interface ITradeLockOrderService {

    /**
     * 查询，未被支付消费完成的营销优惠订单
     *
     * @param userId     用户ID
     * @param outTradeNo 外部唯一单号
     * @return 拼团，预购订单营销实体对象
     */
    MarketPayOrderEntity queryNoPayMarketPayOrderByOutTradeNo(String userId, String outTradeNo);

    /**查询拼团进度
     *
     * @param teamId 拼团id
     * @return 进度
     */
    GroupBuyProgressVO queryGroupBuyProgressVO(String teamId);

    /**
     * 锁定，营销预支付订单；商品下单前，预购锁定。
     *
     * @param userEntity        用户根实体对象
     * @param payActivityEntity 拼团，支付活动实体对象
     * @param payDiscountEntity 拼团，支付优惠实体对象
     * @return 拼团，预购订单营销实体对象
     */
    MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception;
}
