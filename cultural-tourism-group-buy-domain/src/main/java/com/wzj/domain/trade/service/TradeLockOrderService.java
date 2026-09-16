package com.wzj.domain.trade.service;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import com.wzj.domain.trade.model.entity.*;
import com.wzj.domain.trade.model.valobj.GroupBuyProgressVO;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.types.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @author wzj
 * @date 2026-01-19 19:36
 * @description 拼团交易锁单服务接口
 */
@Slf4j
@Service
public class TradeLockOrderService implements ITradeLockOrderService {

    @Resource
    private ITradeRepository repository;

    @Resource
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter;

    @Override
    public MarketPayOrderEntity queryNoPayMarketPayOrderByOutTradeNo(String userId, String outTradeNo) {
        log.info("拼团交易-查询未支付营销订单:{} outTradeNo:{}", userId, outTradeNo);
        return repository.queryMarketPayOrderEntityByOutTradeNo(userId, outTradeNo);
    }

    @Override
    public GroupBuyProgressVO queryGroupBuyProgressVO(String teamId) {
        log.info("拼团交易-查询拼单进度:{}", teamId);
        return repository.queryGroupBuyProgress(teamId);
    }

    @Override
    public MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("拼团交易-锁定营销优惠支付订单:{} activityId:{} goodsId:{}", userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId());

        // 交易规则过滤
        TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity = tradeRuleFilter.apply(TradeLockRuleCommandEntity.builder()
                        .activityId(payActivityEntity.getActivityId())
                        .userId(userEntity.getUserId())
                        .teamId(payActivityEntity.getTeamId())
                        .goodsId(payDiscountEntity.getGoodsId())
                        .outTradeNo(payDiscountEntity.getOutTradeNo())
                        .travelDate(payDiscountEntity.getTravelDate())
                        .visitorName(payDiscountEntity.getVisitorName())
                        .visitorIdType(payDiscountEntity.getVisitorIdType())
                        .visitorIdNo(payDiscountEntity.getVisitorIdNo())
                        .build(),
                new TradeLockRuleFilterFactory.DynamicContext());

        // 已参与拼团量 - 用于构建数据库唯一索引使用，确保用户只能在一个活动上参与固定的次数
        Integer userTakeOrderCount = tradeLockRuleFilterBackEntity.getUserTakeOrderCount();

        // 构建聚合对象
        GroupBuyOrderAggregate groupBuyOrderAggregate = GroupBuyOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(userTakeOrderCount)
                .build();

        try {
            // 锁定聚合订单 - 这会用户只是下单还没有支付。后续会有2个流程；支付成功、超时未支付（回退）
            return repository.lockMarketPayOrder(groupBuyOrderAggregate);
        } catch (Exception e) {
            // 记录失败恢复量
            repository.recoveryTeamStock(tradeLockRuleFilterBackEntity.getRecoveryTeamStockKey(), payActivityEntity.getValidTime());
            repository.recoveryCalendarStock(tradeLockRuleFilterBackEntity.getCalendarStockKey());
            repository.releaseRequestLock(tradeLockRuleFilterBackEntity.getRequestLockKey());
            throw e;
        }
    }
}
