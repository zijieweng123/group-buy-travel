package com.wzj.types.annotations;

import java.lang.annotation.*;

/**
 * @author wzj
 * @date 2026-01-14 16:48
 * @description 注解，动态配置中心标记
 */

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Documented
public @interface DCCValue {

    String value() default "";
}
