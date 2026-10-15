package com.demo.edu.knowledge;

import com.demo.edu.mapper.EduDomainAccessMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库访问控制测试（对应方案书的
 * {@code security_level ≤ user_authorization_level} 与"教师可一键关闭本班某专题库"）。
 *
 * <p>这里守三件容易做错的事：
 * <ol>
 *   <li><b>分级的两端都是真实量</b>：专题库=2、匿名=1、有班级的学生=2 —— 不是给所有人发同一个数字；</li>
 *   <li><b>班级开关默认开放</b>：表里没记录就是开放，"关掉"才产生记录；</li>
 *   <li><b>uid 归一化精确优先</b>：知识中心页传的是 {@code user_xxx_libId}，需要回退；
 *       但 {@code user_demo_1} 这种 id 砍一段就查不到了，所以必须先试精确匹配。</li>
 * </ol>
 */
class EduKnowledgeAccessTest {

    /** 假班级映射：user_cvic4056 / user_demo_1 属于 class_demo_1。 */
    private static EduUserClassMapper userClassMapper() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("user_cvic4056", "class_demo_1");
        mapping.put("user_demo_1", "class_demo_1");
        return new EduUserClassMapper() {
            @Override
            public String selectClassIdByUserId(String userId) {
                return mapping.get(userId);
            }

            @Override
            public int countStudentsOfClass(String classId) {
                return mapping.size();
            }
        };
    }

    /** 假开关表：只放"关过"的记录（默认开放）。 */
    private static EduDomainAccessMapper domainAccessMapper(Map<String, Integer> table) {
        return new EduDomainAccessMapper() {
            @Override
            public Integer selectEnabled(String classId, String domainId) {
                return table.get(classId + "|" + domainId);
            }

            @Override
            public int upsert(String classId, String domainId, int enabled) {
                table.put(classId + "|" + domainId, enabled);
                return 1;
            }

            @Override
            public List<Map<String, Object>> listByClass(String classId) {
                return List.of();
            }
        };
    }

    private static EduKnowledgeAccessProperties props() {
        EduKnowledgeAccessProperties p = new EduKnowledgeAccessProperties();
        p.getDomainLevels().put("lifesafety", 2);
        p.getDomainLevels().put("sexedu", 2);
        return p;
    }

    private EduKnowledgeAccessService service(Map<String, Integer> switchTable) {
        return new EduKnowledgeAccessService(props(), userClassMapper(), domainAccessMapper(switchTable));
    }

    @Test
    @DisplayName("专题库（level 2）对匿名请求拒绝")
    void rejectsAnonymousOnTopicLibrary() {
        EduKnowledgeAccessService.Decision d = service(new HashMap<>()).check("sexedu", "unknown_user");
        assertFalse(d.allowed());
        assertEquals(2, d.domainLevel());
        assertEquals(1, d.requesterLevel());
        assertTrue(d.reason().contains("学生身份"), "拒绝原因要能直接展示给用户：" + d.reason());
    }

    @Test
    @DisplayName("专题库对有班级的学生放行")
    void allowsStudentOnTopicLibrary() {
        EduKnowledgeAccessService.Decision d = service(new HashMap<>()).check("sexedu", "user_cvic4056");
        assertTrue(d.allowed(), "专题库默认对本班学生开放");
        assertEquals(2, d.requesterLevel());
        assertEquals("class_demo_1", d.classId());
        assertNull(d.reason());
    }

    @Test
    @DisplayName("通识/心理学（默认 level 1）匿名也放行 —— 不能把常规学科一起关掉")
    void publicDomainsStayOpen() {
        EduKnowledgeAccessService svc = service(new HashMap<>());
        assertTrue(svc.check("tongshi", "unknown_user").allowed());
        assertTrue(svc.check("psychology", "").allowed());
        assertTrue(svc.check("tongshi", null).allowed());
    }

    @Test
    @DisplayName("班级开关：默认开放 → 关掉即拒绝 → 重新开放即恢复")
    void classSwitchDefaultsOpenAndToggles() {
        Map<String, Integer> table = new HashMap<>();
        EduKnowledgeAccessService svc = service(table);

        assertTrue(svc.isEnabledForClass("class_demo_1", "sexedu"), "无记录 = 默认开放");
        assertTrue(svc.check("sexedu", "user_cvic4056").allowed());

        svc.setEnabledForClass("class_demo_1", "sexedu", false);
        assertFalse(svc.isEnabledForClass("class_demo_1", "sexedu"));
        EduKnowledgeAccessService.Decision denied = svc.check("sexedu", "user_cvic4056");
        assertFalse(denied.allowed());
        assertTrue(denied.reason().contains("停用"),
                "原因要说清是「本班停用」而不是「身份不够」：" + denied.reason());

        svc.setEnabledForClass("class_demo_1", "sexedu", true);
        assertTrue(svc.check("sexedu", "user_cvic4056").allowed());
    }

    @Test
    @DisplayName("开关是班级级的：A 班关掉不影响 B 班")
    void switchIsPerClass() {
        Map<String, Integer> table = new HashMap<>();
        table.put("class_a|sexedu", 0); // A 班关掉
        EduKnowledgeAccessService svc = service(table);
        assertFalse(svc.isEnabledForClass("class_a", "sexedu"));
        assertTrue(svc.isEnabledForClass("class_b", "sexedu"), "其它班不受影响");
    }

    @Test
    @DisplayName("uid 归一化：精确优先，其次回退到去掉子库后缀")
    void resolvesUidWithFallback() {
        EduKnowledgeAccessService svc = service(new HashMap<>());
        // 知识中心页的形态：user_xxx_libId
        assertEquals("class_demo_1", svc.resolveClassId("user_cvic4056_puberty"));
        assertEquals("class_demo_1", svc.resolveClassId("user_cvic4056"));
        // 精确优先：user_demo_1 不能因为砍后缀被误判成 user_demo（那会查不到班级）
        assertEquals("class_demo_1", svc.resolveClassId("user_demo_1"));
        assertNull(svc.resolveClassId("user_not_in_any_class"));
        assertNull(svc.resolveClassId(null));
    }

    @Test
    @DisplayName("总开关关闭 → 不做任何校验（排障用）")
    void masterSwitchOffMeansAllowAll() {
        EduKnowledgeAccessProperties p = props();
        p.setEnabled(false);
        EduKnowledgeAccessService svc = new EduKnowledgeAccessService(
                p, userClassMapper(), domainAccessMapper(new HashMap<>()));
        assertTrue(svc.check("sexedu", "unknown_user").allowed());
    }

    @Test
    @DisplayName("开关表查询异常时按开放处理（额外管控挂了不该把内容全封掉）")
    void degradesOpenWhenSwitchTableFails() {
        EduDomainAccessMapper broken = new EduDomainAccessMapper() {
            @Override
            public Integer selectEnabled(String classId, String domainId) {
                throw new IllegalStateException("表不存在");
            }

            @Override
            public int upsert(String classId, String domainId, int enabled) {
                return 0;
            }

            @Override
            public List<Map<String, Object>> listByClass(String classId) {
                return List.of();
            }
        };
        EduKnowledgeAccessService svc = new EduKnowledgeAccessService(props(), userClassMapper(), broken);
        assertTrue(svc.isEnabledForClass("class_demo_1", "sexedu"));
        assertTrue(svc.check("sexedu", "user_cvic4056").allowed(),
                "等级闸已经过了（有学生身份），开关表挂了不该再拦——真正的安全前提是等级闸，它不依赖数据库");
    }

    @Test
    @DisplayName("教师端总览：列出各领域的等级与开关状态")
    void overviewListsDomains() {
        Map<String, Integer> table = new HashMap<>();
        table.put("class_demo_1|sexedu", 0);
        Map<String, Object> overview = service(table)
                .accessOverview("class_demo_1", List.of("lifesafety", "sexedu", "school"));

        assertEquals("class_demo_1", overview.get("classId"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> domains = (Map<String, Map<String, Object>>) overview.get("domains");
        assertEquals(3, domains.size());
        assertEquals(2, domains.get("sexedu").get("domainLevel"));
        assertEquals(false, domains.get("sexedu").get("enabled"));
        assertEquals(1, domains.get("school").get("domainLevel"));
        assertEquals(true, domains.get("school").get("enabled"));
    }

    @Test
    @DisplayName("等级配置可扩：把某库配成 3 级（教师专用）会对学生拒绝")
    void levelsAreConfigurable() {
        EduKnowledgeAccessProperties p = props();
        p.getDomainLevels().put("school", 3);
        EduKnowledgeAccessService svc = new EduKnowledgeAccessService(
                p, userClassMapper(), domainAccessMapper(new LinkedHashMap<>()));
        assertFalse(svc.check("school", "user_cvic4056").allowed(), "3 级库对学生应拒绝");
        assertEquals(3, svc.check("school", "user_cvic4056").domainLevel());
    }
}
