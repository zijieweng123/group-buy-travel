package com.wzj.api;

import com.wzj.api.response.Response;

/**
 * @author wzj
 * @date 2026-01-14 20:07
 * @description DCC 动态配置中心
 */
public interface IDCCService {

    Response<Boolean> updateConfig(String key, String value);
}
