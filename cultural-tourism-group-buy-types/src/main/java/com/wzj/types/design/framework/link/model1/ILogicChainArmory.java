package com.wzj.types.design.framework.link.model1;

/**
 * @author wzj
 * @date 2026-01-21 16:51
 * @description 责任链装配
 */
public interface ILogicChainArmory<T, D, R> {

    ILogicLink<T, D, R> next();

    ILogicLink<T, D, R> appendNext(ILogicLink<T, D, R> next);

}
