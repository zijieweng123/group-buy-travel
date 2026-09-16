package com.wzj.infrastructure.dcc;

import com.wzj.types.annotations.DCCValue;
import org.springframework.stereotype.Service;
import com.wzj.types.common.Constants;

import java.util.Arrays;
import java.util.List;

/**
 * @author wzj
 * @date 2026-01-14 16:55
 * @description 动态配置服务
 */

@Service
public class DCCService {

    /**
     * 降级开关 0关闭、1开启
     * 默认值：0
     */
    @DCCValue("downgradeSwitch:0")
    private String downgradeSwitch;

    /**
     * 人群切量开关
     * 默认值：100
     */
    @DCCValue("cutRange:100")
    private String cutRange;

    @DCCValue("scBlacklist:s02c02")
    private String scBlacklist;

    /**
     * 缓存开关 1关闭、0开启
     * 默认值：0
     */
    @DCCValue("cacheSwitch:0")
    private String cacheOpenSwitch;

    public boolean isDowngradeSwitch() {
        // downgradeSwitch = 1, 返回 true
        return "1".equals(downgradeSwitch);
    }

    public boolean isCutRange(String userId) {

        // 计算哈希码的绝对值
        int hasCode = Math.abs(userId.hashCode());

        // 获取最后两位
        int lastTwoDigits = hasCode % 100;

        // 判断是否在切量范围内
        if (lastTwoDigits <= Integer.parseInt(cutRange)) {
            return true;
        }

        return false;
    }

    /**
     * 判断黑名单拦截渠道，true 拦截、false 放行
     */
    public boolean isSCBlackIntercept(String source, String channel) {
        List<String> list = Arrays.asList(scBlacklist.split(Constants.SPLIT));
        return list.contains(source + channel);
    }

    /**
     * 缓存开启开关，true为开启，1为关闭
     */
    public boolean isCacheOpenSwitch(){
        return "0".equals(cacheOpenSwitch);
    }
}
