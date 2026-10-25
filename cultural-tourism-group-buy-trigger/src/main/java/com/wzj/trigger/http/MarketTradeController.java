package com.wzj.trigger.http;

import com.wzj.types.observability.TradeRequestTiming;
import com.wzj.types.observability.TradeRequestTiming.Stage;
import com.alibaba.fastjson.JSON;
import com.wzj.api.IMarketTradeService;
import com.wzj.api.dto.LockMarketPayOrderRequestDTO;
import com.wzj.api.dto.LockMarketPayOrderResponseDTO;
import com.wzj.api.dto.SettlementMarketPayOrderRequestDTO;
import com.wzj.api.dto.SettlementMarketPayOrderResponseDTO;
import com.wzj.api.response.Response;
import com.wzj.domain.activity.model.entity.MarketProductEntity;
import com.wzj.domain.activity.model.entity.TrialBalanceEntity;
import com.wzj.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.wzj.domain.activity.service.IIndexGroupBuyMarketService;
import com.wzj.domain.trade.model.entity.*;
import com.wzj.domain.trade.model.valobj.GroupBuyProgressVO;
import com.wzj.domain.trade.model.valobj.NotifyConfigVO;
import com.wzj.domain.trade.model.valobj.NotifyTypeEnumVO;
import com.wzj.domain.trade.service.ITradeLockOrderService;
import com.wzj.domain.trade.service.ITradeSettlementOrderService;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Objects;

/**
 * @author wzj
 * @date 2026-01-19 20:09
 * @description 营销交易服务
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/group-buy/orders")
public class MarketTradeController implements IMarketTradeService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;
    @Resource
    private ITradeLockOrderService tradeLockOrderService;
    @Resource
    private ITradeSettlementOrderService tradeSettlementOrderService;
    /**
     * 拼团营销锁单
     */
    @PostMapping
    @Override
    public Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(@RequestBody LockMarketPayOrderRequestDTO lockMarketPayOrderRequestDTO) {

        try{
            // 获得参数
            String userId = lockMarketPayOrderRequestDTO.getUserId();
            String source = lockMarketPayOrderRequestDTO.getSource();
            String channel = lockMarketPayOrderRequestDTO.getChannel();
            String goodsId = lockMarketPayOrderRequestDTO.getGoodsId();
            Long activityId = lockMarketPayOrderRequestDTO.getActivityId();
            String outTradeNo = lockMarketPayOrderRequestDTO.getOutTradeNo();
            String teamId = lockMarketPayOrderRequestDTO.getTeamId();
            LockMarketPayOrderRequestDTO.NotifyConfigVO notifyConfigVO = lockMarketPayOrderRequestDTO.getNotifyConfigVO();

            log.info("票务拼团锁单 userId:{} activityId:{} goodsId:{} travelDate:{} outTradeNo:{}",
                    userId, activityId, goodsId, lockMarketPayOrderRequestDTO.getTravelDate(), outTradeNo);

            // 校验参数是否为空
            if (StringUtils.isBlank(userId) || StringUtils.isBlank(source) || StringUtils.isBlank(channel)
                    || StringUtils.isBlank(goodsId) || StringUtils.isBlank(outTradeNo) || null == activityId
                    || lockMarketPayOrderRequestDTO.getTravelDate() == null || notifyConfigVO == null
                    || ("HTTP".equals(notifyConfigVO.getNotifyType()) && StringUtils.isBlank(notifyConfigVO.getNotifyUrl()))) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 查询未支付订单
            MarketPayOrderEntity marketPayOrderEntity = tradeLockOrderService.queryNoPayMarketPayOrderByOutTradeNo(userId, outTradeNo);
            if (marketPayOrderEntity != null) {
                LockMarketPayOrderResponseDTO lockMarketPayOrderResponseDTO = LockMarketPayOrderResponseDTO.builder()
                                .orderId(marketPayOrderEntity.getOrderId())
                                .originalPrice(marketPayOrderEntity.getOriginalPrice())
                                .deductionPrice(marketPayOrderEntity.getDeductionPrice())
                                .payPrice(marketPayOrderEntity.getPayPrice())
                                .tradeOrderStatus(marketPayOrderEntity.getTradeOrderStatusEnumVO().getCode())
                                .build();

                if (log.isInfoEnabled()) {
                    log.info("交易锁单记录(存在):{} marketPayOrderEntity:{}", userId, JSON.toJSONString(marketPayOrderEntity));
                }
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(lockMarketPayOrderResponseDTO)
                        .build();
            }

            // 判断拼团锁单是否完成了目标
            if (null != teamId){
                GroupBuyProgressVO groupBuyProgressVO = tradeLockOrderService.queryGroupBuyProgressVO(teamId);
                if (null != groupBuyProgressVO && Objects.equals(groupBuyProgressVO.getTargetCount(), groupBuyProgressVO.getLockCount())) {
                    log.info("交易锁单拦截-拼单目标已达成:{} {}", userId, teamId);
                    return Response.<LockMarketPayOrderResponseDTO>builder()
                            .code(ResponseCode.E0006.getCode())
                            .info(ResponseCode.E0006.getInfo())
                            .build();
                }
            }

            // 营销优惠试算
            TrialBalanceEntity trialBalanceEntity = TradeRequestTiming.call(Stage.MARKET, () -> indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                    .userId(userId)
                    .source(source)
                    .channel(channel)
                    .goodsId(goodsId)
                    .activityId(activityId)
                    .build()));

//            // 人群限定
//            if (!trialBalanceEntity.getIsVisible() || !trialBalanceEntity.getIsEnable()){
//                return Response.<LockMarketPayOrderResponseDTO>builder()
//                        .code(ResponseCode.E0007.getCode())
//                        .info(ResponseCode.E0007.getInfo())
//                        .build();
//            }


            GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();

            // 营销优惠锁单
            marketPayOrderEntity = tradeLockOrderService.lockMarketPayOrder(
                    UserEntity.builder().userId(userId).build(),
                    PayActivityEntity.builder()
                            .teamId(teamId)
                            .activityId(activityId)
                            .activityName(groupBuyActivityDiscountVO.getActivityName())
                            .startTime(groupBuyActivityDiscountVO.getStartTime())
                            .endTime(groupBuyActivityDiscountVO.getEndTime())
                            .validTime(groupBuyActivityDiscountVO.getValidTime())
                            .targetCount(groupBuyActivityDiscountVO.getTarget())
                            .build(),
                    PayDiscountEntity.builder()
                            .source(source)
                            .channel(channel)
                            .goodsId(goodsId)
                            .goodsName(trialBalanceEntity.getGoodsName())
                            .originalPrice(trialBalanceEntity.getOriginalPrice())
                            .deductionPrice(trialBalanceEntity.getDeductionPrice())
                            .payPrice(trialBalanceEntity.getPayPrice())
                            .outTradeNo(outTradeNo)
                            .travelDate(lockMarketPayOrderRequestDTO.getTravelDate())
                            .visitorName(lockMarketPayOrderRequestDTO.getVisitorName())
                            .visitorIdType(lockMarketPayOrderRequestDTO.getVisitorIdType())
                            .visitorIdNo(lockMarketPayOrderRequestDTO.getVisitorIdNo())
                            .notifyConfigVO(
                                    // 构建回调通知对象
                                    NotifyConfigVO.builder()
                                            .notifyType(NotifyTypeEnumVO.valueOf(notifyConfigVO.getNotifyType()))
                                            .notifyMQ(notifyConfigVO.getNotifyMQ())
                                            .notifyUrl(notifyConfigVO.getNotifyUrl())
                                            .build()
                                    )
                            .build());

            if (log.isInfoEnabled()) {
                log.info("交易锁单记录(新):{} marketPayOrderEntity:{}", userId, JSON.toJSONString(marketPayOrderEntity));
            }

            // 返回结果
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(LockMarketPayOrderResponseDTO.builder()
                            .orderId(marketPayOrderEntity.getOrderId())
                            .originalPrice(marketPayOrderEntity.getOriginalPrice())
                            .deductionPrice(marketPayOrderEntity.getDeductionPrice())
                            .payPrice(marketPayOrderEntity.getPayPrice())
                            .tradeOrderStatus(marketPayOrderEntity.getTradeOrderStatusEnumVO().getCode())
                            .build())
                    .build();

        }catch(AppException e){
            log.error("票务拼团锁单业务异常 userId:{} outTradeNo:{}", lockMarketPayOrderRequestDTO.getUserId(), lockMarketPayOrderRequestDTO.getOutTradeNo(), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        }catch (Exception e){
            log.error("票务拼团锁单服务失败 userId:{} outTradeNo:{}", lockMarketPayOrderRequestDTO.getUserId(), lockMarketPayOrderRequestDTO.getOutTradeNo(), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }

    }

    /**
     * 营销交易结算支付订单
     * @param requestDTO 结算商品信息
     * @return
     */
    @PutMapping("/settlement")
    @Override
    public Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(@RequestBody SettlementMarketPayOrderRequestDTO requestDTO) {
        try {
            log.info("营销交易组队结算开始:{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || null == requestDTO.getOutTradeTime()) {
                return Response.<SettlementMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 营销结算服务
            TradePaySettlementEntity tradePaySettlementEntity = tradeSettlementOrderService.settlementMarketPayOrder(TradePaySuccessEntity.builder()
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .userId(requestDTO.getUserId())
                    .outTradeNo(requestDTO.getOutTradeNo())
                    .outTradeTime(requestDTO.getOutTradeTime())
                    .build());

            SettlementMarketPayOrderResponseDTO responseDTO = SettlementMarketPayOrderResponseDTO.builder()
                    .userId(tradePaySettlementEntity.getUserId())
                    .teamId(tradePaySettlementEntity.getTeamId())
                    .activityId(tradePaySettlementEntity.getActivityId())
                    .outTradeNo(tradePaySettlementEntity.getOutTradeNo())
                    .build();

            // 返回结果
            Response<SettlementMarketPayOrderResponseDTO> response = Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            if (log.isInfoEnabled()) {
                log.info("营销交易组队结算完成:{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JSON.toJSONString(response));
            }

            return response;

        } catch (AppException e){
            log.error("营销交易组队结算异常:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e){
            log.error("营销交易组队结算失败:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
