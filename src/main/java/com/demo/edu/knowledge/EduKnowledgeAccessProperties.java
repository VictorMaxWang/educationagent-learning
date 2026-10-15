package com.demo.edu.knowledge;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库访问控制配置，键前缀 {@code edu.knowledge.access.*}。
 *
 * <h3>security_level 是怎么落地的（不是编出来的分级）</h3>
 * 方案书要求"检索请求附带元数据权限过滤 {@code security_level ≤ user_authorization_level}"。
 * 本项目把它落成<b>两个真实存在的量</b>：
 * <ul>
 *   <li><b>领域等级（security_level）</b>：由 {@link #domainLevels} 配置，默认
 *       {@code 1=公开}；专题库配 {@code 2}（需要学生身份）；将来的"教师专用库"可配 {@code 3}。</li>
 *   <li><b>请求方等级（user_authorization_level）</b>：由凭据<b>推导</b>而不是存库——
 *       能对应到一个班级的学生标识 → {@code 2}；无法识别的匿名请求 → {@code 1}。</li>
 * </ul>
 * 这样"分级"两端都是真实量，而不是给所有人发同一个数字再比较。
 * 一旦将来引入教师专用库，只需把它的领域等级配成 3、并让教师请求推导出 3 即可（各一行）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edu.knowledge.access")
public class EduKnowledgeAccessProperties {

    /** 总开关。false → 不做任何访问校验（行为回到改造前，仅用于排障）。 */
    private boolean enabled = true;

    /** 本地单人课堂可使用三个成长专题；不授予校本资料或教师领域访问权。 */
    private boolean singleUserClassroomEnabled = false;

    /** 未显式配置的领域按此等级计。 */
    private int defaultDomainLevel = 1;

    /** 领域 → 等级。1=公开；2=需要学生身份（现由班级归属判定）；3=预留（教师专用）。 */
    private Map<String, Integer> domainLevels = new LinkedHashMap<>();

    /** 学生身份对应的等级（能查到班级即视为学生）。 */
    private int studentLevel = 2;

    /** 无法识别身份时的等级（匿名）。 */
    private int anonymousLevel = 1;

    /** 取某领域的等级（未配置时用默认值）。 */
    public int levelOf(String domainId) {
        if (domainId == null) return defaultDomainLevel;
        Integer v = domainLevels.get(domainId);
        return v == null ? defaultDomainLevel : v;
    }
}
