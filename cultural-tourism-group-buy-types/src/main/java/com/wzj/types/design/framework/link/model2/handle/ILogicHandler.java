package com.wzj.types.design.framework.link.model2.handle;

/**
 * @author wzj
 * @date 2026-01-21 17:07
 * @description 逻辑处理器
 */
public interface ILogicHandler<T, D, R> {

    default R next(T requestParameter, D dynamicContext) {
        return null;
    }

    R apply(T requestParameter, D dynamicContext) throws Exception;

}
