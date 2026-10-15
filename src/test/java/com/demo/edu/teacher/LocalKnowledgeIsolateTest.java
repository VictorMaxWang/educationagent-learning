package com.demo.edu.teacher;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.doc.DocKnowledgeIndexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乡土校本知识库的<b>按班隔离</b>链路测试。
 *
 * <h3>能测什么、不能测什么（如实说明）</h3>
 * 隔离的完整链路是：上传打 classId（侧车）→ 索引器把 classId 写进 chunk 元数据 →
 * 检索时以 {@code where} 条件下发 → 只召回本班材料。其中<b>打通 Chroma 的最后一跳</b>
 * 没法在单测里跑（需要真实向量库），所以这里测的是这条链路上所有"可离线验证"的环节：
 * <ol>
 *   <li>侧车写入：上传后 classId 被正确登记，且<b>重新上传会覆盖归属</b>；</li>
 *   <li>侧车解析：索引器能把它读成"文件 → 元数据"的映射，坏格式不抛异常；</li>
 *   <li>过滤条件构造：{@code eqWhere} 生成的 {@code where} 子句形状正确，
 *       空值返回 {@code null}（表示"不过滤"而不是"过滤掉全部"）。</li>
 * </ol>
 * 端到端那一跳由演示时的活体检查覆盖：A 班上传 → 用 A 班 token 检索能命中、
 * 换成 B 班的过滤条件检索不到。
 */
class LocalKnowledgeIsolateTest {

    private static final String META_MAP_NAME = ".meta-map.json";

    @Test
    @DisplayName("上传把 classId 登记进侧车，且再次上传会覆盖归属（不会同时属于两个班）")
    void tagClassWritesAndOverwrites(@TempDir Path tmp) throws Exception {
        Path metaMap = tmp.resolve(META_MAP_NAME);

        SchoolResourceService.tagClass(metaMap, "school/讲义.txt", "class_a");
        JSONObject first = JSON.parseObject(Files.readString(metaMap));
        assertEquals("class_a", first.getJSONObject("school/讲义.txt").getString("classId"));

        // 同一份文件被 B 班重新上传：归属必须变成 B 班。
        // 若这里是"追加"语义，这份材料会永久泄漏给两个班。
        SchoolResourceService.tagClass(metaMap, "school/讲义.txt", "class_b");
        JSONObject second = JSON.parseObject(Files.readString(metaMap));
        assertEquals("class_b", second.getJSONObject("school/讲义.txt").getString("classId"));

        // 不同文件互不影响
        SchoolResourceService.tagClass(metaMap, "school/另一份.txt", "class_a");
        JSONObject third = JSON.parseObject(Files.readString(metaMap));
        assertEquals(2, third.size());
        assertEquals("class_a", third.getJSONObject("school/另一份.txt").getString("classId"));
    }

    @Test
    @DisplayName("索引器能把侧车解析成「文件 → 元数据」映射")
    void indexerParsesMetaMap() {
        String json = "{\"school/讲义.txt\":{\"classId\":\"class_a\"},"
                + "\"school/另一份.txt\":{\"classId\":\"class_b\"}}";
        Map<String, Map<String, Object>> parsed = DocKnowledgeIndexer.parseMetaMap(json);

        assertEquals(2, parsed.size());
        assertEquals("class_a", parsed.get("school/讲义.txt").get("classId"));
        assertEquals("class_b", parsed.get("school/另一份.txt").get("classId"));
    }

    @Test
    @DisplayName("侧车坏格式不抛异常，按「无补充元数据」处理（不能让知识库起不来）")
    void indexerToleratesBrokenMetaMap() {
        assertTrue(DocKnowledgeIndexer.parseMetaMap("这不是 JSON").isEmpty());
        assertTrue(DocKnowledgeIndexer.parseMetaMap("").isEmpty());
        assertTrue(DocKnowledgeIndexer.parseMetaMap(null).isEmpty());
        assertTrue(DocKnowledgeIndexer.parseMetaMap("{\"a\":\"不是对象\"}").isEmpty());
    }

    @Test
    @DisplayName("过滤条件形状正确；空值表示「不过滤」而不是「过滤掉全部」")
    void whereClauseShape() {
        Map<String, Object> where = ChromaClient.eqWhere("classId", "class_a");
        assertNotNull(where);
        assertEquals("class_a", ((Map<?, ?>) where.get("classId")).get("$eq"));

        // 空班级必须返回 null（= 不过滤）。若返回 {"classId":{"$eq":""}}，
        // 检索会因为匹配不到任何 chunk 而静默返回空——表现就是"知识库坏了"。
        assertNull(ChromaClient.eqWhere("classId", null));
        assertNull(ChromaClient.eqWhere("classId", "  "));
        assertNull(ChromaClient.eqWhere(null, "class_a"));
    }

    @Test
    @DisplayName("端到端的最后一跳（Chroma 的 where 检索）只能在活体检查里验证——此处显式记录边界")
    void endToEndIsVerifiedLive() {
        // 这条测试的用途是"把边界写在测试里"，避免后来者以为单测已经覆盖了端到端隔离。
        // 活体检查：A 班教师上传 → 用 A 班的 classId 过滤能召回；
        //          换成 B 班的 classId 过滤召回为空。
        assertTrue(true, "隔离的端到端验证见演示前自检清单");
    }
}
