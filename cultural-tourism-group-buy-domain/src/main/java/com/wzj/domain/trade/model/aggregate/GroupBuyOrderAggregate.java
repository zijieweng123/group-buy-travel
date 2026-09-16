package com.wzj.domain.trade.model.aggregate;

import com.wzj.domain.trade.model.entity.PayActivityEntity;
import com.wzj.domain.trade.model.entity.PayDiscountEntity;
import com.wzj.domain.trade.model.entity.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author wzj
 * @date 2026-01-19 16:06
 * @description 拼团订单聚合对象；聚合可以理解用各个四肢、身体、头等组装出来一个人
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyOrderAggregate {

    /** 用户实体对象 */
    private UserEntity userEntity;
    /** 支付活动实体对象 */
    private PayActivityEntity payActivityEntity;
    /** 支付优惠实体对象 */
    private PayDiscountEntity payDiscountEntity;
    /** 用户拼单数量 */
    private Integer userTakeOrderCount;
}
