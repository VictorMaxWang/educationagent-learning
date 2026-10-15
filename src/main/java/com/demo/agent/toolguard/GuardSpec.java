package com.demo.agent.toolguard;

import java.util.Set;

/**
 * 一条工具守卫规则：命中任一关键词 → 期望调用 {@code toolName}。
 *
 * <p>放在这个包（而不是作为 {@code AgentExecutor} 的私有 record）的原因：
 * 工具守卫的规则表本身是"数据"，仲裁逻辑是"算法"。把数据留在 {@code AgentExecutor}、
 * 把算法放到独立包，既让算法可以脱离 Spring 上下文被单测覆盖，
 * 也把 {@code AgentExecutor} 的改动面压到最小——那个文件是跨分支合并的冲突高发区。
 *
 * @param keywords 触发关键词（命中任一即视为该规则适用）
 * @param toolName 期望被调用的工具名（静态 {@code @Tool} 方法名，或 MCP 限定名 {@code source_tool}）
 * @param ruleDesc 规则的中文说明，仅用于违规重试提示词
 */
public record GuardSpec(Set<String> keywords, String toolName, String ruleDesc) {
}
