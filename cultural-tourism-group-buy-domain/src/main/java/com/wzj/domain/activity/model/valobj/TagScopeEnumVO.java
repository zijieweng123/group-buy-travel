package com.wzj.domain.activity.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @author wzj
 * @date 2026-01-13 15:50
 * @description 活动人群标签作用域范围枚举
 */

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum TagScopeEnumVO {

    VISIBLE(true,false,"是否可看见拼团"),
    ENABLE(true, false,"是否可参与拼团"),
    ;

    private Boolean allow;
    private Boolean refuse;
    private String desc;
}
