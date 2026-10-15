package com.demo.memory;

import com.demo.router.IntentLabel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 把本地记忆库接进对话链路：读侧构造上下文块，写侧沉淀新记忆并处理反馈。
 *
 * <h3>与旧的 Chroma 用户记忆是「二选一」，不是并行</h3>
 * 本项目原先已有一条用户长期记忆链路（{@code UserMemoryService} + Chroma + 云端 embedding），
 * 通过 {@code ChatContextManager.getLongTermMemories} 注入提示词。
 * 本模块是它的<b>本地替代</b>（同样的职责，不同的存储）。
 * 两条同时注入会让同一件事被说两遍、白白吃掉上下文预算，所以调用方按
 * {@code memory.integration.enabled} 二选一——开关打开时走本地，关闭时保持原样。
 *
 * <h3>为什么从用户的话里抽、不从助手的回复里抽</h3>
 * 方案书的抽取器把「用户：X / 助手：Y」一起喂给模型。这里<b>只喂用户的话</b>：
 * 助手回复是模型生成的，里面可能包含它自己的推测；把推测当成"用户事实"存进长期记忆，
 * 之后每轮对话都会被当成事实注入，等于把幻觉固化了。用户事实只能来自用户自己说的话。
 */
@Slf4j
@Service
public class MemoryIntegrationService {

    /** 记录每用户"上一轮注入过哪些记忆"，供显式反馈（对/不对）定位作用对象。 */
    private static final int MAX_TRACKED_USERS = 2000;
    private final Map<String, List<MemoryRecord>> lastRetrieved =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MemoryRecord>> eldest) {
                    return size() > MAX_TRACKED_USERS;
                }
            });

    private final MemoryProperties properties;
    private final HybridRagService hybridRagService;
    private final MemoryExtractor memoryExtractor;
    private final MemoryRagService memoryRagService;
    private final FeedbackCollector feedbackCollector;
    private final WorkingMemoryService workingMemoryService;

    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-integration-write");
        t.setDaemon(true);
        return t;
    });

    public MemoryIntegrationService(MemoryProperties properties,
                                    HybridRagService hybridRagService,
                                    MemoryExtractor memoryExtractor,
                                    MemoryRagService memoryRagService,
                                    FeedbackCollector feedbackCollector,
                                    WorkingMemoryService workingMemoryService) {
        this.properties = properties;
        this.hybridRagService = hybridRagService;
        this.memoryExtractor = memoryExtractor;
        this.memoryRagService = memoryRagService;
        this.feedbackCollector = feedbackCollector;
        this.workingMemoryService = workingMemoryService;
    }

    @PreDestroy
    public void shutdown() {
        writeExecutor.shutdown();
        try {
            writeExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记忆集成是否生效。false → 调用方应保持原有行为（走旧的 Chroma 记忆链路）。 */
    public boolean isEnabled() {
        return properties.getIntegration().isEnabled() && memoryRagService.isUsable();
    }

    /**
     * 构造注入提示词的记忆上下文块。
     *
     * @return 可注入的块；无需注入时返回 {@code null}
     */
    public Block buildContextBlock(String userId, String message, IntentLabel intent) {
        if (!isEnabled() || userId == null || message == null || message.isBlank()) {
            return null;
        }
        try {
            int topK = Math.max(1, properties.getIntegration().getRetrieveTopK());
            List<FusedResult> fused = hybridRagService.fusedSearch(userId, message, intent, topK);
            if (fused.isEmpty()) {
                lastRetrieved.remove(userId);
                return null;
            }

            StringBuilder sb = new StringBuilder(properties.getIntegration().getContextPrefix()).append('\n');
            StringBuilder note = new StringBuilder();
            List<MemoryRecord> memories = new ArrayList<>();
            // 重要度门槛：埋点全量捕获的原句重要度是 2，低于门槛 → 只在脑图上可见，
            // 不注入提示词。否则每轮上下文会被"今天天气怎么样""谢谢"这类内容稀释。
            int minImportance = properties.getIntegration().getMinImportance();
            for (FusedResult r : fused) {
                if (FusedResult.SOURCE_MEMORY.equals(r.getSource())) {
                    if (importanceOf(r) < minImportance) continue;
                    sb.append("- ").append(r.getContent()).append('\n');
                    MemoryRecord m = new MemoryRecord();
                    m.setId(str(r.getMetadata().get("memoryId")));
                    m.setContent(r.getContent());
                    memories.add(m);
                } else {
                    // 法律条文单独标注来源，让模型知道这是权威原文而非用户自述；
                    // 也不受重要度门槛约束（法条的重要度概念不适用）
                    note.append("- [法条] ").append(r.getContent()).append('\n');
                }
            }
            if (memories.isEmpty() && note.length() == 0) {
                lastRetrieved.remove(userId);
                return null;   // 全被门槛滤掉了，没必要注入一个空壳
            }
            if (note.length() > 0) {
                sb.append("以下为相关法律条文原文，仅在与问题直接相关时引用：\n").append(note);
            }
            sb.append("（以上为背景参考，与当前问题无关时请忽略）");

            lastRetrieved.put(userId, memories);
            return new Block(sb.toString(), memories.size(), fused.size());
        } catch (Exception e) {
            // 记忆检索失败绝不能让主对话失败
            log.warn("【记忆库-Integration】[{}] 上下文构造失败，本轮不注入记忆：{}", userId, e.toString());
            return null;
        }
    }

    /**
     * 写侧：沉淀新记忆 + 处理反馈 + 投递工作记忆。
     *
     * @param userMessage 用户这一轮说的话（抽取来源）
     */
    public void write(String userId, String userMessage) {
        if (!isEnabled() || userId == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        Runnable task = () -> {
            try {
                List<MemoryRecord> retrieved = lastRetrieved.get(userId);
                // 1) 显式/隐式反馈（可能删或改记忆，先于抽取，否则刚抽的会被误伤）
                feedbackCollector.handle(userId, userMessage, retrieved);

                // 2) 工作记忆缓冲（给"分几次说完整件事"兜底）
                workingMemoryService.onMessage(userId, userMessage);

                // 3) 立即抽取（规则层零成本，命中就直接入库；都没命中则埋点捕获原句）
                List<MemoryRecord> facts = memoryExtractor.extract(userId, userMessage);
                int handled = 0;
                for (MemoryRecord f : facts) {
                    if (memoryRagService.addMemory(
                            userId, f.getContent(), f.getType(), f.getImportance(), f.getKeywords()) != null) {
                        handled++;
                    }
                }
                // 注意措辞：addMemory 对重复项返回的是**已存在的那条**（不是 null），
                // 所以 handled 里含被去重跳过的。之前这里写"新增 N 条"、数的是候选数，
                // 索引写入全失败时照样打印"新增 6 条"，把人往错方向带过一次。
                if (handled > 0) {
                    log.info("【记忆库-Integration】[{}] 处理 {} 条记忆（候选 {} 条，重复的计入但未新增）",
                            userId, handled, facts.size());
                } else if (!facts.isEmpty()) {
                    log.warn("【记忆库-Integration】[{}] {} 条候选全部未能入库"
                            + "（去重 / 向量化失败 / 索引不可写），检查记忆库状态", userId, facts.size());
                }
            } catch (Exception e) {
                log.warn("【记忆库-Integration】[{}] 写入失败（不影响主流程）：{}", userId, e.toString());
            }
        };

        if (properties.getIntegration().isAsyncWrite()) {
            writeExecutor.submit(task);
        } else {
            task.run();
        }
    }

    /** 观测：当前跟踪的用户数。 */
    public int trackedUsers() {
        return lastRetrieved.size();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 从融合结果的 metadata 里读重要度。
     *
     * <p>读不到时返回 {@code Integer.MAX_VALUE}（<b>放行</b>而不是拦截）：
     * 门槛的作用是滤掉明确标记为低价值的埋点原句，不该因为一条元数据缺失
     * 就让本来有用的记忆凭空消失。
     */
    private static int importanceOf(FusedResult r) {
        Object v = r.getMetadata() == null ? null : r.getMetadata().get("importance");
        if (v instanceof Number n) return n.intValue();
        if (v == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * @param text         注入用的完整文本
     * @param memoryCount  其中来自用户记忆的条数
     * @param fusedCount   融合结果总条数
     */
    public record Block(String text, int memoryCount, int fusedCount) {
    }
}
