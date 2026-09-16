package com.wzj.api;

import com.wzj.api.dto.LockMarketPayOrderRequestDTO;
import com.wzj.api.dto.LockMarketPayOrderResponseDTO;
import com.wzj.api.dto.SettlementMarketPayOrderRequestDTO;
import com.wzj.api.dto.SettlementMarketPayOrderResponseDTO;
import com.wzj.api.response.Response;

/**
 * @author wzj
 * @date 2026-01-19 20:05
 * @description 营销交易服务接口
 */
public interface IMarketTradeService {

    /**
     * 营销锁单方法
     * @param lockMarketPayOrderRequestDTO 锁单商品信息
     * @return 锁单结果信息
     */
    Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(LockMarketPayOrderRequestDTO lockMarketPayOrderRequestDTO);


    /**
     * 营销结算
     *
     * @param requestDTO 结算商品信息
     * @return 结算结果信息
     */
    Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(SettlementMarketPayOrderRequestDTO requestDTO);

}
