package com.demo.router;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 意图质心的 TF-IDF 引擎：纯 Java 本地计算，零外部 API，单次判定 &lt; 0.1ms。
 *
 * <h3>为什么是 TF-IDF 而不是 embedding</h3>
 * 方案书 Prompt 3 记录了改造原因：DashScope embedding 额度耗尽 + L2 延迟敏感。
 * 本项目的 {@code EmbeddingService} 也有同样的云端欠费风险，
 * 而意图分类这种「短文本 → 少量固定类别」的任务，字符 n-gram + TF-IDF 质心已经足够，
 * 且完全离线、可解释、可持久化。
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>分词：字符 unigram（前缀 {@code u}）+ 字符 bigram（前缀 {@code b}），
 *       去空白与标点（{@code \s\p{P}\p{S}}，含中文标点）。</li>
 *   <li>IDF 平滑：{@code log((n+1)/(df+1)) + 1}（n=文档数，df=含该词的文档数），保证 IDF ≥ 1。</li>
 *   <li>文档向量：{@code tf = 词频/总词数}，加权 {@code tf*idf}，再做 L2 归一化。</li>
 *   <li>质心：同类文档向量求平均后再 L2 归一化，即该意图的「质心」。</li>
 *   <li>检索：query 向量 L2 归一化后与各质心做点积（等价于余弦相似度）。</li>
 * </ol>
 *
 * <h3>缓存与自愈</h3>
 * 落盘 {@code data/intent-tfidf.json}，文件里记录 <b>种子文件的 SHA-256</b>。
 * 种子改了哈希就不匹配 → 自动重建（无需手工删缓存，但删了也会重建）。
 * 这与本项目 {@code DocKnowledgeIndexer} 的指纹自愈思路一致。
 */
@Slf4j
@Component
public class IntentTfIdfStore {

    /** 缓存格式版本，算法变更时递增即可让旧缓存失效。 */
    private static final int CACHE_VERSION = 1;

    private final RouterProperties properties;
    private final ResourceLoader resourceLoader;

    /** 已构建的模型；构建失败时为 null，此时 {@link #isReady()} 返回 false。 */
    private volatile Model model;

    /** 模型来源："cache" / "rebuilt" / null。 */
    @Getter
    private volatile String source;

    public IntentTfIdfStore(RouterProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    // ======================== 生命周期 ========================

    /**
     * 启动预热。刻意<b>不</b>抛异常——种子文件损坏或缓存不可写都不能让应用起不来，
     * 最多让 L2 层失效（{@link #isReady()}=false），路由自动降级到 L3 / FALLBACK。
     */
    @PostConstruct
    public void init() {
        try {
            loadOrBuild();
        } catch (Exception e) {
            log.warn("【前置路由】L2 意图库构建失败，该层将自动跳过：{}", e.toString());
        }
    }

    /** 供 REST 手动触发重建（改完种子不想重启时用）。 */
    public synchronized void rebuild() {
        SeedBundle bundle = loadSeeds();
        Model built = build(bundle);
        persist(built);
        this.model = built;
        this.source = "rebuilt";
        log.info("【前置路由】L2 意图库已重建：{} 类 / {} 条种子 / {} 维词表",
                built.centroids.size(), built.docCount, built.terms.size());
    }

    private void loadOrBuild() throws IOException {
        SeedBundle bundle = loadSeeds();

        Path cachePath = Paths.get(properties.getTfidf().getStorePath());
        if (Files.exists(cachePath)) {
            Model cached = tryLoadCache(cachePath, bundle.hash());
            if (cached != null) {
                this.model = cached;
                this.source = "cache";
                log.info("【前置路由】L2 意图库已从缓存加载：{} 类 / {} 条种子 / {} 维词表 ← {}",
                        cached.centroids.size(), cached.docCount, cached.terms.size(), cachePath);
                return;
            }
            log.info("【前置路由】L2 缓存指纹不匹配（种子已改动或版本升级），重建中…");
        }
        rebuild();
    }

    // ======================== 种子加载 ========================

    /** 种子包：文本 + 每条的标签 + 内容哈希。 */
    private record SeedBundle(JSONObject raw, String hash) {}

    private SeedBundle loadSeeds() {
        String path = properties.getTfidf().getSeedPath();
        Resource resource = resourceLoader.getResource(path);
        String json;
        try {
            json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("意图种子读取失败: " + path, e);
        }
        JSONObject root = JSON.parseObject(json);
        return new SeedBundle(root, sha256(json));
    }

    // ======================== 构建 ========================

    private Model build(SeedBundle bundle) {
        JSONObject labels = bundle.raw().getJSONObject("labels");
        if (labels == null || labels.isEmpty()) {
            throw new IllegalStateException("意图种子缺少 labels 节点");
        }

        // 1) 展开成 (标签, 文本) 列表；未知标签直接跳过并告警（防止枚举改名后静默失效）
        List<IntentLabel> docLabels = new ArrayList<>();
        List<String> docTexts = new ArrayList<>();
        for (String key : labels.keySet()) {
            IntentLabel label = IntentLabel.parse(key);
            if (label == null) {
                log.warn("【前置路由】种子里的意图标签 [{}] 在 IntentLabel 中不存在，已跳过", key);
                continue;
            }
            JSONArray arr = labels.getJSONArray(key);
            if (arr == null) continue;
            for (int i = 0; i < arr.size(); i++) {
                String text = arr.getString(i);
                if (text == null || text.isBlank()) continue;
                docLabels.add(label);
                docTexts.add(text);
            }
        }
        if (docTexts.isEmpty()) {
            throw new IllegalStateException("意图种子为空");
        }

        // 2) 文档频率 df
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Integer>> docTermCounts = new ArrayList<>(docTexts.size());
        for (String text : docTexts) {
            List<String> tokens = tokenize(text);
            Map<String, Integer> counts = new HashMap<>();
            for (String t : tokens) {
                counts.merge(t, 1, Integer::sum);
            }
            docTermCounts.add(counts);
            for (String t : counts.keySet()) {
                df.merge(t, 1, Integer::sum);
            }
        }

        int n = docTexts.size();
        // 3) IDF（平滑，保证 ≥ 1）
        Map<String, Double> idf = new HashMap<>(df.size() * 2);
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }

        // 词表固定为有序列表，持久化后按下标还原
        List<String> terms = new ArrayList<>(idf.keySet());
        terms.sort(Comparator.naturalOrder());

        // 4) 文档向量（L2 归一化）
        Map<IntentLabel, double[]> sums = new HashMap<>();
        Map<IntentLabel, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double[] vec = toVector(docTermCounts.get(i), idf, terms);
            IntentLabel label = docLabels.get(i);
            double[] acc = sums.computeIfAbsent(label, k -> new double[terms.size()]);
            for (int d = 0; d < vec.length; d++) {
                acc[d] += vec[d];
            }
            counts.merge(label, 1, Integer::sum);
        }

        // 5) 质心（平均后再归一化）
        Map<IntentLabel, double[]> centroids = new HashMap<>();
        for (Map.Entry<IntentLabel, double[]> e : sums.entrySet()) {
            double[] acc = e.getValue();
            int c = counts.getOrDefault(e.getKey(), 1);
            for (int d = 0; d < acc.length; d++) {
                acc[d] /= c;
            }
            l2Normalize(acc);
            centroids.put(e.getKey(), acc);
        }

        return new Model(terms, idf, centroids, bundle.hash(), n);
    }

    private static double[] toVector(Map<String, Integer> termCounts, Map<String, Double> idf,
                                     List<String> terms) {
        double[] vec = new double[terms.size()];
        int total = termCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return vec;
        for (int i = 0; i < terms.size(); i++) {
            Integer c = termCounts.get(terms.get(i));
            if (c == null) continue;
            double tf = (double) c / total;
            vec[i] = tf * idf.getOrDefault(terms.get(i), 1.0);
        }
        l2Normalize(vec);
        return vec;
    }

    private static void l2Normalize(double[] vec) {
        double sum = 0;
        for (double v : vec) {
            sum += v * v;
        }
        if (sum <= 0) return;
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) {
            vec[i] /= norm;
        }
    }

    // ======================== 检索 ========================

    /** 单个意图的相似度。 */
    public record IntentScore(IntentLabel label, double similarity) {}

    /**
     * 按相似度降序返回所有意图的得分。
     *
     * @return 未就绪时返回空列表
     */
    public List<IntentScore> rank(String text) {
        Model m = this.model;
        if (m == null || text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokenize(text)) {
            counts.merge(t, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        double[] q = toVector(counts, m.idf, m.terms);

        List<IntentScore> out = new ArrayList<>(m.centroids.size());
        for (Map.Entry<IntentLabel, double[]> e : m.centroids.entrySet()) {
            out.add(new IntentScore(e.getKey(), dot(q, e.getValue())));
        }
        out.sort(Comparator.comparingDouble(IntentScore::similarity).reversed());
        return out;
    }

    private static double dot(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < len; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /**
     * 分词：字符 unigram + bigram。
     * <p>单字能兜住「猫」「雨」这类短查询，双字能兜住「天气」「火车票」这类语义单元，
     * 两者靠前缀区分，不会互相污染。这与 {@code UserMemoryService} 里
     * bigram-Jaccard 去重用的是同一套字符切分思路。
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String s = text.replaceAll("[\\s\\p{P}\\p{S}]+", "");
        if (s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            out.add("u" + s.charAt(i));
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add("b" + s.charAt(i) + s.charAt(i + 1));
        }
        return out;
    }

    // ======================== 持久化 ========================

    private void persist(Model m) {
        try {
            Path path = Paths.get(properties.getTfidf().getStorePath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            JSONObject root = new JSONObject();
            root.put("version", CACHE_VERSION);
            root.put("seedHash", m.seedHash);
            root.put("docCount", m.docCount);
            root.put("terms", m.terms);
            JSONArray idfArr = new JSONArray(m.terms.size());
            for (String t : m.terms) {
                idfArr.add(m.idf.getOrDefault(t, 1.0));
            }
            root.put("idf", idfArr);
            JSONObject centroids = new JSONObject();
            for (Map.Entry<IntentLabel, double[]> e : m.centroids.entrySet()) {
                JSONArray arr = new JSONArray(e.getValue().length);
                for (double v : e.getValue()) {
                    arr.add(v);
                }
                centroids.put(e.getKey().name(), arr);
            }
            root.put("centroids", centroids);
            Files.writeString(path, root.toJSONString());
        } catch (Exception e) {
            log.warn("【前置路由】L2 缓存落盘失败（不影响运行）：{}", e.toString());
        }
    }

    private Model tryLoadCache(Path cachePath, String expectedHash) {
        try {
            JSONObject root = JSON.parseObject(Files.readString(cachePath));
            if (root == null) return null;
            if (root.getIntValue("version", -1) != CACHE_VERSION) return null;
            if (!expectedHash.equals(root.getString("seedHash"))) return null;

            List<String> terms = root.getJSONArray("terms").toJavaList(String.class);
            JSONArray idfArr = root.getJSONArray("idf");
            Map<String, Double> idf = new HashMap<>(terms.size() * 2);
            for (int i = 0; i < terms.size(); i++) {
                idf.put(terms.get(i), idfArr.getDoubleValue(i));
            }
            Map<IntentLabel, double[]> centroids = new HashMap<>();
            JSONObject cobj = root.getJSONObject("centroids");
            for (String key : cobj.keySet()) {
                IntentLabel label = IntentLabel.parse(key);
                if (label == null) continue;
                JSONArray arr = cobj.getJSONArray(key);
                double[] vec = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    vec[i] = arr.getDoubleValue(i);
                }
                centroids.put(label, vec);
            }
            if (centroids.isEmpty() || terms.isEmpty()) return null;
            return new Model(terms, idf, centroids, expectedHash, root.getIntValue("docCount", 0));
        } catch (Exception e) {
            log.warn("【前置路由】L2 缓存读取失败，将重建：{}", e.toString());
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 一定存在；退化成按内容长度做弱校验
            return "len:" + s.length();
        }
    }

    // ======================== 只读视图 ========================

    /** 模型是否可用。 */
    public boolean isReady() {
        return model != null;
    }

    /** 意图类别数。 */
    public int labelCount() {
        Model m = model;
        return m == null ? 0 : m.centroids.size();
    }

    /** 参与训练的种子条数。 */
    public int seedCount() {
        Model m = model;
        return m == null ? 0 : m.docCount;
    }

    /** 词表大小。 */
    public int vocabSize() {
        Model m = model;
        return m == null ? 0 : m.terms.size();
    }

    /** 不可变模型快照。 */
    private record Model(List<String> terms, Map<String, Double> idf,
                         Map<IntentLabel, double[]> centroids, String seedHash, int docCount) {}
}
