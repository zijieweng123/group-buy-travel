package com.wzj.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TicketCalendarInventory {
    private Long id;
    private Long activityId;
    private String goodsId;
    private LocalDate travelDate;
    private Integer availableStock;
}
