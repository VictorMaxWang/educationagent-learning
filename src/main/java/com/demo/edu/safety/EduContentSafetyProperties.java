package com.demo.edu.safety;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后置内容校验配置，键前缀 {@code edu.safety.content.*}。
 *
 * <h3>为什么默认 mode=log</h3>
 * 与本项目其余两处判断一致，但理由更硬：<b>内容裁判每次都要多调一次 LLM</b>，
 * 既增加延迟又增加成本，而"判得准不准"没有先验数据。
 * 所以先只记录（把误判率量出来），确认误报可接受再切 {@code enforce}。
 *
 * <p>对照：L1（精确关键词）默认 enforce；L2（模糊语义）有标注集数据后已切 enforce；
 * L4（内容裁判）没有数据之前不阻断。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edu.safety.content")
public class EduContentSafetyProperties {

    /** 总开关。false → 完全不做内容校验（行为与改造前一致）。 */
    private boolean enabled = true;

    /** {@code log} 只记录；{@code enforce} 命中即替换/追加更正提示。 */
    private String mode = "log";

    /** 裁判用模型（判别任务用便宜模型即可）。 */
    private String model = "deepseek-v4-flash";

    /** 单次裁判超时（毫秒）。超时按"无法判定"处理，不阻断。 */
    private int timeoutMs = 3000;

    /** 熔断：连续失败多少次后暂停裁判。 */
    private int circuitFailThreshold = 3;

    /** 熔断后暂停多长时间（毫秒）。 */
    private long circuitOpenMs = 60_000;

    /** 被判定为不合适时替换成的话术（非流式/知识库路径）。 */
    private String replacement = """
            这条回答可能不太合适，我已经把它拦下了。

            如果你在问的是学科或健康知识，换个说法再问一次，我会认真回答你；
            涉及身心健康的问题，也可以直接找老师、家长或校医聊。""";

    /** 流式路径的更正提示（回答已经推给用户，撤回不了，只能追加更正）。 */
    private String streamCorrection = "\n\n⚠️ 上面这段回答可能不太合适，请以老师或教材为准；"
            + "我会把这次内容记录给管理员复核。";

    public boolean isEnforce() {
        return mode != null && "enforce".equalsIgnoreCase(mode.trim());
    }
}
