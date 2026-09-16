package com.wzj.domain.trade.adapter.port;

import com.wzj.domain.trade.model.entity.NotifyTaskEntity;

/**
 * @author wzj
 * @date 2026-01-28 14:28
 * @description 交易接口服务接口
 */
public interface ITradePort {

    String groupBuyNotify(NotifyTaskEntity notifyTask) throws Exception;

}
