package com.demo.testsupport;

import org.mockito.Answers;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 普通上下文测试不要求本机 Redis；只有显式容量基准才使用真实连接。 */
// Lettuce 同时实现同步和响应式接口，替身须保留两者，避免自动配置拿到不兼容的工厂类型。
@MockitoBean(types = RedisConnectionFactory.class, extraInterfaces = ReactiveRedisConnectionFactory.class)
@MockitoBean(name = "redisTemplate", types = StringRedisTemplate.class, answers = Answers.RETURNS_DEEP_STUBS)
public abstract class IsolatedRedisStubContext {
}
