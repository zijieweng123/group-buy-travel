package com.wzj.infrastructure.dao;

import com.wzj.infrastructure.dao.po.ScSkuActivity;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author wzj
 * @date 2026-01-13 13:56
 * @description 渠道商品活动配置关联表Dao
 */

@Mapper
public interface IScSkuActivityDao {

    ScSkuActivity querySCSkuActivityBySCGoodsId(ScSkuActivity scSkuActivityReq);
}
