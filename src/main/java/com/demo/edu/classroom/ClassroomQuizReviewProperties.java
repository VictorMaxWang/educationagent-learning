package com.demo.edu.classroom;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 习题两级审核中 L2（盲解复核）的配置，键前缀 {@code edu.classroom.quiz-review.*}。
 *
 * <p><b>只有 L2 有开关。</b>L1 是确定性规则（空解析、解析含模型思考过程、选项键不是单个大写字母、
 * 选项正文重复），零模型调用、无副作用，永远执行，因此不提供关闭方式。</p>
 *
 * <h3>为什么复核必须 temperature 0</h3>
 * 生成模型在 {@code AgentConfig} 里固定 {@code temperature(0.3)}。
 * 盲解要给出稳定可比的结论就必须 0 温度，所以 {@link ClassroomTutorModel} 为复核单独建一个模型实例，
 * 而不是复用生成用的那个。
 *
 * <h3>为什么「复核没跑出来」不移出计分</h3>
 * 「复核失败」与「复核不通过」是两个不同结论。因一次模型抖动就把整页练习清空，
 * 等于用「没题可做」换「答案可能错」，代价更大。这类题保留计分并如实记为 UNVERIFIED，
 * <b>任何路径都不许把它记成 PASS</b>。
 *
 * <h3>边界（对外话术不要越过）</h3>
 * 复核与出题是同族模型，出题错一次复核可能原样再错。它的价值来自上下文差异
 * （批量作答、不看标答与解析、不产出思考过程），<b>不是独立验证</b>。
 * 简答题无法用比对答案复核，一律 UNVERIFIED；填空题只有纯数值才比对。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edu.classroom.quiz-review")
public class ClassroomQuizReviewProperties {

    /** L2 总开关。false → 不做盲解复核，题目不带 review 字段，行为与改造前一致。 */
    private boolean enabled = true;

    /** 复核用模型；留空表示沿用统一文本模型。 */
    private String model = "";

    /** 复核温度，固定 0。 */
    private double temperature = 0;

    /** 复核单次输出上限；防止啰嗦输出吃掉场景超时预算。 */
    private int maxTokens = 1200;

    /** 复核单次超时（毫秒）。 */
    private long timeoutMs = 45_000;

    /** 单题最多重新生成几次；仍不通过则降级为不计分思考题。 */
    private int maxRegenerationsPerQuestion = 2;
}
