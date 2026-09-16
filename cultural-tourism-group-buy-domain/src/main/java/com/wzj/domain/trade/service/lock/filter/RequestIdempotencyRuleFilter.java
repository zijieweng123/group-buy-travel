package com.wzj.domain.trade.service.lock.filter;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/** 基于 SETNX 的同用户、同外部单号请求防重。 */
@Service
public class RequestIdempotencyRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {
    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity request, TradeLockRuleFilterFactory.DynamicContext context) throws Exception {
        if (StringUtils.isBlank(request.getOutTradeNo())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }
        String key = context.generateRequestLockKey(request.getUserId(), request.getOutTradeNo());
        if (!repository.acquireRequestLock(key, context.getGroupBuyActivity().getValidTime())) {
            throw new AppException(ResponseCode.E0109);
        }
        try {
            TradeLockRuleFilterBackEntity result = next(request, context);
            result.setRequestLockKey(key);
            return result;
        } catch (Exception e) {
            repository.releaseRequestLock(key);
            throw e;
        }
    }
}
