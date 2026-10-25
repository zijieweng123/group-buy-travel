package com.wzj.test.performance;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.entity.*;
import com.wzj.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.domain.trade.service.lock.filter.*;
import com.wzj.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.wzj.domain.trade.service.settlement.filter.*;
import com.wzj.infrastructure.adapter.repository.TradeRepository;
import com.wzj.infrastructure.dao.*;
import com.wzj.infrastructure.dao.po.*;
import com.wzj.infrastructure.redis.IRedisService;
import com.wzj.types.enums.ActivityStatusEnumVO;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TradeReadSnapshotUnitTest {
    private TradeLockRuleCommandEntity request() {
        return TradeLockRuleCommandEntity.builder().activityId(1L).userId("user").goodsId("ticket")
                .travelDate(LocalDate.of(2026,11,1)).outTradeNo("123").build();
    }
    private GroupBuyActivityEntity activity() {
        return GroupBuyActivityEntity.builder().activityId(1L).status(ActivityStatusEnumVO.EFFECTIVE)
                .takeLimitCount(3).startTime(new Date(System.currentTimeMillis()-60000))
                .endTime(new Date(System.currentTimeMillis()+60000)).build();
    }
    private <T> T filter(T filter, ITradeRepository repository) {
        ReflectionTestUtils.setField(filter,"repository",repository);return filter;
    }
    private void code(ResponseCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected.getCode(),assertThrows(AppException.class,action).getCode());
    }
    @Test public void snapshotFeedsActivityLimitAndDateInventoryWithoutExtraReads() throws Exception {
        ITradeRepository repository=mock(ITradeRepository.class);TradeLockRuleCommandEntity request=request();
        TradeLockPrecheckEntity snapshot=new TradeLockPrecheckEntity(activity(),2,9);
        when(repository.queryLockPrecheck(request)).thenReturn(snapshot);
        TradeLockRuleFilterFactory.DynamicContext context=new TradeLockRuleFilterFactory.DynamicContext();
        String key=context.generateCalendarStockKey(1L,"ticket",request.getTravelDate());
        when(repository.occupyCalendarStock(key,9)).thenReturn(true);
        filter(new ActivityUsabilityRuleFilter(),repository).apply(request,context);
        filter(new UserTakeLimitRuleFilter(),repository).apply(request,context);
        filter(new CalendarStockRuleFilter(),repository).apply(request,context);
        assertEquals(2,context.getUserTakeOrderCount());assertEquals(key,context.getOccupiedCalendarStockKey());
        verify(repository,never()).queryGroupBuyActivityEntityByActivityId(anyLong());
        verify(repository,never()).queryOrderCountByActivityId(anyLong(),anyString());
        verify(repository,never()).occupyCalendarStock(anyString(),anyLong(),anyString(),any());
    }
    @Test public void missingOrInactiveActivityCannotPassTheCombinedRead() {
        ITradeRepository repository=mock(ITradeRepository.class);TradeLockRuleCommandEntity request=request();
        when(repository.queryLockPrecheck(request)).thenReturn(new TradeLockPrecheckEntity());
        ActivityUsabilityRuleFilter filter=filter(new ActivityUsabilityRuleFilter(),repository);
        code(ResponseCode.E0101,()->filter.apply(request,new TradeLockRuleFilterFactory.DynamicContext()));
    }
    @Test public void combinedUserCountStillEnforcesParticipationLimit() {
        ITradeRepository repository=mock(ITradeRepository.class);
        TradeLockRuleFilterFactory.DynamicContext context=new TradeLockRuleFilterFactory.DynamicContext();
        context.setGroupBuyActivity(activity());context.setPrecheck(new TradeLockPrecheckEntity(activity(),3,10));
        code(ResponseCode.E0103,()->filter(new UserTakeLimitRuleFilter(),repository).apply(request(),context));
        verify(repository,never()).queryOrderCountByActivityId(anyLong(),anyString());
    }
    @Test public void disabledFeatureKeepsTheOriginalReadSequence() throws Exception {
        ITradeRepository repository=mock(ITradeRepository.class);TradeLockRuleCommandEntity request=request();
        when(repository.queryGroupBuyActivityEntityByActivityId(1L)).thenReturn(activity());
        when(repository.queryOrderCountByActivityId(1L,"user")).thenReturn(1);
        when(repository.occupyCalendarStock(anyString(),eq(1L),eq("ticket"),eq(request.getTravelDate()))).thenReturn(true);
        TradeLockRuleFilterFactory.DynamicContext context=new TradeLockRuleFilterFactory.DynamicContext();
        filter(new ActivityUsabilityRuleFilter(),repository).apply(request,context);
        filter(new UserTakeLimitRuleFilter(),repository).apply(request,context);
        filter(new CalendarStockRuleFilter(),repository).apply(request,context);
        verify(repository).queryGroupBuyActivityEntityByActivityId(1L);
        verify(repository).queryOrderCountByActivityId(1L,"user");
        verify(repository).occupyCalendarStock(anyString(),eq(1L),eq("ticket"),eq(request.getTravelDate()));
    }
    @Test public void invalidDateStockDoesNotTouchRedisAndOverflowIsCompensated() {
        TradeRepository repository=new TradeRepository();IRedisService redis=mock(IRedisService.class);
        ReflectionTestUtils.setField(repository,"redisService",redis);
        assertFalse(repository.occupyCalendarStock("date",(Integer)null));
        assertFalse(repository.occupyCalendarStock("date",0));assertFalse(repository.occupyCalendarStock("date",-1));
        verifyNoInteractions(redis);
        when(redis.incr("date")).thenReturn(6L);assertFalse(repository.occupyCalendarStock("date",5));
        verify(redis).decr("date");
    }
    @Test public void repositoryFeatureOffDoesNotQueryTheNewMappers() {
        TradeRepository repository=new TradeRepository();IGroupBuyActivityDao activity=mock(IGroupBuyActivityDao.class);
        IGroupBuyOrderDao teams=mock(IGroupBuyOrderDao.class);
        ReflectionTestUtils.setField(repository,"groupBuyActivityDao",activity);
        ReflectionTestUtils.setField(repository,"groupBuyOrderDao",teams);
        assertNull(repository.queryLockPrecheck(request()));assertNull(repository.querySettlementSnapshot("user","123"));
        verifyNoInteractions(activity,teams);
    }
    @Test public void repositoryMapsOrderPricesSeparatelyFromTeamFields() {
        TradeRepository repository=new TradeRepository();IGroupBuyOrderDao teams=mock(IGroupBuyOrderDao.class);
        ReflectionTestUtils.setField(repository,"groupBuyOrderDao",teams);
        ReflectionTestUtils.setField(repository,"readSnapshotsEnabled",true);
        ReflectionTestUtils.setField(repository,"topic_team_success","test-topic");
        TradeSettlementRead row=new TradeSettlementRead();row.setOrderTeamId("team");row.setTeamId("team");
        row.setOrderId("order");row.setOrderStatus(0);row.setStatus(0);row.setNotifyType("MQ");
        row.setOrderPayPrice(new BigDecimal("12"));row.setPayPrice(new BigDecimal("99"));
        when(teams.querySettlementSnapshot("user","123")).thenReturn(row);
        TradeSettlementSnapshotEntity snapshot=repository.querySettlementSnapshot("user","123");
        assertEquals(new BigDecimal("12"),snapshot.getOrder().getPayPrice());
        assertEquals("test-topic",snapshot.getTeam().getNotifyConfigVO().getNotifyMQ());
        assertEquals(TradeOrderStatusEnumVO.CREATE,snapshot.getOrder().getTradeOrderStatusEnumVO());
    }
    @Test public void joinedTeamIsReusedAndExpiredSettlementIsRejected() throws Exception {
        ITradeRepository repository=mock(ITradeRepository.class);
        GroupBuyTeamEntity team=GroupBuyTeamEntity.builder().teamId("team")
                .validEndTime(new Date(System.currentTimeMillis()+60000)).build();
        MarketPayOrderEntity order=MarketPayOrderEntity.builder().teamId("team").build();
        when(repository.querySettlementSnapshot("user","123")).thenReturn(new TradeSettlementSnapshotEntity(order,team));
        TradeSettlementRuleCommandEntity request=TradeSettlementRuleCommandEntity.builder()
                .userId("user").outTradeNo("123").outTradeTime(new Date()).build();
        TradeSettlementRuleFilterFactory.DynamicContext context=new TradeSettlementRuleFilterFactory.DynamicContext();
        filter(new OutTradeNoRuleFilter(),repository).apply(request,context);
        SettableRuleFilter filter=filter(new SettableRuleFilter(),repository);filter.apply(request,context);
        verify(repository,never()).queryGroupBuyTeamByTeamId(anyString());
        verify(repository,never()).queryMarketPayOrderEntityByOutTradeNo(anyString(),anyString());
        request.setOutTradeTime(new Date(team.getValidEndTime().getTime()+1));
        code(ResponseCode.E0106,()->filter.apply(request,context));
    }
    @Test public void absentUnpaidOrderCannotFallBackAndAcceptARepeatSettlement() {
        ITradeRepository repository=mock(ITradeRepository.class);
        when(repository.querySettlementSnapshot("user","123")).thenReturn(new TradeSettlementSnapshotEntity());
        TradeSettlementRuleCommandEntity request=TradeSettlementRuleCommandEntity.builder().userId("user").outTradeNo("123").build();
        code(ResponseCode.E0104,()->filter(new OutTradeNoRuleFilter(),repository).apply(request,new TradeSettlementRuleFilterFactory.DynamicContext()));
        verify(repository,never()).queryMarketPayOrderEntityByOutTradeNo(anyString(),anyString());
    }
}
