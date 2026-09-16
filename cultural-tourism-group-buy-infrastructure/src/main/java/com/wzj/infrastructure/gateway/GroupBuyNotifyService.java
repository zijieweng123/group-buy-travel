package com.wzj.infrastructure.gateway;

import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @author wzj
 * @date 2026-01-28 14:33
 * @description 拼团回调服务
 */
@Slf4j
@Service
public class GroupBuyNotifyService {

    @Resource
    private OkHttpClient okHttpClient;

    /**
     *
     * @param apiUrl 要通知的目标地址
     * @param notifyRequestDTOJSON 要发送的数据内容，已经序列化成了 JSON 字符串
     * @return 目标接口返回的响应内容（String）
     * @throws Exception
     */
    public String groupBuyNotify(String apiUrl, String notifyRequestDTOJSON) throws Exception {
        try {
            // 1. 构建参数

            // 告诉 HTTP 协议，“我发给你的是 JSON 数据”
            MediaType mediaType = MediaType.parse("application/json");
            // 把入参的 JSON 字符串包装成 HTTP 的请求体
            RequestBody body = RequestBody.create(mediaType, notifyRequestDTOJSON);
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("content-type", "application/json")
                    .build();

            // 2. 调用接口
            Response response = okHttpClient.newCall(request).execute();

            // 3. 返回结果
            return response.body().string();
        } catch (Exception e) {
            log.error("拼团回调 HTTP 接口服务异常 {}", apiUrl, e);
            throw new AppException(ResponseCode.HTTP_EXCEPTION);
        }
    }
}
