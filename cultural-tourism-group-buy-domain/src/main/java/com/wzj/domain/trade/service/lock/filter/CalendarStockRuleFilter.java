package com.wzj.domain.trade.service.lock.filter;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/** 按活动、票种和游玩日期隔离库存。 */
@Service
public class CalendarStockRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {
    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity request, TradeLockRuleFilterFactory.DynamicContext context) throws Exception {
        String key = context.generateCalendarStockKey(request.getActivityId(), request.getGoodsId(), request.getTravelDate());
        if (!repository.occupyCalendarStock(key, request.getActivityId(), request.getGoodsId(), request.getTravelDate())) {
            throw new AppException(ResponseCode.E0108);
        }
        context.setOccupiedCalendarStockKey(key);
        return next(request, context);
    }
}
