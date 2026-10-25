package com.wzj.domain.trade.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fresh, request-local read; a null stock means no active inventory for the selected date. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TradeLockPrecheckEntity {
    private GroupBuyActivityEntity activity;
    private Integer userOrderCount;
    private Integer availableStock;
}
