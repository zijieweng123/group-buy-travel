package com.wzj.domain.trade.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One consistent read of the unpaid order and its team, never a cross-request cache. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TradeSettlementSnapshotEntity {
    private MarketPayOrderEntity order;
    private GroupBuyTeamEntity team;
}
