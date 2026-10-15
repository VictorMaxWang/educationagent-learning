package com.demo.agent.toolguard;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import com.demo.config.AiChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动期打印【ToolGuard】配置面板。
 *
 * <p>方案书 Prompt 8/9 都要求启动面板。这里尤其必要：{@code mode=log} 与 {@code mode=enforce}
 * 的行为差异很大（后者会真的打断回复触发重试），而两者在对话里看不出区别——
 * 不打印面板就无法确认线上到底跑的是哪种模式。
 */
@Slf4j
@Component
public class ToolGuardHealthPrinter {

    @Resource
    private AiChatProperties aiChat;

    private final ToolGuardProperties properties;
    private final ToolGuardAudit audit;

    public ToolGuardHealthPrinter(ToolGuardProperties properties, ToolGuardAudit audit) {
        this.properties = properties;
        this.audit = audit;
    }

    @PostConstruct
    public void print() {
        if (!properties.isEnabled()) {
            log.info("【ToolGuard】ai.fc.toolguard.enabled=false —— 守卫审计未启用，行为与改造前一致");
            return;
        }
        log.info("""

                ═══════════════ 【ToolGuard】配置 ═══════════════
                 总开关      : enabled={}  mode={}（{}）
                 裁判        : enabled={}  model={}  timeout={}ms  状态={}
                 说明        : log 模式只记录不阻断；enforce 模式 FAIL 会触发违规重试
                ═════════════════════════════════════════════════""",
                properties.isEnabled(),
                properties.getMode(),
                properties.isEnforce() ? "会阻断" : "只记录",
                properties.getJudge().isEnabled(),
                aiChat == null ? "unconfigured" : aiChat.getModel(),
                properties.getJudge().getTimeoutMs(),
                audit.describe());
    }
}
