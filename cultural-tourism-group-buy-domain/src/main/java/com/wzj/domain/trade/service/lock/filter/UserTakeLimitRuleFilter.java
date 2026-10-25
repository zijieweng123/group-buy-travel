package com.wzj.domain.trade.service.lock.filter;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.entity.GroupBuyActivityEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @author wzj
 * @date 2026-01-22 14:30
 * @description 用户参与限制，规则过滤
 */
@Slf4j
@Service
public class UserTakeLimitRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("交易规则过滤-用户参与次数校验{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        GroupBuyActivityEntity groupBuyActivity = dynamicContext.getGroupBuyActivity();

        // 查询用户在一个拼团活动上参与的次数
        Integer count = dynamicContext.getPrecheck() == null
                ? repository.queryOrderCountByActivityId(requestParameter.getActivityId(), requestParameter.getUserId())
                : dynamicContext.getPrecheck().getUserOrderCount();

        if (null != groupBuyActivity.getTakeLimitCount() && count >= groupBuyActivity.getTakeLimitCount()) {
            log.info("用户参与次数校验，已达可参与上限 activityId:{}", requestParameter.getActivityId());
            throw new AppException(ResponseCode.E0103);
        }

        dynamicContext.setUserTakeOrderCount(count);

        // 走到下一个责任链节点
        return next(requestParameter, dynamicContext);

    }
}
