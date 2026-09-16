package com.wzj.api;

import com.wzj.api.dto.GoodsMarketRequestDTO;
import com.wzj.api.dto.GoodsMarketResponseDTO;
import com.wzj.api.response.Response;

/**
 * @author wzj
 * @date 2026-01-29 10:29
 * @description 营销首页服务接口
 */
public interface IMarketIndexService {

    /**
     * 查询拼团营销配置
     *
     * @param goodsMarketRequestDTO 营销商品信息
     * @return 营销配置信息
     */
    Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfig(GoodsMarketRequestDTO goodsMarketRequestDTO);

}
