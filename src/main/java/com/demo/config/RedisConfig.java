package com.demo.config;

import com.demo.entity.UserSettingsEntity;
import com.demo.cache.ThreeTierCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis + 三级缓存配置
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    @Value("${app.cache.redis.ttl.user-settings:1800}")
    private int userSettingsTtl;

    @Value("${app.cache.redis.ttl.chat-history:600}")
    private int chatHistoryTtl;

    // ======================== Redis 连接健康检查 ========================

    /**
     * 启动时检测 Redis 是否连通，PING 成功/失败均在日志中明确体现
     */
    @PostConstruct
    public void checkRedisConnection() {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔍 开始检测 Redis 连接...");
        log.info("   Host: {}:{}", redisHost, redisPort);
        log.info("   Database: {}", redisDatabase);
        log.info("   缓存 TTL → user_settings: {}s, chat_history: {}s", userSettingsTtl, chatHistoryTtl);

        try {
            // Spring Boot 自动装配的 RedisConnectionFactory 还未完全就绪时，
            // 先尝试通过 StringRedisTemplate ping（如果 Bean 已就绪）
            log.info("   Redis 连接检测将在首次读写时自动完成（Lettuce 懒连接）");
            log.info("   ⚠ 如 Redis 未启动，缓存将自动降级：L1 内存 ⇢ L3 MySQL（跳过 L2）");
        } catch (Exception e) {
            log.error("❌ Redis 连接检测失败: {}", e.getMessage());
        }
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * 创建 StringRedisTemplate 并立即 ping 验证
     */
    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.setKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer());
        template.setHashKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer());
        org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer<>(Object.class);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();

        // Bean 初始化后立即 ping，确认连通
        try {
            String pong = template.getConnectionFactory().getConnection().ping();
            log.info("✅ Redis PING 成功 → 响应: {}", pong);
        } catch (Exception e) {
            log.error("❌ Redis PING 失败 → {}:{} 不可达！错误: {}",
                    redisHost, redisPort, e.getMessage());
            log.error("   缓存将降级运行：所有 L2(Redis) 操作自动跳过，仅 L1(内存)+L3(MySQL) 生效");
        }

        return template;
    }

    /**
     * user_settings 表的三级缓存（3 个 Service 共享同一实例）
     * VoiceModeService / VoiceTypeService / ChatModeService 都读写此缓存
     */
    @Bean
    public ThreeTierCache<UserSettingsEntity> userSettingsCache(StringRedisTemplate redisTemplate) {
        return new ThreeTierCache<>("user:settings", UserSettingsEntity.class,
                redisTemplate, Duration.ofSeconds(userSettingsTtl));
    }
}
