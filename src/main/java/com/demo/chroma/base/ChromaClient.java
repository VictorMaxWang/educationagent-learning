package com.demo.chroma.base;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chroma REST API 通用客户端
 * <p>
 * 封装 Chroma 向量数据库的集合管理、文档添加、相似度查询等基础操作。
 * UserMemoryService 和 TravelKnowledgeService 共用此客户端。
 */
@Slf4j
@Service
public class ChromaClient {

    @Value("${memory.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    /**
     * 单次 {@code /add} 请求最多提交多少条。
     *
     * <p>1024 维向量按 JSON double 序列化后每条约 20KB，500 条约 10MB —— 远低于
     * fastjson2 的 64MB 上限，也不会让 Chroma 掐连接。别调得太大：
     * 实测整库一次提交（3224 条 ≈ 64MB）必然失败。
     */
    @Value("${memory.chroma.add-batch-size:500}")
    private int addBatchSize;

    @Resource
    private RestTemplate restTemplate;

    /** 本地缓存集合 ID（避免每次请求查 Chroma） */
    private final ConcurrentHashMap<String, String> collectionCache = new ConcurrentHashMap<>();

    // ======================== 集合管理 ========================

    /**
     * 按集合名精确查找集合 ID（只读，<b>绝不创建集合</b>）。
     *
     * <p><b>为什么不用 {@code /collections?name=xxx}</b>：该查询参数在 Chroma 1.x 上被
     * <b>静默忽略</b> —— 实测传一个根本不存在的名字，返回的仍是库里全部集合。
     * 正确做法是 {@code GET /collections/{name}}，由服务端按名解析，不存在时返回 404。</p>
     *
     * @return 集合 ID；集合<b>不存在</b>返回 null。
     *         Chroma 不可达时<b>抛异常</b>（不吞掉），让调用方能区分「集合不存在」与「Chroma 挂了」
     */
    public String findCollectionId(String collectionName) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionName,
                    String.class);
            JSONObject found = JSON.parseObject(response.getBody());
            if (found == null) return null;
            String id = found.getString("id");
            return (id == null || id.isBlank()) ? null : id;
        } catch (HttpClientErrorException e) {
            // 任意 4xx 都视为「拿不到这个集合」。Chroma 各端点不一致：
            // /collections/{name} 不存在时返 404，而 /collections/{id}/count 返 400，
            // 只捕 NotFound 会把 400 漏成「Chroma 不可达」这种误导性文案
            return null;
        }
    }

    /**
     * 获取或创建 Chroma Collection
     *
     * @param collectionName 集合名称
     * @return collectionId（UUID），失败返回 null
     */
    public String getOrCreateCollection(String collectionName) {
        String cached = collectionCache.get(collectionName);
        if (cached != null) return cached;

        // 按名精确查找。历史实现走的是 /collections?name=xxx 后取 list[0]，
        // 而该参数会被 Chroma 静默忽略 —— 于是任意集合名都会解析成「库里第一个集合」的 ID，
        // 并被永久写进 collectionCache。后果是 deleteCollection/clearCollection 会误删第一个集合
        // （今天是 user_memory_console，即全部用户长期记忆）。必须用路径参数按名查找。
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionName,
                    String.class);
            JSONObject found = JSON.parseObject(response.getBody());
            if (found != null) {
                String id = found.getString("id");
                if (id != null && !id.isBlank()) {
                    collectionCache.put(collectionName, id);
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("Chroma 查找集合 {} 失败，尝试创建", collectionName);
        }

        // 创建新集合
        try {
            JSONObject body = new JSONObject();
            body.put("name", collectionName);
            JSONObject metadata = new JSONObject();
            metadata.put("hnsw:space", "cosine");
            body.put("metadata", metadata);

            ResponseEntity<String> createResponse = restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);

            JSONObject result = JSON.parseObject(createResponse.getBody());
            String id = result.getString("id");
            collectionCache.put(collectionName, id);
            log.info("📁 Chroma 集合已创建：{} -> {}", collectionName, id);
            return id;
        } catch (Exception e) {
            log.error("创建 Chroma 集合失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 删除集合（用于重建索引场景）
     */
    public boolean deleteCollection(String collectionName) {
        try {
            // 幂等：集合不存在就直接返回（顺带给出「无需删除」的明确日志）。
            // 注意 findCollectionId 这里只用于存在性判断 —— 真正的删除请求必须按【名字】发。
            String existingId = findCollectionId(collectionName);
            if (existingId == null) {
                collectionCache.remove(collectionName);
                log.info("🗑 Chroma 集合 {} 不存在，无需删除", collectionName);
                return true;
            }
            // ⚠️ Chroma 的端点寻址规则不一致，这是本 bug 的根源：
            //   • 裸 /collections/{x}            → 只认【名字】，传 UUID 一律 404
            //     （实测：GET 名字→200、GET id→404、DELETE id→404 且集合仍在、DELETE 名字→200）
            //   • /collections/{x}/{action}      → 【id 和名字都可以】
            //     （实测 /collections/{id}/add、/{id}/get、/{id}/count 均正常）
            // 所以只有本方法（裸 DELETE）受影响。历史实现传 id，于是「删旧集合重建」
            // 从来没删掉过任何东西：重建只会往旧集合追加，换 embedding 维度也无法迁移
            // （旧维度永远留着）。该 bug 长期不可见，只因异常被吞在 WARN 级。
            restTemplate.delete(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionName);
            collectionCache.remove(collectionName);
            log.info("🗑 Chroma 集合已删除：{}", collectionName);
            return true;
        } catch (Exception e) {
            log.error("删除 Chroma 集合失败（{}）: {}", collectionName, e.getMessage(), e);
            return false;
        }
    }

    // ======================== 文档操作 ========================

    /**
     * 批量添加文档（带向量）。
     *
     * <p>返回布尔值而非 void：写入失败必须让调用方能判断出来。历史实现是 void + 吞异常，
     * 导致索引器「写完就报成功」——实测出现过 embedding 全部失败（账户欠费）时
     * 一个向量都没写入，索引器却报「重建完成」并置 DONE、写下指纹，
     * 于是下次启动直接跳过，永久卡在「已完成 + 空集合」。</p>
     *
     * @return true 表示写入成功；false 表示失败（已记 ERROR 日志）
     */
    public boolean addDocuments(String collectionId, List<String> ids, List<List<Double>> embeddings,
                                List<String> documents, List<Map<String, Object>> metadatas) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Chroma 添加文档：空批次，跳过（集合={}）", collectionId);
            return false;
        }

        // 分批提交。一次把整库塞进一个请求会同时踩两个坑（实测）：
        //   ① fastjson2 序列化 64MB 上限 —— 3224 条 1024 维向量约 64MB，
        //      报 "Maximum array size exceeded. Requested size: 67108880, max size: 67108864"；
        //   ② 即使勉强序列化出来，Chroma 侧也会因为请求体过大直接掐断连接
        //      （"你的主机中的软件中止了一个已建立的连接"）。
        // 两者都表现为"入库失败 → 置 FAILED → 每分钟重试"，而且永远好不了。
        int batch = Math.max(1, addBatchSize);
        int total = ids.size();
        if (total <= batch) {
            return postBatch(collectionId, ids, embeddings, documents, metadatas, 0, total);
        }

        boolean allOk = true;
        int failed = 0;
        for (int from = 0; from < total; from += batch) {
            int to = Math.min(total, from + batch);
            if (!postBatch(collectionId, ids, embeddings, documents, metadatas, from, to)) {
                allOk = false;
                failed++;
            }
        }
        if (allOk) {
            log.info("Chroma 分批写入完成：集合={} 共 {} 条（每批 {}）", collectionId, total, batch);
        } else {
            log.error("Chroma 分批写入存在失败：集合={} 共 {} 条，失败 {} 批", collectionId, total, failed);
        }
        return allOk;
    }

    /** 提交一批。{@code [from, to)} 为闭开区间；各列表长度不一致时按短的截断。 */
    private boolean postBatch(String collectionId, List<String> ids, List<List<Double>> embeddings,
                              List<String> documents, List<Map<String, Object>> metadatas,
                              int from, int to) {
        List<String> subIds = ids.subList(from, Math.min(to, ids.size()));
        int n = subIds.size();
        try {
            JSONObject body = new JSONObject();
            body.put("ids", subIds);
            body.put("embeddings", slice(embeddings, from, n));
            body.put("documents", slice(documents, from, n));
            List<Map<String, Object>> meta = (metadatas == null || metadatas.isEmpty())
                    ? null : slice(metadatas, from, n);
            if (meta != null && !meta.isEmpty()) {
                body.put("metadatas", meta);
            }

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/add",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.error("Chroma 添加文档非 2xx：HTTP {}（集合={}, 区间=[{},{}), 条数={}）",
                        resp.getStatusCode().value(), collectionId, from, to, n);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Chroma 添加文档失败（集合={}, 区间=[{},{}), 条数={}）: {}",
                    collectionId, from, to, n, e.getMessage(), e);
            return false;
        }
    }

    /** 安全切片：null 原样返回；越界按实际长度收窄（各列表长度可能不完全对齐）。 */
    private static <T> List<T> slice(List<T> src, int from, int count) {
        if (src == null) return null;
        if (from >= src.size()) return List.of();
        return src.subList(from, Math.min(from + count, src.size()));
    }

    /**
     * 添加单条文档（自动生成 UUID ID）
     *
     * @return true 表示写入成功
     */
    public boolean addDocument(String collectionId, List<Double> embedding, String document,
                               Map<String, Object> metadata) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return addDocuments(collectionId, List.of(id), List.of(embedding), List.of(document),
                metadata != null ? List.of(metadata) : null);
    }

    // ======================== 向量查询 ========================

    /**
     * 向量相似度查询
     *
     * @param collectionId   集合 ID
     * @param queryEmbedding 查询向量（double）
     * @param topK           返回前 N 条
     * @return 匹配的 documents 文本列表
     */
    public List<String> query(String collectionId, List<Double> queryEmbedding, int topK) {
        try {
            JSONObject body = new JSONObject();
            body.put("query_embeddings", List.of(queryEmbedding));
            body.put("n_results", Math.min(topK, 20));

            ResponseEntity<String> response = restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/query",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);

            return parseQueryResult(response.getBody());
        } catch (Exception e) {
            log.warn("Chroma 查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 向量相似度查询（带元数据 + 距离）
     *
     * <p>法律 RAG 专用：返回 [{content, distance, metadata}]，
     * 供检索结果展示相关度、来源等信息。与 {@link #query} 共用 Chroma query 接口。</p>
     *
     * @param collectionId   集合 ID
     * @param queryEmbedding 查询向量（double）
     * @param topK           返回前 N 条
     * @return [{content, distance, metadata}] 列表，异常返回空列表
     */
    public List<Map<String, Object>> queryDetail(String collectionId, List<Double> queryEmbedding, int topK) {
        return queryDetail(collectionId, queryEmbedding, topK, null);
    }

    /**
     * 带<b>元数据过滤</b>的检索（按班/按权限隔离）。
     *
     * <p>关键点：过滤条件<b>随查询一起下发</b>（Chroma 的 {@code where}），而不是"取回结果后再剔除"。
     * 后者有两个问题：无权文档仍会被召回、只是没返回；而且它们会占掉 topK 名额，
     * 把真正属于本班的相关内容挤出结果集。
     *
     * @param where 元数据过滤条件，语法与 {@link #deleteByWhere} 一致：
     *              {@code {"classId": {"$eq": "class_demo_1"}}}（可用 {@link #eqWhere} 构造）；
     *              {@code null} 或空表示不过滤
     */
    public List<Map<String, Object>> queryDetail(String collectionId, List<Double> queryEmbedding,
                                                 int topK, Map<String, Object> where) {
        try {
            JSONObject body = new JSONObject();
            body.put("query_embeddings", List.of(queryEmbedding));
            body.put("n_results", Math.min(topK, 20));
            if (where != null && !where.isEmpty()) {
                body.put("where", where);
            }

            ResponseEntity<String> response = restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/query",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);

            return parseQueryDetail(response.getBody());
        } catch (Exception e) {
            log.warn("Chroma 查询(带元数据)失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构造等值过滤条件：{@code eqWhere("classId", "class_demo_1")}
     * → {@code {"classId": {"$eq": "class_demo_1"}}}。
     *
     * @return 条件为 null/空时返回 {@code null}（表示不过滤，而不是"过滤掉所有"）
     */
    public static Map<String, Object> eqWhere(String key, Object value) {
        if (key == null || key.isBlank() || value == null) return null;
        String v = String.valueOf(value).trim();
        if (v.isEmpty()) return null;
        return Map.of(key, Map.of("$eq", v));
    }

    /** 解析 Chroma query 返回：documents + distances + metadatas → [{content, distance, metadata}] */
    private List<Map<String, Object>> parseQueryDetail(String responseBody) {
        try {
            JSONObject result = JSON.parseObject(responseBody);
            JSONArray documents = result.getJSONArray("documents");
            if (documents == null || documents.isEmpty()) return Collections.emptyList();

            JSONArray distArray = result.getJSONArray("distances") != null
                    ? result.getJSONArray("distances").getJSONArray(0) : null;
            JSONArray metaArray = result.getJSONArray("metadatas") != null
                    ? result.getJSONArray("metadatas").getJSONArray(0) : null;
            JSONArray docArray = documents.getJSONArray(0);
            if (docArray == null) return Collections.emptyList();

            List<Map<String, Object>> out = new ArrayList<>();
            for (int j = 0; j < docArray.size(); j++) {
                String text = docArray.getString(j);
                if (text == null || text.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("content", text);
                m.put("distance", distArray != null && j < distArray.size()
                        ? distArray.getDoubleValue(j) : 9.0);
                m.put("metadata", metaArray != null && j < metaArray.size()
                        ? metaArray.getJSONObject(j) : null);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 Chroma 查询详情失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ======================== 集合统计与取样 ========================

    /**
     * 集合内文档总数。
     *
     * <p>Chroma 的 /count 接口返回的是<b>裸整数</b>（不是 {"count":N}）——
     * 官方客户端 chromadb/api/fastapi.py 的 _count 里就是 {@code return cast(int, resp_json)}。</p>
     *
     * @return 文档总数；<b>-1 表示查询失败</b>（与「集合为空」的 0 严格区分）
     */
    public int count(String collectionId) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/count",
                    String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) return -1;
            return Integer.parseInt(body.trim());
        } catch (Exception e) {
            log.warn("Chroma 统计集合文档数失败: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 按 offset 分页取文档（用于随机抽样 chunk，反向生成评估用例）。
     *
     * <p><b>结构差异</b>：/get 返回的是扁平数组 {ids:[...], documents:[...], metadatas:[...]}，
     * 而 /query 返回的是嵌套数组 documents:[[...]]。两者不能共用解析逻辑，
     * 所以这里走独立的 {@link #parseGetResult}，不要改成复用 parseQueryDetail。</p>
     *
     * @param limit  取几条
     * @param offset 跳过几条
     * @return [{content, metadata}]，异常返回空列表
     */
    public List<Map<String, Object>> getDocuments(String collectionId, int limit, int offset) {
        try {
            JSONObject body = new JSONObject();
            body.put("limit", limit);
            body.put("offset", offset);
            body.put("include", List.of("documents", "metadatas"));

            ResponseEntity<String> response = restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/get",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);

            return parseGetResult(response.getBody());
        } catch (Exception e) {
            log.warn("Chroma 取样文档失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 解析 Chroma /get 的扁平结构：documents[i] 与 metadatas[i] 按下标一一对应 */
    private List<Map<String, Object>> parseGetResult(String responseBody) {
        try {
            JSONObject result = JSON.parseObject(responseBody);
            JSONArray documents = result.getJSONArray("documents");
            if (documents == null || documents.isEmpty()) return Collections.emptyList();

            JSONArray metaArray = result.getJSONArray("metadatas");

            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                String text = documents.getString(i);
                if (text == null || text.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("content", text);
                m.put("metadata", metaArray != null && i < metaArray.size()
                        ? metaArray.getJSONObject(i) : null);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 Chroma 取样结果失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ======================== 按条件删除 ========================

    /**
     * 按元数据条件删除文档（用于删除某用户知识库中指定文件的全部向量）。
     * Chroma where 语法：{"filePath": {"$eq": "/abs/path"}} 或 {"filePath": "/abs/path"}。
     */
    public boolean deleteByWhere(String collectionId, Map<String, Object> where) {
        try {
            JSONObject body = new JSONObject();
            body.put("where", where);
            restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/delete",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);
            log.info("🗑 Chroma 删除文档（where={}）", where);
            return true;
        } catch (Exception e) {
            log.error("Chroma 按条件删除失败（where={}）: {}", where, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 清空集合内全部文档（空 where 删除所有）。
     *
     * @deprecated <b>在 Chroma 1.x 上必然失败</b>，请改用 {@link #deleteCollection(String)} + 重新
     *     {@link #getOrCreateCollection(String)} 来重建集合。实测本方法发送的 {@code {"where":{}}}
     *     会被服务端拒绝：HTTP 400 {@code InvalidArgumentError: Invalid where clause}。
     *     另外「只清空不删集合」会保留集合的旧向量维度，换 embedding 模型后写入会被拒
     *     （{@code Collection expecting embedding with dimension of X, got Y}），
     *     而删掉重建能自动适配新维度。当前已无调用方，保留仅为兼容。
     */
    @Deprecated
    public boolean clearCollection(String collectionId) {
        try {
            JSONObject body = new JSONObject();
            body.put("where", new JSONObject());
            restTemplate.postForEntity(
                    chromaUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collectionId + "/delete",
                    new HttpEntity<>(body.toJSONString(), jsonHeaders()),
                    String.class);
            log.info("🧹 Chroma 已清空集合 {} 全部文档", collectionId);
            return true;
        } catch (Exception e) {
            // 清空失败必须暴露：否则旧向量残留会让后续「写入条数 == 集合总数」的校验失真
            log.error("Chroma 清空集合失败（{}）: {}", collectionId, e.getMessage(), e);
            return false;
        }
    }

    // ======================== 工具方法 ========================

    /** 解析 Chroma query 返回结果中的 documents */
    private List<String> parseQueryResult(String responseBody) {
        try {
            JSONObject result = JSON.parseObject(responseBody);
            JSONArray documents = result.getJSONArray("documents");
            if (documents == null || documents.isEmpty()) return Collections.emptyList();

            List<String> facts = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                JSONArray docArray = documents.getJSONArray(i);
                if (docArray != null) {
                    for (int j = 0; j < docArray.size(); j++) {
                        String fact = docArray.getString(j);
                        if (fact != null && !fact.isBlank()) {
                            facts.add(fact);
                        }
                    }
                }
            }
            return facts;
        } catch (Exception e) {
            log.warn("解析 Chroma 查询结果失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        return headers;
    }
}
