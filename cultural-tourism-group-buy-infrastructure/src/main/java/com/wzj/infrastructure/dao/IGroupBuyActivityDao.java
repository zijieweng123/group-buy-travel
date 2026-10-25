package com.wzj.infrastructure.dao;

import com.wzj.domain.trade.model.entity.GroupBuyActivityEntity;
import com.wzj.infrastructure.dao.po.GroupBuyActivity;
import org.apache.ibatis.annotations.Mapper;
import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.infrastructure.dao.po.TradeLockPrecheck;

import java.util.List;

/**
 * @description 拼团活动Dao
 * @create 2026-1-07
 */
@Mapper
public interface IGroupBuyActivityDao {

    TradeLockPrecheck queryLockPrecheck(TradeLockRuleCommandEntity request);

    List<GroupBuyActivity> queryGroupBuyActivityList();

    GroupBuyActivity queryValidGroupBuyActivity(GroupBuyActivity groupBuyActivityReq);

    GroupBuyActivity queryValidGroupBuyActivityId(Long activityId);

    GroupBuyActivity queryGroupBuyActivityByActivityId(Long activityId);
}
