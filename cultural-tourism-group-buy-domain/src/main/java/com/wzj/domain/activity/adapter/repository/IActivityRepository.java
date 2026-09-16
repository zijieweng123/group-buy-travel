package com.wzj.domain.activity.adapter.repository;

import com.wzj.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.wzj.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.wzj.domain.activity.model.valobj.SCSkuActivityVO;
import com.wzj.domain.activity.model.valobj.SkuVO;
import com.wzj.domain.activity.model.valobj.TeamStatisticVO;

import java.util.List;

/**
 * @author wzj
 * @date 2026-01-08 16:41
 * @description
 */
public interface IActivityRepository {

    GroupBuyActivityDiscountVO queryGroupBuyActivityDiscountVO(Long activityId);

    SkuVO querySkuByGoodsId(String goodsId);

    SCSkuActivityVO querySCSkuActivityBySCGoodsId(String source, String channel, String goodsId);

    boolean isTagCrowdRange(String tagId, String userId);

    boolean downgradeSwitch();

    boolean cutRange(String userId);

    List<UserGroupBuyOrderDetailEntity> queryInProgressUserGroupBuyOrderDetailListByOwner(Long activityId, String userId, Integer ownerCount);

    List<UserGroupBuyOrderDetailEntity> queryInProgressUserGroupBuyOrderDetailListByRandom(Long activityId, String userId, Integer randomCount);

    TeamStatisticVO queryTeamStatisticByActivityId(Long activityId);
}
