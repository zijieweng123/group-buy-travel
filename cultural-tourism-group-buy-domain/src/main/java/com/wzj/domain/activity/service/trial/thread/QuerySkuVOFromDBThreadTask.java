package com.wzj.domain.activity.service.trial.thread;

import com.wzj.domain.activity.adapter.repository.IActivityRepository;
import com.wzj.domain.activity.model.valobj.SkuVO;

import java.util.concurrent.Callable;

/**
 * @author wzj
 * @date 2026-01-08 19:42
 * @description
 */
public class QuerySkuVOFromDBThreadTask implements Callable<SkuVO>{

    private final String goodsId;

    private final IActivityRepository activityRepository;

    public QuerySkuVOFromDBThreadTask(String goodsId, IActivityRepository activityRepository) {
        this.goodsId = goodsId;
        this.activityRepository = activityRepository;
    }

    @Override
    public SkuVO call() throws Exception {
        return activityRepository.querySkuByGoodsId(goodsId);
    }
}
