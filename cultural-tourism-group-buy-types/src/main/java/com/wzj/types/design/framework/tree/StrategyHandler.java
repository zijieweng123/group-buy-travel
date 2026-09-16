package com.wzj.types.design.framework.tree;

/**
 * @author wzj
 * @date 2026-01-08 13:37
 * @description 策略处理器
 */
public interface StrategyHandler<T, D, R> {

    StrategyHandler DEFAULT = (T, D) -> null;

    /**
     * StrategyHandler#apply 受理执行的业务流程。
     * 每个业务流程执行时，如果有数据是从前面节点到后面节点要使用的，那么可以填充到 dynamicContext 上下文中。
     * @param requestParameter
     * @param dynamicContext
     * @return
     * @throws Exception
     */
    R apply(T requestParameter, D dynamicContext) throws Exception;
}
