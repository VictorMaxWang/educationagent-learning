package com.demo.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具过滤的求交集 + 安全阀逻辑。
 *
 * <p>抽成独立纯函数类（而不是留在 {@code AgentExecutor} 的私有方法里）的原因：
 * 这是整个前置路由里<b>唯一会造成功能性回归</b>的一步——过滤错了，模型就看不见本该有的工具，
 * 而且失败是静默的（用户只会觉得"AI 变笨了"）。方案书把"工具过滤没生效/过滤错"
 * 列为核心 bug 类别，所以这段逻辑必须能脱离 Spring 上下文被单测钉住。
 *
 * <p>三态语义在这一层收敛：{@code requested == null} 或最终回退 → 返回 {@code tools == null}
 * 表示"不过滤、发全量"；返回空列表表示"真的一个工具都不发"（闲聊意图）。
 */
public final class ToolFilterResolver {

    private ToolFilterResolver() {
    }

    /**
     * @param requested       路由层给出的期望工具（通常是"意图工具 ∪ 通用工具"）；{@code null} = 不做过滤
     * @param intentTools     意图<b>原生</b>声明的工具（不含通用工具），仅用于安全阀判定
     * @param available       当前真实注册的工具名全集
     * @param fallbackOnEmpty 意图原生工具在当前工具集里一个都不存在时，是否回退全量
     */
    public static Resolution resolve(List<String> requested,
                                     List<String> intentTools,
                                     Set<String> available,
                                     boolean fallbackOnEmpty) {
        if (requested == null) {
            return new Resolution(null, false, "not-filterable");
        }

        List<String> kept = new ArrayList<>();
        for (String name : requested) {
            if (name != null && available.contains(name) && !kept.contains(name)) {
                kept.add(name);
            }
        }

        // 安全阀 1：交集为空。可能是工具名写错，也可能是工具被摘掉了注册。
        // 注意这与"意图本来就不要工具"不同——那种情况 requested 会是空列表而非非空列表。
        if (kept.isEmpty() && !requested.isEmpty()) {
            return new Resolution(null, true, "empty-intersection");
        }

        // 安全阀 2：意图原生工具全部缺失 —— 说明意图表与工具注册表已经脱节，
        // 此时顺着过滤走会让整个意图静默失效，宁可放弃省 token。
        boolean intentMissing = intentTools != null && !intentTools.isEmpty()
                && intentTools.stream().noneMatch(available::contains);
        if (fallbackOnEmpty && intentMissing) {
            return new Resolution(null, true, "intent-tools-missing");
        }

        return new Resolution(kept, false, "filtered");
    }

    /**
     * @param tools          最终要发给 LLM 的工具名；{@code null} 表示不做过滤（发全量）
     * @param fellBackToAll  是否触发了安全阀
     * @param reason         判定原因，便于日志与埋点定位
     */
    public record Resolution(List<String> tools, boolean fellBackToAll, String reason) {
    }
}
