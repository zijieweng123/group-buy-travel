package com.wzj.config;

import com.wzj.types.annotations.DCCValue;
import com.wzj.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * @author wzj
 * @date 2026-01-14 17:03
 * @description 基于 Redis 实现动态配置中心
 */

@Slf4j
@Configuration
public class DCCValueBeanFactory implements BeanPostProcessor {

    private static final String BASE_CONFIG_PATH = "cultural_tourism_group_buy_dcc_";

    private final RedissonClient redissonClient;

    private final Map<String, Object> dccObjGroup = new HashMap<>();

    public DCCValueBeanFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }


    @Bean("dccTopic")
    public RTopic dccRedisTopicListener(RedissonClient redissonClient){

        // 获取topic
        RTopic topic = redissonClient.getTopic("cultural_tourism_group_buy_dcc");

        // 给topic添加监听, new接受消息的操作
        topic.addListener(String.class, (charSequence, s) -> {
            String[] split = s.split(Constants.SPLIT);

            // 获取值
            String attribute = split[0];        // 属性值
            String key = BASE_CONFIG_PATH + attribute;
            String value = split[1];

            // 设置值
            RBucket<String> bucket = redissonClient.getBucket(key);
            boolean exists = bucket.isExists();
            if (!exists) return;
            bucket.set(value);

            // 拿key去获得对象的属性
            Object objBean = dccObjGroup.get(key);
            if (null == objBean) return;

            // 获得class
            Class<?> objBeanClass = objBean.getClass();
            // 检查 objBean 是否是代理对象
            if (AopUtils.isAopProxy(objBean)) {
                // 获取代理对象的目标对象
                objBeanClass = AopUtils.getTargetClass(objBean);
            }

            try
            {
                // 1. getDeclaredField 方法用于获取指定类中声明的所有字段，包括私有字段、受保护字段和公共字段。
                // 2. getField 方法用于获取指定类中的公共字段，即只能获取到公共访问修饰符（public）的字段。
                Field field = objBeanClass.getDeclaredField(attribute);
                field.setAccessible(true);
                field.set(objBean, value);
                field.setAccessible(false);
            }catch (Exception e){
                throw new RuntimeException(e);
            }
        });

        return topic;
    }

    /**
     * 自动配置注入器
     * 在 Spring 容器初始化完一个 Bean（对象）之后，
     * 利用反射机制，扫描该对象中所有标记了 @DCCValue 注解的字段，
     * 并从 Redis 配置中心拉取值注入进去。
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

        // 注意；增加 AOP 代理后，获得类的方式要通过 AopProxyUtils.getTargetClass(bean); 不能直接 bean.class 因为代理后类的结构发生变化，这样不能获得到自己的自定义注解了。
        // 获取bean的原始类和原始对象实例
        Class<?> targetBeanClass = bean.getClass();
        Object targetBeanObject = bean;
        if (AopUtils.isAopProxy(bean)) {
            targetBeanClass = AopUtils.getTargetClass(bean);
            targetBeanObject = AopProxyUtils.getSingletonTarget(bean);
        }

        // 拿到所有的属性序列
        Field[] fields = targetBeanClass.getDeclaredFields();

        // 遍历属性
        for (Field field : fields) {
            if(!field.isAnnotationPresent(DCCValue.class)){
                continue;
            }

            // 获取DCCValue
            DCCValue dccValue = field.getAnnotation(DCCValue.class);

            // 获取DCCValue对应的值
            String value = dccValue.value();
            // 如果值信息value为空，抛出异常
            if (StringUtils.isBlank(value)) {
                throw new RuntimeException(field.getName() + " @DCCValue is not config value config case 「isSwitch/isSwitch:1」");
            }

            // 拆分值信息
            String[] split = value.split(":");
            // 拼接key
            String key = BASE_CONFIG_PATH.concat(split[0]);
            // 获取默认值
            String defaultValue = split.length == 2 ? split[1] : null;

            // 设置值
            String setValue = defaultValue;

            try {
                // 如果默认值defaultValue为空则抛出异常
                if (StringUtils.isBlank(defaultValue)) {
                    throw new RuntimeException("dcc config error " + key + " is not null - 请配置默认值！");
                }

                // Redis 操作，判断配置Key是否存在，不存在则创建，存在则获取最新值
                RBucket<String> bucket = redissonClient.getBucket(key);
                boolean exists = bucket.isExists();
                if (!exists) {
                    bucket.set(defaultValue);
                }else{
                    setValue = bucket.get();
                }

                // 请把 targetBeanObject 这个对象中，对应 field 定义的那个变量，修改为 setValue
                // 反射的写法
                field.setAccessible(true);
                field.set(targetBeanObject, setValue);
                field.setAccessible(false);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            dccObjGroup.put(key, targetBeanObject);
        }
        return bean;
    }
}
