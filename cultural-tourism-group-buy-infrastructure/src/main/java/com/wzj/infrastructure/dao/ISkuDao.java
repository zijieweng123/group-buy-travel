package com.wzj.infrastructure.dao;

import com.wzj.infrastructure.dao.po.Sku;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author wzj
 * @date 2026-01-08 16:54
 * @description
 */

@Mapper
public interface ISkuDao {

    Sku querySkuByGoodsId(String goodsId);
}
