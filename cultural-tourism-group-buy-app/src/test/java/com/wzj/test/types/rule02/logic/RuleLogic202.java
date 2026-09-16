package com.wzj.test.types.rule02.logic;

import com.wzj.test.types.rule02.factory.Rule02TradeRuleFactory;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author wzj
 * @date 2026-01-21 17:01
 * @description
 */
@Slf4j
@Service
public class RuleLogic202 implements ILogicHandler<String, Rule02TradeRuleFactory.DynamicContext, XxxResponse> {

    public XxxResponse apply(String requestParameter, Rule02TradeRuleFactory.DynamicContext dynamicContext) throws Exception{

        log.info("link model02 RuleLogic202");

        return new XxxResponse("hi 小傅哥！");
    }

}
