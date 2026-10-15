package com.demo.edu.safety;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 危机兜底配置，键前缀 {@code edu.safety.*}。
 *
 * <h3>为什么 mode 默认是 enforce（与 ToolGuard 相反）</h3>
 * {@code ai.fc.toolguard.mode} 默认 {@code log}，因为它识别的是"该调工具没调"这种灰区，
 * 直接阻断会改掉现有对话行为，得先量误报率。
 * 危机兜底不一样：它的 {@code log} 模式意味着<b>一个说自己不想活的孩子，得到的是一段模型自由生成的文字</b>，
 * 而 enforced 与否在页面和日志里都看不出来 —— 这正是本项目反复踩到的"静默失效"。
 * 所以这里默认就生效，{@code log} 只作为<b>调词表时</b>的临时档位（且会在启动面板上大声打印）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edu.safety")
public class CrisisGuardProperties {

    /** 总开关。false → 完全不检测，行为与改造前一致。 */
    private boolean enabled = true;

    /** 处置模式：{@code enforce} 命中即返回固定话术（不进 LLM）；{@code log} 只记日志、照常走 LLM。 */
    private String mode = "enforce";

    /** 运行时追加的危机词（用于按学校/地区口径补充，不必改代码）。 */
    private List<String> extraWords = new ArrayList<>();

    /** 是否处于强制执行模式。 */
    public boolean isEnforce() {
        return mode != null && "enforce".equalsIgnoreCase(mode.trim());
    }
}
