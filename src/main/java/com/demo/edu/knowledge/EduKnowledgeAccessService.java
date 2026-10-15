package com.demo.edu.knowledge;

import com.demo.edu.mapper.EduDomainAccessMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库访问控制：决定"这次请求能不能查这个领域的向量库"。
 *
 * <h3>两道闸（顺序固定）</h3>
 * <ol>
 *   <li><b>等级闸</b>：{@code security_level ≤ user_authorization_level}
 *       （领域等级见 {@link EduKnowledgeAccessProperties}；请求方等级由凭据推导）；</li>
 *   <li><b>班级开关闸</b>：本班是否被教师停用了这个领域（默认开放）。</li>
 * </ol>
 *
 * <h3>为什么不给 health / libs 也加闸</h3>
 * 这两个接口只返回"服务是否可用""有哪些子库"，不含任何内容；
 * 而前端知识中心页要轮询它们、且轮询时不带学生标识。给它们加闸只会把页面弄坏，
 * 换不来任何隐私收益——<b>闸门要装在有内容的那一端</b>。
 *
 * <h3>关于 uid 的形态（一个必须处理的现实问题）</h3>
 * 知识中心页传的 uid 是 {@code <用户id>_<子库id>}（例如 {@code user_cvic4056_puberty}），
 * 直接拿去查班级会查不到。所以 {@link #resolveClassId} 做"精确 → 去掉子库后缀 → 去掉最后一段"
 * 的逐级回退。<b>精确匹配优先</b>是必须的：本项目的演示用户 id 形如 {@code user_demo_1}，
 * 盲目砍后缀会把它砍成 {@code user_demo}。
 */
@Slf4j
@Service
public class EduKnowledgeAccessService {

    private final EduKnowledgeAccessProperties properties;
    private final EduUserClassMapper userClassMapper;
    private final EduDomainAccessMapper domainAccessMapper;

    public EduKnowledgeAccessService(EduKnowledgeAccessProperties properties,
                                     EduUserClassMapper userClassMapper,
                                     EduDomainAccessMapper domainAccessMapper) {
        this.properties = properties;
        this.userClassMapper = userClassMapper;
        this.domainAccessMapper = domainAccessMapper;
    }

    /**
     * @param allowed         是否放行
     * @param requesterLevel  推导出的请求方等级
     * @param domainLevel     该领域的等级
     * @param classId         解析出的班级（null = 未能识别身份）
     * @param reason          拒绝原因（放行时为 null），可直接展示给用户
     */
    public record Decision(boolean allowed, int requesterLevel, int domainLevel,
                           String classId, String reason) {
    }

    /**
     * 判定一次知识库访问。
     *
     * @param domainId 领域标识（如 {@code sexedu}）
     * @param uid      请求携带的用户标识（可为空）
     */
    public Decision check(String domainId, String uid) {
        if (!properties.isEnabled()) {
            return new Decision(true, properties.getStudentLevel(), properties.levelOf(domainId),
                    null, null);
        }
        int domainLevel = properties.levelOf(domainId);

        // 单人工作台没有入班流程；仅放行明确的课堂会话及既有成长专题，不伪造班级。
        if (properties.isSingleUserClassroomEnabled()
                && domainId != null
                && java.util.Set.of("psychology", "lifesafety", "sexedu").contains(domainId)
                && java.util.Set.of("classroom", "classroom_general", "classroom_life", "classroom_support",
                        "classroom_puberty", "classroom_relationship", "classroom_protection").contains(uid == null ? "" : uid)
                && domainLevel <= properties.getStudentLevel()) {
            return new Decision(true, properties.getStudentLevel(), domainLevel, null, null);
        }

        String classId = resolveClassId(uid);
        int requesterLevel = (classId == null || classId.isBlank())
                ? properties.getAnonymousLevel() : properties.getStudentLevel();

        // 闸 1：等级
        if (domainLevel > requesterLevel) {
            String reason = domainLevel >= properties.getStudentLevel()
                    ? "该专题知识库需要学生身份（请从学生端进入并携带你的用户标识）"
                    : "该知识库不对当前身份开放";
            log.info("🔒 知识库访问被拒（等级）：domain={} 领域等级={} 请求方等级={} reason={}",
                    domainId, domainLevel, requesterLevel, reason);
            return new Decision(false, requesterLevel, domainLevel, classId, reason);
        }

        // 闸 2：班级开关（无记录 = 默认开放）
        try {
            Integer enabled = domainAccessMapper.selectEnabled(classId, domainId);
            if (enabled != null && enabled == 0) {
                log.info("🔒 知识库访问被拒（班级开关）：domain={} class={} 已被本班教师停用",
                        domainId, classId);
                return new Decision(false, requesterLevel, domainLevel, classId,
                        "本班教师已停用该专题知识库");
            }
        } catch (Exception e) {
            // 开关表查不到时按"开放"处理：它是额外管控，不是安全前提；
            // 真正的安全前提是等级闸（不依赖数据库）
            log.warn("查询班级知识库开关失败（按开放处理）：{}", e.toString());
        }

        return new Decision(true, requesterLevel, domainLevel, classId, null);
    }

    /** 教师端：查看本班各领域开关（含默认开放的领域，便于前端直接渲染列表）。 */
    public Map<String, Object> accessOverview(String classId, Iterable<String> domainIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classId", classId);
        Map<String, Object> domains = new LinkedHashMap<>();
        for (String domainId : domainIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domainLevel", properties.levelOf(domainId));
            item.put("enabled", isEnabledForClass(classId, domainId));
            domains.put(domainId, item);
        }
        out.put("domains", domains);
        out.put("note", "开关默认为开放；「停用」只影响本班学生的检索，不会删除任何材料。");
        return out;
    }

    /** 该班该领域是否开放（无记录 = 开放）。 */
    public boolean isEnabledForClass(String classId, String domainId) {
        try {
            Integer enabled = domainAccessMapper.selectEnabled(classId, domainId);
            return enabled == null || enabled != 0;
        } catch (Exception e) {
            log.warn("查询班级开关失败（按开放处理）：{}", e.toString());
            return true;
        }
    }

    /** 设置班级开关。 */
    public boolean setEnabledForClass(String classId, String domainId, boolean enabled) {
        try {
            domainAccessMapper.upsert(classId, domainId, enabled ? 1 : 0);
            log.info("🔧 班级 {} 的知识库 {} 已{}", classId, domainId, enabled ? "开放" : "停用");
            return true;
        } catch (Exception e) {
            log.warn("设置班级开关失败：{}", e.toString());
            return false;
        }
    }

    /**
     * 由 uid 解析班级：精确匹配优先，其次逐级去掉 {@code _} 后缀。
     *
     * <p>回退是必要的（知识中心页传的是 {@code user_xxx_libId}），
     * 但必须精确优先（{@code user_demo_1} 这种 id 砍一段就查不到了）。
     */
    public String resolveClassId(String uid) {
        if (uid == null || uid.isBlank()) return null;
        String candidate = uid.trim();
        for (int i = 0; i < 3; i++) {
            try {
                String classId = userClassMapper.selectClassIdByUserId(candidate);
                if (classId != null && !classId.isBlank()) return classId;
            } catch (Exception e) {
                log.warn("查询用户班级失败（{}）：{}", candidate, e.toString());
                return null;
            }
            int idx = candidate.lastIndexOf('_');
            if (idx <= 0) break;
            candidate = candidate.substring(0, idx);
        }
        return null;
    }
}
