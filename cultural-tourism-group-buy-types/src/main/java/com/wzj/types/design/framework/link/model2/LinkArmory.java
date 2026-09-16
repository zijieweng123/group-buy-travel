package com.wzj.types.design.framework.link.model2;

import com.wzj.types.design.framework.link.model2.chain.BusinessLinkedList;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;

/**
 * @author wzj
 * @date 2026-01-21 17:38
 * @description 链路装配
 */
public class LinkArmory<T, D, R> {

    private final BusinessLinkedList<T, D, R> logicLink;

    @SafeVarargs
    public LinkArmory(String linkName, ILogicHandler<T, D, R>... logicHandlers) {
        logicLink = new BusinessLinkedList<>(linkName);
        for (ILogicHandler<T, D, R> logicHandler: logicHandlers){
            logicLink.add(logicHandler);
        }
    }

    public BusinessLinkedList<T, D, R> getLogicLink() {
        return logicLink;
    }

}
