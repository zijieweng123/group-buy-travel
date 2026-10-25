package com.wzj.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data @EqualsAndHashCode(callSuper=true)
public class TradeSettlementRead extends GroupBuyOrder {
    private String orderTeamId;
    private String orderId;
    private BigDecimal orderOriginalPrice;
    private BigDecimal orderDeductionPrice;
    private BigDecimal orderPayPrice;
    private Integer orderStatus;
}
