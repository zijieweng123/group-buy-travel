package com.wzj.domain.activity.service.trial.node;

import com.wzj.domain.activity.model.entity.MarketProductEntity;
import com.wzj.domain.activity.model.entity.TrialBalanceEntity;
import com.wzj.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.wzj.domain.activity.service.trial.AbstractGroupBuyMarketSupport;
import com.wzj.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import com.wzj.types.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @author wzj
 * @date 2026-01-13 15:48
 * @description 人群标签接口
 * 过滤逻辑：
 * 1. 没配置`tag_id`，就是不筛选人群，所以用户全都可见可参与；配置了`tag_id`，就是要筛选人群
 * 2. 如果用户在人群标签中直接放行，可见可参与；不在此人群标签中要看`tag_scope`的配置
 * 3. 如果`tag_scope`为空就是不做限制，所以也全都可见可参与；如果`tag_scope`有值，则代表做限制
 * - 包含 "1" → 不可见（visible=false）
 * - 包含 "2" → 不可参与（enable=false）
 */
@Service
@Slf4j
public class TagNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> {


    @Resource
    private EndNode endNode;

    @Override
    protected TrialBalanceEntity doApply(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {

        // 从上下文中获取拼团活动配置
        GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = dynamicContext.getGroupBuyActivityDiscountVO();

        String tagId = groupBuyActivityDiscountVO.getTagId();
        boolean enable = groupBuyActivityDiscountVO.isEnable();
        boolean visible = groupBuyActivityDiscountVO.isVisible();

        // 人群标签配置为空，则走默认值
        if(tagId==null || tagId.isEmpty()){
            dynamicContext.setVisible(true);
            dynamicContext.setEnable(true);
            return router(requestParameter, dynamicContext);
        }

        // 是否在人群范围内；visible、enable 如果值为 ture 则表示没有配置拼团限制，那么就直接保证为 true 即可
        boolean isWithin = repository.isTagCrowdRange(tagId, requestParameter.getUserId());
        dynamicContext.setVisible(visible || isWithin);
        dynamicContext.setEnable(enable || isWithin);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> get(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) {
        return endNode;
    }
}
