package com.wzj.test.performance;

import com.wzj.domain.trade.adapter.repository.ITradeRepository;
import com.wzj.domain.trade.model.entity.*;
import com.wzj.domain.trade.model.valobj.InventoryReservationResult;
import com.wzj.domain.trade.service.TradeLockOrderService;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.domain.trade.service.lock.filter.RequestIdempotencyRuleFilter;
import com.wzj.domain.trade.service.lock.filter.CalendarStockRuleFilter;
import com.wzj.infrastructure.adapter.repository.TradeRepository;
import com.wzj.infrastructure.redis.IRedisService;
import com.wzj.types.design.framework.link.model2.chain.BusinessLinkedList;
import com.wzj.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AtomicInventoryUnitTest {
    private TradeLockRuleCommandEntity request() {
        return TradeLockRuleCommandEntity.builder().activityId(1L).goodsId("ticket").userId("user")
                .outTradeNo("123").travelDate(LocalDate.of(2026,11,1)).build();
    }
    private TradeLockRuleFilterFactory.DynamicContext context() {
        TradeLockRuleFilterFactory.DynamicContext context=new TradeLockRuleFilterFactory.DynamicContext();
        GroupBuyActivityEntity activity=GroupBuyActivityEntity.builder().activityId(1L).validTime(60).build();
        context.setGroupBuyActivity(activity);context.setPrecheck(new TradeLockPrecheckEntity(activity,0,9));return context;
    }
    private RequestIdempotencyRuleFilter requestFilter(ITradeRepository repository) {
        RequestIdempotencyRuleFilter filter=new RequestIdempotencyRuleFilter();
        ReflectionTestUtils.setField(filter,"repository",repository);return filter;
    }
    @Test public void combinedSuccessOwnsBothKeysAndCalendarIsNotCountedTwice() throws Exception {
        ITradeRepository repository=mock(ITradeRepository.class);TradeLockRuleCommandEntity request=request();
        TradeLockRuleFilterFactory.DynamicContext context=context();
        String key=context.generateRequestLockKey("user","123");
        String stock=context.generateCalendarStockKey(1L,"ticket",request.getTravelDate());
        when(repository.reserveRequestAndCalendar(key,stock,9,60)).thenReturn(InventoryReservationResult.ACQUIRED);
        requestFilter(repository).apply(request,context);
        CalendarStockRuleFilter calendar=new CalendarStockRuleFilter();ReflectionTestUtils.setField(calendar,"repository",repository);
        calendar.apply(request,context);
        assertEquals(key,context.getAcquiredRequestLockKey());assertEquals(stock,context.getOccupiedCalendarStockKey());
        verify(repository,never()).acquireRequestLock(anyString(),anyInt());
        verify(repository,never()).occupyCalendarStock(anyString(),any(Integer.class));
    }
    @Test public void rejectionDoesNotClaimResourcesOrFallThroughToSetNx() {
        for (InventoryReservationResult outcome:new InventoryReservationResult[]{InventoryReservationResult.DUPLICATE,InventoryReservationResult.EXHAUSTED}) {
            ITradeRepository repository=mock(ITradeRepository.class);TradeLockRuleFilterFactory.DynamicContext context=context();
            when(repository.reserveRequestAndCalendar(anyString(),anyString(),anyInt(),anyInt())).thenReturn(outcome);
            AppException error=assertThrows(AppException.class,()->requestFilter(repository).apply(request(),context));
            assertEquals(outcome==InventoryReservationResult.DUPLICATE?"E0109":"E0108",error.getCode());
            assertNull(context.getAcquiredRequestLockKey());assertNull(context.getOccupiedCalendarStockKey());
            verify(repository,never()).acquireRequestLock(anyString(),anyInt());
        }
    }
    @Test public void repositoryClampsLeaseAndDisabledFeatureAvoidsRemoteCalls() {
        TradeRepository repository=new TradeRepository();IRedisService redis=mock(IRedisService.class);
        ReflectionTestUtils.setField(repository,"redisService",redis);
        assertEquals(InventoryReservationResult.DISABLED,repository.reserveRequestAndCalendar("request","stock",5,0));
        verifyNoInteractions(redis);ReflectionTestUtils.setField(repository,"atomicReservationEnabled",true);
        when(redis.reserveRequestAndCalendar(eq("request"),eq("stock"),eq(-1L),eq(60000L),anyString())).thenReturn(1L,0L,-1L);
        assertEquals(InventoryReservationResult.ACQUIRED,repository.reserveRequestAndCalendar("request","stock",null,0));
        assertEquals(InventoryReservationResult.DUPLICATE,repository.reserveRequestAndCalendar("request","stock",null,0));
        assertEquals(InventoryReservationResult.EXHAUSTED,repository.reserveRequestAndCalendar("request","stock",null,0));
    }
    @Test @SuppressWarnings("unchecked") public void failedDatabaseWriteCompensatesBothCombinedResources() throws Exception {
        ITradeRepository repository=mock(ITradeRepository.class);TradeLockOrderService service=new TradeLockOrderService();
        BusinessLinkedList<TradeLockRuleCommandEntity,TradeLockRuleFilterFactory.DynamicContext,TradeLockRuleFilterBackEntity> rules=mock(BusinessLinkedList.class);
        ReflectionTestUtils.setField(service,"repository",repository);ReflectionTestUtils.setField(service,"tradeRuleFilter",rules);
        when(repository.reserveRequestAndCalendar(anyString(),anyString(),anyInt(),anyInt())).thenReturn(InventoryReservationResult.ACQUIRED);
        when(rules.apply(any(),any())).thenAnswer(call->{
            TradeLockRuleFilterFactory.DynamicContext context=call.getArgument(1);
            context.setGroupBuyActivity(context().getGroupBuyActivity());context.setPrecheck(context().getPrecheck());
            requestFilter(repository).apply(call.getArgument(0),context);
            return TradeLockRuleFilterBackEntity.builder().userTakeOrderCount(0).build();
        });
        RuntimeException failure=new RuntimeException("database failure");when(repository.lockMarketPayOrder(any())).thenThrow(failure);
        PayActivityEntity activity=PayActivityEntity.builder().activityId(1L).validTime(60).build();
        PayDiscountEntity discount=PayDiscountEntity.builder().goodsId("ticket").outTradeNo("123").travelDate(request().getTravelDate()).build();
        assertSame(failure,assertThrows(RuntimeException.class,()->service.lockMarketPayOrder(UserEntity.builder().userId("user").build(),activity,discount)));
        verify(repository).recoveryCalendarStock("travel:calendar:stock:1:ticket:2026-11-01");
        verify(repository).releaseRequestLock("travel:order:idempotent:user:123");
    }
}
