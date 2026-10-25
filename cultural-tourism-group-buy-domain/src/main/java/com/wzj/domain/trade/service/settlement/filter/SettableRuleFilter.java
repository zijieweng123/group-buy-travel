package com.wzj.domain.trade.service.settlement.filter;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.entity.GroupBuyTeamEntity;
import com.wzj.domain.trade.model.entity.MarketPayOrderEntity;
import com.wzj.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
import com.wzj.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;

/**
 * @author wzj
 * @date 2026-01-27 11:46
 * @description 可结算规则过滤；交易时间
 */
@Slf4j
@Service
public class SettableRuleFilter implements ILogicHandler<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeSettlementRuleFilterBackEntity apply(TradeSettlementRuleCommandEntity requestParameter, TradeSettlementRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("结算规则过滤-有效时间校验{} outTradeNo:{}", requestParameter.getUserId(), requestParameter.getOutTradeNo());

        // 上下文；获取数据
        MarketPayOrderEntity marketPayOrderEntity = dynamicContext.getMarketPayOrderEntity();

        // 查询拼团对象
        GroupBuyTeamEntity groupBuyTeamEntity = dynamicContext.isTeamSnapshotLoaded()
                ? dynamicContext.getGroupBuyTeamEntity() : repository.queryGroupBuyTeamByTeamId(marketPayOrderEntity.getTeamId());
        if (groupBuyTeamEntity == null) throw new AppException(ResponseCode.E0104);

        // 外部交易时间 - 也就是用户支付完成的时间，这个时间要在拼团有效时间范围内
        Date outTradeTime = requestParameter.getOutTradeTime();

        // 判断，外部交易时间，要小于拼团结束时间。否则抛异常。
        if (!outTradeTime.before(groupBuyTeamEntity.getValidEndTime())) {
            log.error("订单交易时间不在拼团有效时间范围内");
            throw new AppException(ResponseCode.E0106);
        }

        // 设置上下文
        dynamicContext.setGroupBuyTeamEntity(groupBuyTeamEntity);

        return next(requestParameter, dynamicContext);
    }

}
