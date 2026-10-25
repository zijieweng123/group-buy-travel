package com.wzj.domain.trade.service.lock.factory;

import com.wzj.domain.trade.model.entity.GroupBuyActivityEntity;
import com.wzj.domain.trade.model.entity.TradeLockPrecheckEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.wzj.domain.trade.service.lock.filter.ActivityUsabilityRuleFilter;
import com.wzj.domain.trade.service.lock.filter.CalendarStockRuleFilter;
import com.wzj.domain.trade.service.lock.filter.IdentityVerificationRuleFilter;
import com.wzj.domain.trade.service.lock.filter.RequestIdempotencyRuleFilter;
import com.wzj.domain.trade.service.lock.filter.TeamStockOccupyRuleFilter;
import com.wzj.domain.trade.service.lock.filter.UserTakeLimitRuleFilter;
import com.wzj.types.design.framework.link.model2.LinkArmory;
import com.wzj.types.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * @author wzj
 * @date 2026-01-22 14:27
 * @description 交易规则过滤工厂
 */
@Slf4j
@Service
public class TradeLockRuleFilterFactory {

    @Bean("tradeRuleFilter")
    public BusinessLinkedList<TradeLockRuleCommandEntity, DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter(
            ActivityUsabilityRuleFilter activityUsabilityRuleFilter,
            IdentityVerificationRuleFilter identityVerificationRuleFilter,
            UserTakeLimitRuleFilter userTakeLimitRuleFilter,
            RequestIdempotencyRuleFilter requestIdempotencyRuleFilter,
            CalendarStockRuleFilter calendarStockRuleFilter,
            TeamStockOccupyRuleFilter teamStockOccupyRuleFilter) {

        // 组装链
        LinkArmory<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> linkArmory =
                new LinkArmory<>("交易规则过滤链",
                        activityUsabilityRuleFilter,
                        identityVerificationRuleFilter,
                        userTakeLimitRuleFilter,
                        requestIdempotencyRuleFilter,
                        calendarStockRuleFilter,
                        teamStockOccupyRuleFilter);

        // 链对象
        return linkArmory.getLogicLink();
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext{

        private String teamStockKey = "cultural_tourism_group_buy_team_stock_key_";
        private String calendarStockKey = "travel:calendar:stock:";
        private String requestLockKey = "travel:order:idempotent:";

        private GroupBuyActivityEntity groupBuyActivity;
        private TradeLockPrecheckEntity precheck;

        private Integer UserTakeOrderCount;

        // Record only successfully acquired resources for request-local compensation.
        private String acquiredRequestLockKey;
        private String occupiedCalendarStockKey;
        private String occupiedRecoveryTeamStockKey;

        public String generateTeamStockKey(String teamId) {
            if (StringUtils.isBlank(teamId)) return null;
            return teamStockKey + groupBuyActivity.getActivityId() + "_" + teamId;
        }

        public String generateRecoveryTeamStockKey(String teamId) {
            if (StringUtils.isBlank(teamId)) return null;
            return teamStockKey + groupBuyActivity.getActivityId() + "_" + teamId + "_recovery";
        }

        public String generateCalendarStockKey(Long activityId, String goodsId, java.time.LocalDate travelDate) {
            return calendarStockKey + activityId + ":" + goodsId + ":" + travelDate;
        }

        public String generateRequestLockKey(String userId, String outTradeNo) {
            return requestLockKey + userId + ":" + outTradeNo;
        }
    }
}
