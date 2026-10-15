package com.demo.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 记忆衰减调度：每天凌晨把过老的记忆删掉、把稍旧的记忆降权。
 *
 * <p>为什么必须有这个：记忆是<b>只增不减</b>的。用户聊得越久，注入提示词的记忆越多，
 * 而且旧记忆会一直占着单用户上限、把新记忆挤掉。
 * 没有衰减机制的记忆库，本质上是一个会持续膨胀的日志。
 *
 * <p>阈值策略（{@code age > 90} 删、{@code age > 30} 降权）来自方案书的决策点。
 * 注意降权是<b>乘性</b>的且每天执行，所以 40 天的记忆会被反复降权到 1 —— 这是有意的：
 * 一条 30 天没人提、重要度又低的记忆本来就该淡出，而不是占着注入额度。
 */
@Slf4j
@Component
public class DecayScheduler {

    private final MemoryRagService memoryRagService;
    private final MemoryProperties properties;

    public DecayScheduler(MemoryRagService memoryRagService, MemoryProperties properties) {
        this.memoryRagService = memoryRagService;
        this.properties = properties;
    }

    /** 每天按配置的 cron 执行（默认凌晨 3 点）。 */
    @Scheduled(cron = "${memory.decay.cron:0 0 3 * * ?}")
    public void decayDaily() {
        runOnce();
    }

    /**
     * 执行一轮衰减。
     *
     * @return 累计删除条数
     */
    public int runOnce() {
        if (!properties.getDecay().isEnabled() || !memoryRagService.isUsable()) {
            return 0;
        }
        Set<String> userIds = memoryRagService.allUserIds();
        if (userIds.isEmpty()) {
            log.debug("【记忆库-衰减】没有用户需要处理");
            return 0;
        }

        int processed = 0;
        int deleted = 0;
        int batch = Math.max(1, properties.getDecay().getBatchSize());
        for (String userId : userIds) {
            if (processed++ >= batch) {
                log.info("【记忆库-衰减】本轮达到批量上限 {}，剩余用户下轮继续", batch);
                break;
            }
            try {
                deleted += memoryRagService.decay(userId);
            } catch (Exception e) {
                // 单个用户失败不能中断整轮
                log.warn("【记忆库-衰减】[{}] 处理失败，跳过：{}", userId, e.toString());
            }
        }
        log.info("【记忆库-衰减】本轮完成：处理 {} 个用户，删除 {} 条记忆", processed, deleted);
        return deleted;
    }
}
