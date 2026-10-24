package com.wzj.domain.activity.service.trial.node;

import com.alibaba.fastjson.JSON;
import com.wzj.domain.activity.model.entity.MarketProductEntity;
import com.wzj.domain.activity.model.entity.TrialBalanceEntity;
import com.wzj.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.wzj.domain.activity.model.valobj.SkuVO;
import com.wzj.domain.activity.service.discount.IDiscountCalculateService;
import com.wzj.domain.activity.service.trial.AbstractGroupBuyMarketSupport;
import com.wzj.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import com.wzj.domain.activity.service.trial.thread.QueryGroupBuyActivityDiscountVOThreadTask;
import com.wzj.domain.activity.service.trial.thread.QuerySkuVOFromDBThreadTask;
import com.wzj.types.design.framework.tree.AbstractStrategyRouter;
import com.wzj.types.design.framework.tree.StrategyHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author wzj
 * @date 2026-01-08 14:38
 * @description
 */

@Service
@Slf4j
public class MarketNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> {

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Value("${trade.market-query-timeout-ms:3000}")
    private long queryTimeoutMillis = 3000;

    /**
     * <a href="https://bugstack.cn/md/road-map/spring-dependency-injection.html">Spring 注入详细说明</a>
     */
    @Resource
    private TagNode tagNode;

    @Resource
    private ErrorNode errorNode;

    @Resource
    private Map<String, IDiscountCalculateService> discountCalculateServicemap;

    @Override
    protected void multiThread(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 异步查询活动配置
        QueryGroupBuyActivityDiscountVOThreadTask queryGroupBuyActivityDiscountVOThreadTask = new QueryGroupBuyActivityDiscountVOThreadTask(requestParameter.getSource(), requestParameter.getChannel(), requestParameter.getGoodsId(), repository);
        FutureTask<GroupBuyActivityDiscountVO> groupBuyActivityDiscountVOFutureTask = new FutureTask<>(queryGroupBuyActivityDiscountVOThreadTask);
        QuerySkuVOFromDBThreadTask querySkuVOFromDBThreadTask =
                new QuerySkuVOFromDBThreadTask(requestParameter.getGoodsId(), repository);
        FutureTask<SkuVO> skuVOFutureTask = new FutureTask<>(querySkuVOFromDBThreadTask);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(queryTimeoutMillis);
        try {
            threadPoolExecutor.execute(groupBuyActivityDiscountVOFutureTask);
            threadPoolExecutor.execute(skuVOFutureTask);
            dynamicContext.setGroupBuyActivityDiscountVO(groupBuyActivityDiscountVOFutureTask.get(
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            dynamicContext.setSkuVO(skuVOFutureTask.get(
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            // Timeout or a failed sibling must not leave queued work behind.
            if (!groupBuyActivityDiscountVOFutureTask.isDone()) groupBuyActivityDiscountVOFutureTask.cancel(true);
            if (!skuVOFutureTask.isDone()) skuVOFutureTask.cancel(true);
        }

        log.info("拼团商品查询试算服务-MarketNode userId:{} 异步线程加载数据「GroupBuyActivityDiscountVO、SkuVO」完成", requestParameter.getUserId());
    }



    @Override
    public TrialBalanceEntity doApply(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("拼团商品查询试算服务-MarketNode userId:{} requestParameter:{}", requestParameter.getUserId(), JSON.toJSONString(requestParameter));

        // 从上下文中获取折扣配置
        GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = dynamicContext.getGroupBuyActivityDiscountVO();
        if (null == groupBuyActivityDiscountVO) {
            return router(requestParameter, dynamicContext);
        }

        GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount = groupBuyActivityDiscountVO.getGroupBuyDiscount();
        SkuVO skuVO = dynamicContext.getSkuVO();
        if (null == groupBuyDiscount || null == skuVO) {
            return router(requestParameter, dynamicContext);
        }

        // 优惠试算
        IDiscountCalculateService discountCalculateService = discountCalculateServicemap.get(groupBuyDiscount.getMarketPlan());
        if(null ==  discountCalculateService){
            throw new AppException(ResponseCode.E0001.getCode(), ResponseCode.E0001.getInfo());
        }

        // 计算最终支付价格
        BigDecimal payPrice = discountCalculateService.calculate(requestParameter.getUserId(), skuVO.getOriginalPrice(), groupBuyDiscount);
        // 将优惠价格和支付价格写入上下文
        dynamicContext.setDeductionPrice(skuVO.getOriginalPrice().subtract(payPrice));
        dynamicContext.setPayPrice(payPrice);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> get(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) {
        // 不存在配置的拼团活动，走异常节点
        if (null == dynamicContext.getGroupBuyActivityDiscountVO() || null == dynamicContext.getSkuVO() || null == dynamicContext.getDeductionPrice()) {
            return errorNode;
        }

        return tagNode;
    }
}
