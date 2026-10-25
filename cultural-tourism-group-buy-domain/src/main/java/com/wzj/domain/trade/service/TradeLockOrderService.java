package com.wzj.domain.trade.service;

import com.wzj.types.observability.TradeRequestTiming;
import com.wzj.types.observability.TradeRequestTiming.Stage;
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
        return TradeRequestTiming.call(Stage.LOOKUP, () -> repository.queryMarketPayOrderEntityByOutTradeNo(userId, outTradeNo));
    }

    @Override
    public GroupBuyProgressVO queryGroupBuyProgressVO(String teamId) {
        log.info("拼团交易-查询拼单进度:{}", teamId);
        return TradeRequestTiming.call(Stage.PROGRESS, () -> repository.queryGroupBuyProgress(teamId));
    }

    @Override
    public MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("拼团交易-锁定营销优惠支付订单:{} activityId:{} goodsId:{}", userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId());

        TradeLockRuleFilterFactory.DynamicContext context = new TradeLockRuleFilterFactory.DynamicContext();
        try {
            // The linked list advances iteratively. Filters store acquired keys in the context.
            TradeLockRuleFilterBackEntity result = TradeRequestTiming.call(Stage.RULES, () -> tradeRuleFilter.apply(TradeLockRuleCommandEntity.builder()
                    .activityId(payActivityEntity.getActivityId())
                    .userId(userEntity.getUserId())
                    .teamId(payActivityEntity.getTeamId())
                    .goodsId(payDiscountEntity.getGoodsId())
                    .outTradeNo(payDiscountEntity.getOutTradeNo())
                    .travelDate(payDiscountEntity.getTravelDate())
                    .visitorName(payDiscountEntity.getVisitorName())
                    .visitorIdType(payDiscountEntity.getVisitorIdType())
                    .visitorIdNo(payDiscountEntity.getVisitorIdNo())
                    .build(), context));

            GroupBuyOrderAggregate aggregate = GroupBuyOrderAggregate.builder()
                    .userEntity(userEntity)
                    .payActivityEntity(payActivityEntity)
                    .payDiscountEntity(payDiscountEntity)
                    .userTakeOrderCount(result.getUserTakeOrderCount())
                    .build();
            return repository.lockMarketPayOrder(aggregate);
        } catch (Exception failure) {
            compensate(() -> repository.recoveryTeamStock(context.getOccupiedRecoveryTeamStockKey(), payActivityEntity.getValidTime()), failure);
            compensate(() -> repository.recoveryCalendarStock(context.getOccupiedCalendarStockKey()), failure);
            compensate(() -> repository.releaseRequestLock(context.getAcquiredRequestLockKey()), failure);
            throw failure;
        }
    }

    private void compensate(Runnable recovery, Exception originalFailure) {
        try {
            recovery.run();
        } catch (Exception compensationFailure) {
            originalFailure.addSuppressed(compensationFailure);
        }
    }
}
