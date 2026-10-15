package com.demo.cache;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 三级缓存：L1 内存（ConcurrentHashMap）→ L2 Redis → L3 MySQL
 * 【读取执行流程】
 *   L1 命中 → 直接返回（零延迟）
 *   L1 未命中 → L2 Redis → 命中则回填 L1 + 返回
 *   L2 未命中 → L3 MySQL → 回填 L2 + L1 + 返回
 * 【写入执行流程】
 *   1.优先执行MySQL数据库数据持久化操作
 *   2.同步更新L1本地内存缓存
 *   3.异步/同步写入L2 Redis缓存并设置自动过期时间
 * 【降级容错机制】
 *   若Redis服务未启动、断开连接、读写异常，自动屏蔽L2缓存逻辑
 *   系统自动降级运行，仅保留 L1内存缓存 + L3MySQL数据库双缓存架构，不影响业务正常运行
 *   @param <V> 缓存值类型
 */
@Slf4j
public class ThreeTierCache<V> {

    // 缓存业务标识名称，用于日志区分不同业务缓存
    private final String cacheName;
    // 缓存实体数据类型，用于JSON序列化反序列化
    private final Class<V> valueType;
    // SpringRedis操作模板类，用于操作L2 Redis缓存
    private final StringRedisTemplate redis;
    // Redis缓存全局过期时长，单位：秒
    private final Duration redisTtl;
    // L1 本地一级内存缓存，ConcurrentHashMap保证多线程并发读写线程安全，常驻JVM内存，读写速度最快
    private final ConcurrentHashMap<String, V> l1 = new ConcurrentHashMap<>();

    // ======================== 缓存命中统计计数器 ========================
    /** L1内存缓存命中次数 */
    private final AtomicLong hitL1 = new AtomicLong(0);
    /** L2 Redis缓存命中次数 */
    private final AtomicLong hitL2 = new AtomicLong(0);
    /** L3数据库回源查询次数 */
    private final AtomicLong hitL3 = new AtomicLong(0);
    /** 三层缓存全部未命中次数 */
    private final AtomicLong missAll = new AtomicLong(0);
    /** Redis缓存读写异常累计次数 */
    private final AtomicLong redisErrors = new AtomicLong(0);

    /**
     * 三级缓存构造初始化方法
     * @param cacheName 缓存业务名称
     * @param valueType 缓存数据实体Class类型
     * @param redis Redis操作模板实例
     * @param redisTtl Redis缓存过期时间
     */
    public ThreeTierCache(String cacheName, Class<V> valueType,
                          StringRedisTemplate redis, Duration redisTtl) {
        this.cacheName = cacheName;
        this.valueType = valueType;
        this.redis = redis;
        this.redisTtl = redisTtl;

        log.info("📦 [{}] 三级缓存初始化完成 | Redis TTL={}s | Redis 状态={}",
                cacheName, redisTtl.getSeconds(),
                redis != null ? "已配置" : "❌ 未配置（降级为 L1+L3）");
    }

    // ======================== 缓存读取核心方法 ========================

    /**
     * 三级缓存级联读取数据
     * @param key 缓存唯一业务键
     * @param mysqlLoader 数据库查询回调函数，L1/L2未命中时执行
     * @return 缓存对应数据，无数据返回null
     */
    public V get(String key, Supplier<V> mysqlLoader) {
        // 拼接Redis完整缓存Key前缀
        String redisKey = redisKey(key);

        // 第一步：查询L1本地内存缓存
        V val = l1.get(key);
        if (val != null) {
            hitL1.incrementAndGet();
            return val;  // debug 级，太频繁
        }

        // 第二步：Redis实例存在则查询L2分布式缓存
        if (redis != null) {
            try {
                // 从Redis读取JSON格式字符串数据
                String json = redis.opsForValue().get(redisKey);
                if (json != null) {
                    val = JSON.parseObject(json, valueType);
                    if (val != null) {
                        // Redis数据回填至本地一级缓存
                        l1.put(key, val);
                        // 原子性地增加 L2 缓存命中次数，线程安全-底层使用 CAS（Compare-And-Swap）机制
                        hitL2.incrementAndGet();
                        log.info("🔄 [{}] L2(Redis) 命中 → key={} | 已回填 L1 | 统计: L1={} L2={} L3={} miss={}",
                                cacheName, key, hitL1.get(), hitL2.get(), hitL3.get(), missAll.get());
                        return val;
                    }
                }
                log.info("🔍 [{}] L2(Redis) 未命中 → key={}，回源 MySQL...", cacheName, key);
            } catch (Exception e) {
                // Redis读写异常计数累加，自动降级
                redisErrors.incrementAndGet();
                log.warn("⚠ [{}] Redis 读取异常 → key={} | 降级直查 MySQL | 错误: {} | Redis 错误计数: {}",
                        cacheName, key, e.getMessage(), redisErrors.get());
            }
        }

        // 第三步：L3 执行数据库回调查询原始数据
        val = mysqlLoader.get();
        if (val != null) {
            // 数据库数据回填一级缓存与二级缓存
            l1.put(key, val);
            putRedis(redisKey, val);
            hitL3.incrementAndGet();
            log.info("💾 [{}] L3(MySQL) 回源 → key={} | 已回填 L1+L2 | 统计: L1={} L2={} L3={} miss={}",
                    cacheName, key, hitL1.get(), hitL2.get(), hitL3.get(), missAll.get());
            return val;
        }

        // 三层缓存全部未命中
        missAll.incrementAndGet();
        log.info("❌ [{}] 全 miss → key={} 在 MySQL 中也不存在 | 统计: L1={} L2={} L3={} miss={}",
                cacheName, key, hitL1.get(), hitL2.get(), hitL3.get(), missAll.get());
        return null;
    }

    // ======================== 缓存写入更新方法 ========================

    /**
     * 全层级缓存统一更新方法
     * 业务层执行MySQL增改操作后调用此方法同步刷新缓存
     * @param key 缓存业务键
     * @param value 待更新缓存数据
     */
    public void put(String key, V value) {
        // 空数据直接拦截，不执行缓存更新
        if (key == null || value == null) return;

        // L1: 立即更新
        l1.put(key, value);

        // L2: Redis可用则同步写入二级缓存
        if (redis != null) {
            putRedis(redisKey(key), value);
            log.info("✍ [{}] 缓存已更新 → key={} (L1内存 + L2 Redis, TTL={}s)",
                    cacheName, key, redisTtl.getSeconds());
        } else {
            log.info("✍ [{}] 缓存已更新 → key={} (仅 L1 内存，Redis 未配置)",
                    cacheName, key);
        }
    }

    /**
     * 缓存主动删除失效方法
     * 业务数据删除时调用，清空对应层级缓存
     * @param key 待删除缓存键
     */
    public void evict(String key) {
        // 移除本地一级缓存
        l1.remove(key);
        if (redis != null) {
            // Redis可用则删除二级缓存
            try {
                redis.delete(redisKey(key));
                log.info("🗑 [{}] 缓存已清除 → key={} (L1 + L2)", cacheName, key);
            } catch (Exception e) {
                log.warn("⚠ [{}] Redis 删除异常 → key={} | 错误: {}", cacheName, key, e.getMessage());
            }
        }
    }

    // ======================== 缓存监控统计方法 ========================

    /**
     * 生成缓存运行状态统计信息
     * 包含总请求数、各层级命中率、异常次数，用于线上监控排查
     * @return 格式化统计字符串
     */
    public String stats() {
        long total = hitL1.get() + hitL2.get() + hitL3.get() + missAll.get();
        if (total == 0) return cacheName + " 暂无请求";

        return String.format("%s | 总请求=%d | L1命中=%d(%.0f%%) L2命中=%d(%.0f%%) L3回源=%d(%.0f%%) miss=%d | Redis异常=%d",
                cacheName, total,
                hitL1.get(), hitL1.get() * 100.0 / total,
                hitL2.get(), hitL2.get() * 100.0 / total,
                hitL3.get(), hitL3.get() * 100.0 / total,
                missAll.get(), redisErrors.get());
    }

    /**
     * 获取当前本地一级缓存存储数据条数
     * @return 内存缓存数量
     */
    public long l1Size() { return l1.size(); }

    // ======================== 内部方法 ========================
    /**
     * 私有方法：统一写入Redis缓存并设置过期时间
     * @param redisKey 拼接完成的Redis键
     * @param value 待存入缓存数据
     */
    private void putRedis(String redisKey, V value) {
        try {
            // 对象转为JSON字符串存入Redis
            String json = JSON.toJSONString(value);
            redis.opsForValue().set(redisKey, json, redisTtl);
        } catch (Exception e) {
            redisErrors.incrementAndGet();
            log.error("❌ [{}] Redis 写入失败！key={} | 错误: {} | Redis 错误计数: {}",
                    cacheName, redisKey, e.getMessage(), redisErrors.get());
        }
    }


    /**
     * 私有方法：统一拼接Redis缓存前缀Key
     * 格式：缓存业务名:自定义业务key
     * 方便Redis统一管理批量区分业务缓存
     * @param key 原始业务key
     * @return 带前缀完整Redis key
     */
    private String redisKey(String key) {
        return cacheName + ":" + key;
    }
}
