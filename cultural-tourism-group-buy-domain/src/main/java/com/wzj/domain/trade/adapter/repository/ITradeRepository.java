package com.wzj.domain.trade.adapter.repository;

import com.wzj.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import com.wzj.domain.trade.model.aggregate.GroupBuyTeamSettlementAggregate;
import com.wzj.domain.trade.model.entity.GroupBuyActivityEntity;
import com.wzj.domain.trade.model.entity.TradeLockPrecheckEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeSettlementSnapshotEntity;
import com.wzj.domain.trade.model.entity.GroupBuyTeamEntity;
import com.wzj.domain.trade.model.entity.MarketPayOrderEntity;
import com.wzj.domain.trade.model.entity.NotifyTaskEntity;
import com.wzj.domain.trade.model.valobj.GroupBuyProgressVO;
import com.wzj.domain.trade.model.valobj.InventoryReservationResult;

import java.util.List;
import java.time.LocalDate;

/**
 * @author wzj
 * @date 2026-01-19 19:38
 * @description 交易仓储服务接口
 */
public interface ITradeRepository {

    MarketPayOrderEntity queryMarketPayOrderEntityByOutTradeNo(String userId, String outTradeNo);

    /** Null means the feature is disabled; a missing record is represented inside the snapshot. */
    TradeLockPrecheckEntity queryLockPrecheck(TradeLockRuleCommandEntity request);

    TradeSettlementSnapshotEntity querySettlementSnapshot(String userId, String outTradeNo);

    GroupBuyProgressVO queryGroupBuyProgress(String teamId);

    MarketPayOrderEntity lockMarketPayOrder(GroupBuyOrderAggregate groupBuyOrderAggregate);

    GroupBuyActivityEntity queryGroupBuyActivityEntityByActivityId(Long activityId);

    Integer queryOrderCountByActivityId(Long activityId, String userId);

    GroupBuyTeamEntity queryGroupBuyTeamByTeamId(String teamId);

    NotifyTaskEntity settlementMarketPayOrder(GroupBuyTeamSettlementAggregate groupBuyTeamSettlementAggregate);

    boolean isSCBlackIntercept(String source, String channel);

    List<NotifyTaskEntity> queryUnExecutedNotifyTaskList();

    List<NotifyTaskEntity> queryUnExecutedNotifyTaskList(String teamId);

    int updateNotifyTaskStatusSuccess(String teamId);

    int updateNotifyTaskStatusError(String teamId);

    int updateNotifyTaskStatusRetry(String teamId);

    boolean occupyTeamStock(String teamStockKey, String recoveryTeamStockKey, Integer target, Integer validTime);

    void recoveryTeamStock(String recoveryTeamStockKey, Integer validTime);

    InventoryReservationResult reserveRequestAndCalendar(String requestKey, String calendarKey, Integer capacity, Integer validTime);

    boolean acquireRequestLock(String requestLockKey, Integer validTime);

    void releaseRequestLock(String requestLockKey);

    boolean occupyCalendarStock(String calendarStockKey, Long activityId, String goodsId, LocalDate travelDate);

    boolean occupyCalendarStock(String calendarStockKey, Integer availableStock);

    void recoveryCalendarStock(String calendarStockKey);

}
