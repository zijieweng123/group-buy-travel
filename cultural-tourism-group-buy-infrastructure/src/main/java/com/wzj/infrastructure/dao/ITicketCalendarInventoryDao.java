package com.wzj.infrastructure.dao;

import com.wzj.infrastructure.dao.po.TicketCalendarInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface ITicketCalendarInventoryDao {
    TicketCalendarInventory queryInventory(@Param("activityId") Long activityId,
                                           @Param("goodsId") String goodsId,
                                           @Param("travelDate") LocalDate travelDate);
}
