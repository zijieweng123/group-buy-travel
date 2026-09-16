package com.wzj.domain.activity.service.trial.node;

import com.alibaba.fastjson.JSON;
import com.wzj.domain.activity.model.entity.MarketProductEntity;
import com.wzj.domain.activity.model.entity.TrialBalanceEntity;
import com.wzj.domain.activity.service.trial.AbstractGroupBuyMarketSupport;
import com.wzj.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import com.wzj.types.design.framework.tree.StrategyHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author wzj
 * @date 2026-01-13 15:04
 * @description
 */

@Service
@Slf4j
public class ErrorNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> {


    @Override
    protected TrialBalanceEntity doApply(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("拼团商品查询试算服务-NoMarketNode userId:{} requestParameter:{}", requestParameter.getUserId(), JSON.toJSONString(requestParameter));

        // 无营销配置
        if (null == dynamicContext.getGroupBuyActivityDiscountVO() || null == dynamicContext.getSkuVO()) {
            log.info("商品无拼团营销配置 {}", requestParameter.getGoodsId());
            throw new AppException(ResponseCode.E0002.getCode(), ResponseCode.E0002.getInfo());
        }

        return TrialBalanceEntity.builder().build();
    }

    @Override
    public StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> get(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) {
        return defaultStrategyHandler;
    }
}
