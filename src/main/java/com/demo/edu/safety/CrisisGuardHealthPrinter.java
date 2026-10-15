package com.demo.edu.safety;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动期打印【危机兜底】配置面板。
 *
 * <p>存在的唯一理由：{@code enforce} 与 {@code log} 在对话里看不出区别，
 * 而 {@code log} 意味着"学生说不想活时，他得到的是模型自由生成的文字"。
 * 这类"不报错、只是没生效"的静默失效，本项目已经踩过三次（累计 token 恒 0、
 * DeepSeek 响应被 gzip 截成 1 字节、天气 Key 与 Host 错配），所以宁可每次启动都打印一遍。
 */
@Slf4j
@Component
public class CrisisGuardHealthPrinter {

    private final CrisisGuardProperties properties;
    private final CrisisCountRecorder recorder;

    public CrisisGuardHealthPrinter(CrisisGuardProperties properties, CrisisCountRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
    }

    @PostConstruct
    public void print() {
        if (!properties.isEnabled()) {
            log.warn("""

                    ═══════════════ 【危机兜底】配置 ═══════════════
                     总开关      : enabled=false（已关闭）
                     ⚠️ 关闭状态下，学生表达自伤/自杀念头时不会得到固定引导话术，
                        这句话会照常交给 LLM 自由生成。仅调试时使用。
                    ═════════════════════════════════════════════════""");
            return;
        }
        log.info("""

                ═══════════════ 【危机兜底】配置 ═══════════════
                 总开关      : enabled=true
                 处置模式    : mode={}{}
                 词表        : 内置 {} 条（{}）+ 自定义 {} 条
                 接入点      : AgentExecutor.chat / chatStream / BaseDocKnowledgeController.search
                 命中的动作  : 跳过 LLM，返回固定话术（12356 / 12355）+ 按班级按日带等级脱敏计数
                ═════════════════════════════════════════════════""",
                properties.getMode(),
                properties.isEnforce() ? "（命中即替换回复，不进 LLM）"
                        : "（⚠️ 只记录不替换 —— 演示/上线前务必改成 enforce）",
                CrisisSignalDetector.signalCount(),
                CrisisSignalDetector.levelSummary(),
                properties.getExtraWords() == null ? 0 : properties.getExtraWords().size());
    }
}
