package com.wzj.domain.activity.service.trial.factory;

import com.wzj.domain.activity.model.entity.MarketProductEntity;
import com.wzj.domain.activity.model.entity.TrialBalanceEntity;
import com.wzj.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.wzj.domain.activity.model.valobj.SkuVO;
import com.wzj.domain.activity.service.trial.node.RootNode;
import com.wzj.types.design.framework.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * @author wzj
 * @date 2026-01-08 14:23
 * @description 活动策略工厂
 */

@Service
public class DefaultActivityStrategyFactory {

    private final RootNode rootNode;

    public DefaultActivityStrategyFactory(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> strategyHandler(){
        return  rootNode;
    }


    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext{

        // 拼团活动营销配置值对象
        private GroupBuyActivityDiscountVO groupBuyActivityDiscountVO;
        // 商品信息
        private SkuVO skuVO;
        // 优惠价格
        private BigDecimal deductionPrice;
        // 支付价格
        private BigDecimal payPrice;
        // 可见限制
        private boolean isVisible;
        // 参与限制
        private boolean isEnable;
    }
}
