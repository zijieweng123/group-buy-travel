package com.wzj.domain.activity.model.valobj;

import com.wzj.types.common.Constants;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.Objects;

/**
 * @author wzj
 * @date 2026-01-08 16:43
 * @description
 */

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyActivityDiscountVO {

    /**
     * 活动ID
     */
    private Long activityId;
    /**
     * 活动名称
     */
    private String activityName;
    /**
     * 折扣配置
     */
    private GroupBuyDiscount groupBuyDiscount;
    /**
     * 拼团方式（0自动成团、1达成目标拼团）
     */
    private Integer groupType;
    /**
     * 拼团次数限制
     */
    private Integer takeLimitCount;
    /**
     * 拼团目标
     */
    private Integer target;
    /**
     * 拼团时长（分钟）
     */
    private Integer validTime;
    /**
     * 活动状态（0创建、1生效、2过期、3废弃）
     */
    private Integer status;
    /**
     * 活动开始时间
     */
    private Date startTime;
    /**
     * 活动结束时间
     */
    private Date endTime;
    /**
     * 人群标签规则标识
     */
    private String tagId;
    /**
     * 人群标签规则范围
     */
    private String tagScope;

    /**
     * 可见限制
     * 只要存在这样一个值，那么首次获得的默认值就是 false
     * 包含 "1" → 不可见（visible=false）
     */
    public boolean isVisible() {
        // 1. 切割字符串
        String[] split = this.tagScope.split(Constants.SPLIT);

        // 2. 检查限制条件
        // 条件：数组不为空 && 第一个元素是 "1" && 第一个元素不是空白
        if (split.length > 0 && Objects.equals(split[0], "1") && StringUtils.isNotBlank(split[0])) {
            return false;
        }
        return true;
    }

    /**
     * 参与限制
     * 只要存在这样一个值，那么首次获得的默认值就是 false
     * 包含 "2" → 不可参与（enable=false）
     */
    public boolean isEnable() {
        // 1. 切割字符串
        String[] split = this.tagScope.split(Constants.SPLIT);

        // 2. 检查限制条件
        // 条件：数组长度必须严格等于 2 && 第二个元素是 "2"
        if (split.length == 2 && Objects.equals(split[1], "2") && StringUtils.isNotBlank(split[1])) {
            return false;
        }
        if (split.length == 1 && Objects.equals(split[0], "2")) {
            return TagScopeEnumVO.ENABLE.getRefuse();
        }
        return true;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GroupBuyDiscount {

        /**
         * 折扣标题
         */
        private String discountName;

        /**
         * 折扣描述
         */
        private String discountDesc;

        /**
         * 折扣类型（0:base、1:tag）
         */
        private DiscountTypeEnum discountType;

        /**
         * 营销优惠计划（ZJ:直减、MJ:满减、N元购）
         */
        private String marketPlan;

        /**
         * 营销优惠表达式
         */
        private String marketExpr;

        /**
         * 人群标签，特定优惠限定
         */
        private String tagId;
    }
}
