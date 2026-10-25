package com.wzj.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data @EqualsAndHashCode(callSuper=true)
public class TradeLockPrecheck extends GroupBuyActivity {
    private Integer userOrderCount;
    private Integer availableStock;
}
