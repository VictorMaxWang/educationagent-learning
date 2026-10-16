package com.demo.edu.classroom.evaluation;

import com.demo.chroma.base.EmbeddingService;
import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.recommendation.*;
import com.demo.edu.classroom.recommendation.RecommendationOptimizer.*;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.edu.classroom.study.feedback.LearningFeedbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;

/**
 * 工程评测入口。生产分类器、反馈语义入口、推荐编排和优化器是被测对象，
 * 内存课程/存储/固定检索快照只是输入适配，不产生模拟模型回答或虚构视频。
 * 预期标签均为未人工复核的工程夹具，目标函数值只衡量该有限模型，不能称教学效果。
 */
public final class ResearchEvaluationCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASELINE_VERSION = "same-pool-feasible-baselines-v1";
    static final String HARNESS_REVISION = "research-eval-20261016-r2-budget-catalog-firsthit";
    private static String runPurpose="development-engineering-check";
    private static final String LABEL_SCOPE = "agent-authored-engineering-expectations; not human reviewed; not student outcomes";
    private ResearchEvaluationCli() {}

    public static void main(String[] argv) throws Exception {
        Map<String,String> args = arguments(argv);
        runPurpose=args.getOrDefault("run-purpose","development-engineering-check");
        if(!Set.of("development-engineering-check","contract-repair-regression","fixed-input-replay","fixture-validation").contains(runPurpose))
            throw new IllegalArgumentException("unknown run purpose");
        String mode = args.getOrDefault("mode", "offline-fixtures");
        Path fixtures = Path.of(args.getOrDefault("fixtures", "src/test/resources/research-evaluation"));
        Path output = Path.of(args.getOrDefault("output", "target/research-upgrade-20261016/evaluation/cli"));
        Files.createDirectories(output);
        if (mode.equals("offline-fixtures")) { offline(fixtures, output); return; }
        if (mode.equals("replay")) { replay(Path.of(required(args,"decisions")), output); return; }
        AiChatProperties properties = new AiChatProperties(new StandardEnvironment());
        if (!properties.hasApiKey()) throw new IllegalStateException("AI_CHAT_API_KEY is required for live mode; no offline answer substitution");
        try (ClassroomAiGateway gateway = new ClassroomAiGateway(properties)) {
            int parallel = Math.max(1, Math.min(2, Integer.parseInt(args.getOrDefault("parallel", "1"))));
            switch (mode) {
                case "classify-live" -> classify(fixtures, output, gateway, parallel);
                case "feedback-live" -> feedback(fixtures, output, gateway, parallel);
                case "features-live" -> features(Path.of(required(args,"snapshots")), output, gateway);
                default -> throw new IllegalArgumentException("unknown mode");
            }
        }
    }

    static void offline(Path fixtures, Path output) throws Exception {
        ObjectNode result = base("OFFLINE_FIXTURE_VALIDATION_ONLY");
        for (String name : List.of("classification-72.json", "feedback-40.json")) {
            JsonNode file = JSON.readTree(fixtures.resolve(name).toFile());
            Set<String> ids = new HashSet<>();
            Map<String,Integer> categories = new TreeMap<>();
            for (JsonNode item : file.path("cases")) {
                if (!ids.add(item.path("id").asText()) || item.path("labelProvenance").path("humanReviewed").asBoolean())
                    throw new IllegalStateException("duplicate case or mislabeled review provenance");
                var context = ClassroomStudyContext.from(item.path("course"),item.path("sceneId").asText());
                if (context.courseId().isBlank()) throw new IllegalStateException("fixture course missing");
                String category = item.path("category").asText(item.path("stage").asText()+":"+item.path("expectedStatus").asText());
                categories.merge(category,1,Integer::sum);
            }
            if (ids.size() != (name.startsWith("classification") ? 72 : 40)) throw new IllegalStateException("fixture denominator mismatch");
            result.putObject(name).put("count",ids.size()).set("categories",JSON.valueToTree(categories));
        }
        result.put("modelCalls",0).put("semanticAccuracyMeasured",false).put("note","Only fixture structure and source context were validated; model behavior is NOT_RUN.");
        write(output.resolve("offline-fixtures.json"),result);
        System.out.println("OFFLINE fixtures: 72 classification, 40 feedback; model results NOT_RUN");
    }

    static void classify(Path fixtures, Path output, ClassroomAiGateway gateway, int parallel) throws Exception {
        VideoTeachingClassifier classifier = new VideoTeachingClassifier(gateway,JSON);
        runCases(JSON.readTree(fixtures.resolve("classification-72.json").toFile()).path("cases"),output,"classification",parallel,item -> {
            ObjectNode row = caseBase(item,"LIVE_PRODUCTION_CLASSIFIER");
            try {
                var value = classifier.classify(ClassroomStudyContext.from(item.path("course"),item.path("sceneId").asText()));
                row.put("actualStatus",value.status()).put("matchesEngineeringExpectation",value.status().equals(item.path("expectedStatus").asText()));
                row.set("result",value.publicValue(JSON));row.set("modelEvidence",value.evidence());
                row.put("status","completed").put("modelCalled",!value.evidence().path("mode").asText().equals("rule"));
            } catch (RuntimeException error) { failure(row,error); }
            return row;
        });
    }

    static LearningFeedbackService feedbackService(ClassroomAiGateway gateway, ClassroomCourseService courses, ClassroomStudyStore store) {
        return new LearningFeedbackService(courses,store,gateway,null,JSON,mock(PlatformTransactionManager.class));
    }

    static void feedback(Path fixtures, Path output, ClassroomAiGateway gateway, int parallel) throws Exception {
        runCases(JSON.readTree(fixtures.resolve("feedback-40.json").toFile()).path("cases"),output,"feedback",parallel,item -> {
            ObjectNode row = caseBase(item,"LIVE_PRODUCTION_FEEDBACK");
            var context = ClassroomStudyContext.from(item.path("course"),item.path("sceneId").asText());
            try {
                if (item.path("requiresVersionCheck").asBoolean()) {
                    // 真正调用保存/核对流程验证stale；资料及存储为固定夹具，模型不应被调用。
                    ClassroomCourseService courses = mock(ClassroomCourseService.class);
                    ObjectNode oldCourse = item.path("course").deepCopy();oldCourse.put("sourceText",item.path("priorSourceText").asText());
                    when(courses.studySnapshot(context.courseId())).thenReturn(oldCourse);
                    LearningFeedbackService service = feedbackService(gateway,courses,new MemoryStore());
                    ObjectNode saved = service.create(context.courseId(),new LearningFeedbackService.CreateRequest("scene-1",item.path("understanding").asText(),item.path("checkPoint").asText(),null,null,item.path("id").asText()));
                    when(courses.studySnapshot(context.courseId())).thenReturn((ObjectNode)item.path("course"));
                    ObjectNode checked = service.check(saved.path("id").asText(),new LearningFeedbackService.CheckRequest("evaluation-check"));
                    row.put("actualStatus",checked.path("status").asText()).put("modelCalled",false).set("result",checked);
                    row.put("matchesEngineeringExpectation",checked.path("status").asText().equals("stale"));
                } else {
                    LearningFeedbackService service = feedbackService(gateway,null,null);
                    var checked = service.evaluateUnderstanding(context,item.path("understanding").asText(),item.path("checkPoint").asText());
                    Set<String> actual = new TreeSet<>();checked.value().path("analysis").forEach(a -> actual.add(a.path("status").asText()));
                    Set<String> expected = strings(item.path("expectedStatuses"));
                    row.set("actualStatuses",JSON.valueToTree(actual));row.put("matchesEngineeringExpectation",actual.equals(expected));
                    row.set("result",checked.value());row.set("modelEvidence",checked.evidence());row.put("modelCalled","live".equals(checked.evidence().path("mode").asText()));
                }
                row.put("status","completed");
            } catch (ClassroomException error) {
                if (item.path("noEvidence").asBoolean() && error.status() == 422)
                    row.put("status","completed").put("actualStatus","uncertain").put("modelCalled",false).put("matchesEngineeringExpectation",true).put("reason","PRODUCTION_SOURCE_ABSTENTION");
                else failure(row,error);
            } catch (RuntimeException error) { failure(row,error); }
            return row;
        });
    }

    static void features(Path snapshots, Path output, ClassroomAiGateway gateway) throws Exception {
        // 此适配器只固定检索返回，不改变实际分类、语义匹配、向量服务及优化器。
        MemoryStore sharedVectors = new MemoryStore();
        ResourceSemanticFeatures semantic = new ResourceSemanticFeatures(embeddingFromEnvironment(),sharedVectors,JSON);
        try (Stream<Path> stream = Files.list(snapshots)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().matches("query-\\d+\\.json")).sorted().toList()) {
                JsonNode source = JSON.readTree(path.toFile());
                Path destination = output.resolve(source.path("id").asText()+".json");
                if (Files.exists(destination)) continue;
                ObjectNode report = base("LIVE_PRODUCTION_FEATURE_EXTRACTION_FROM_FIXED_METADATA");
                report.put("id",source.path("id").asText()).put("query",source.path("query").asText()).put("searchStatus",source.path("status").asText());
                report.put("sourceSnapshot",path.toAbsolutePath().toString()).put("courseContextOrigin","agent-authored query context, not a student record");
                if (!source.path("status").asText().equals("ok")) {
                    report.put("status","source_failed");write(destination,report);continue;
                }
                try {
                    List<BilibiliVideoCatalog.Video> videos = snapshotVideos(source);
                    ObjectNode course = queryCourse(source);
                    ClassroomCourseService courses = mock(ClassroomCourseService.class);
                    when(courses.studySnapshot(course.path("id").asText())).thenReturn(course);
                    when(courses.hasActiveGeneration()).thenReturn(false);
                    MemoryStore store = new MemoryStore();
                    FixedSnapshotCatalog catalog = new FixedSnapshotCatalog(source,videos);
                    try (var service = new ClassroomRecommendationService(courses,store,JSON,new VideoTeachingClassifier(gateway,JSON),catalog,semantic,gateway)) {
                        ObjectNode queued = service.refresh(course.path("id").asText(),new ClassroomRecommendationService.RefreshRequest("scene-1",10,""));
                        String id = queued.path("decisionId").asText();
                        long deadline = System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
                        Optional<ObjectNode> decision;
                        do {Thread.sleep(200);decision=store.find("recommendation_decision",id);} while(decision.isEmpty()&&System.nanoTime()<deadline);
                        if (decision.isEmpty()) report.put("status","feature_timeout");
                        else {
                            ObjectNode actual = decision.get();report.set("decision",actual);
                            report.put("status",actual.path("audit").has("optimizerInput") ? "features_ready" : actual.path("status").asText());
                        }
                        report.putObject("fixedCatalogCalls").put("twoArgument",catalog.legacyCalls.get()).put("budgetArgument",catalog.budgetCalls.get())
                                .put("liveSearchRequests",0).put("snapshotVideos",videos.size());
                    }
                } catch (RuntimeException error) { failure(report,error); }
                write(destination,report);
                System.out.println(source.path("id").asText()+" "+report.path("status").asText());
            }
        }
        replay(output,output.resolve("replay"));
    }

    /** 两个公开签名均截获：新增Duration预算不能意外落回父类网络实现。 */
    static final class FixedSnapshotCatalog extends BilibiliVideoCatalog {
        final AtomicInteger legacyCalls=new AtomicInteger(),budgetCalls=new AtomicInteger();
        private final JsonNode source;private final List<Video> videos;
        FixedSnapshotCatalog(JsonNode source,List<Video> videos){super(JSON,null);this.source=source.deepCopy();this.videos=List.copyOf(videos);}
        @Override public Search search(String query,int budgetSeconds) {
            legacyCalls.incrementAndGet();return snapshot(query,budgetSeconds,Duration.ofSeconds(40));
        }
        @Override public Search search(String query,int budgetSeconds,Duration budget) {
            budgetCalls.incrementAndGet();return snapshot(query,budgetSeconds,budget);
        }
        private Search snapshot(String query,int budgetSeconds,Duration budget) {
            if(budget==null||budget.isNegative()||budget.isZero())throw new IllegalArgumentException("snapshot query budget");
            ObjectNode evidence=JSON.createObjectNode().put("mode","fixed-public-metadata-snapshot").put("query",query)
                    .put("originalQuery",source.path("query").asText()).put("requestedAt",source.path("requestedAt").asText())
                    .put("durationBudgetMs",budget.toMillis()).put("supplementaryBudgetSeconds",budgetSeconds).put("liveSearchRequests",0);
            return new Search(videos,List.of(),evidence);
        }
    }

    static List<BilibiliVideoCatalog.Video> snapshotVideos(JsonNode source) {
        List<BilibiliVideoCatalog.Video> videos = new ArrayList<>();
        for (JsonNode row : source.path("candidates")) {
            if (!row.path("metadataVerified").asBoolean()) continue;
            JsonNode first = row.path("pages").path(0);
            int duration = first.path("durationSeconds").asInt(row.path("durationSeconds").asInt());
            if (duration <= 0 || row.path("author").asText().isBlank()) continue;
            String bvid = row.path("bvid").asText();
            videos.add(new BilibiliVideoCatalog.Video(bvid+":1",bvid,1,"https://www.bilibili.com/video/"+bvid+"/?p=1",
                    row.path("title").asText(),row.path("description").asText(),first.path("title").asText(),
                    row.path("author").asText(),duration,row.path("verifiedAt").asText(),
                    JSON.createObjectNode().put("source","saved-public-view-api").put("scope","metadata-only")));
        }
        return List.copyOf(videos);
    }

    static ObjectNode queryCourse(JsonNode source) {
        String query = source.path("query").asText();
        ObjectNode course = JSON.createObjectNode().put("id","eval-"+source.path("id").asText()).put("title",query).put("topic",query)
                .put("grade",query.contains("沁园春")||query.contains("Java")?11:query.contains("勾股")?8:5).put("subject","综合").put("direction","academic")
                .put("sourceName","工程评测课例上下文").put("sourceText","本节学习主题："+query+"。已有文字说明，希望补充与本节具体主题对应的可观察示范或讲解。")
                .put("revision",1);
        course.putArray("outlines").addObject().put("id","scene-1").put("description","结合"+query+"，观察其关键过程或表达方式。");
        ObjectNode scene = course.putArray("scenes").addObject().put("id","scene-1").put("title",query).put("type","slide");
        scene.putObject("content").putArray("canvas").addObject().put("text","当前任务："+query+"；补充适配视频用于观察示范。");
        scene.putArray("actions");course.putObject("progress").put("sceneIndex",0);return course;
    }

    static EmbeddingService embeddingFromEnvironment() {
        EmbeddingService service = new EmbeddingService();
        String provider = environment("EVAL_EMBEDDING_PROVIDER","siliconflow");
        String model = environment("EVAL_EMBEDDING_MODEL","BAAI/bge-m3");
        int dimension = Integer.parseInt(environment("EVAL_EMBEDDING_DIMENSION","1024"));
        if (!Set.of("dashscope","siliconflow","ollama").contains(provider)) throw new IllegalArgumentException("embedding provider");
        setEmbeddingField(service,"provider",provider);setEmbeddingField(service,"expectedDimension",dimension);
        setEmbeddingField(service,"requestDimensions",Boolean.parseBoolean(environment("EVAL_EMBEDDING_REQUEST_DIMENSIONS","false")));
        setEmbeddingField(service,"dashscopeModel",model);setEmbeddingField(service,"siliconflowModel",model);setEmbeddingField(service,"ollamaModel",model);
        String key = environment("EVAL_EMBEDDING_KEY","");
        if (!provider.equals("ollama") && key.isBlank()) throw new IllegalStateException("EVAL_EMBEDDING_KEY required; no lexical score substituted for embeddings");
        setEmbeddingField(service,"dashscopeApiKey",key);setEmbeddingField(service,"siliconflowKey",key);
        setEmbeddingField(service,"siliconflowUrl",environment("EVAL_EMBEDDING_URL","https://api.siliconflow.cn/v1/embeddings"));
        setEmbeddingField(service,"ollamaUrl",environment("EVAL_EMBEDDING_URL","http://127.0.0.1:11434"));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();factory.setConnectTimeout(15_000);factory.setReadTimeout(30_000);
        RestTemplate http = new RestTemplate(factory);setEmbeddingField(service,"restTemplate",http);setEmbeddingField(service,"ollamaRestTemplate",http);
        return service;
    }

    /** 全部排序基线使用相同候选、时间/重复/适用约束；跳过不满足约束项而不是偷偷扩大预算。 */
    static List<String> feasibleTop(Input input,List<Candidate> order) {
        List<String> chosen = new ArrayList<>();
        for (Candidate candidate : order) {
            List<String> next = new ArrayList<>(chosen);next.add(candidate.id());
            if (RecommendationVerifier.recompute(input,next).isPresent()) chosen=next;
            if (chosen.size()==3) break;
        }
        return chosen.stream().sorted().toList();
    }

    static List<String> greedy(Input input) {
        List<String> selected = List.of();Score score = null;
        for (int step=0;step<3;step++) {
            List<String> best = selected;Score bestScore = score;
            for (Candidate candidate:input.candidates()) {
                if (selected.contains(candidate.id())) continue;
                List<String> next = new ArrayList<>(selected);next.add(candidate.id());next.sort(String::compareTo);
                Score value = RecommendationVerifier.recompute(input,next).orElse(null);
                if (better(next,value,best,bestScore)) {best=next;bestScore=value;}
            }
            if (best.equals(selected)) break;
            selected=List.copyOf(best);score=bestScore;
        }
        return selected;
    }

    static boolean better(List<String> ids,Score score,List<String> previous,Score previousScore) {
        if (score==null) return false;if(previousScore==null)return true;
        int order = Double.compare(score.total(),previousScore.total());if(order!=0)return order>0;
        order=Integer.compare(score.durationSeconds(),previousScore.durationSeconds());if(order!=0)return order<0;
        order=Double.compare(score.redundancy(),previousScore.redundancy());if(order!=0)return order<0;
        return String.join("\n",ids).compareTo(String.join("\n",previous))<0;
    }

    static ObjectNode compare(Input input) {
        ObjectNode result = JSON.createObjectNode().put("algorithmVersion",RecommendationOptimizer.ALGORITHM_VERSION).put("baselineVersion",BASELINE_VERSION)
                .put("qualityMeaning","finite objective proxy; not human relevance or learning improvement");
        long start=System.nanoTime();Selection optimum=RecommendationOptimizer.select(input);long elapsed=System.nanoTime()-start;
        var verification=RecommendationVerifier.verify(input,optimum);
        if(!verification.optimal())throw new IllegalStateException("independent verifier disagrees");
        result.set("verification",JSON.valueToTree(verification));result.put("optimizerNanos",elapsed);
        Map<String,List<String>> selected=new LinkedHashMap<>();
        selected.put("search_order",feasibleTop(input,input.candidates()));
        selected.put("semantic_rank",feasibleTop(input,input.candidates().stream().sorted(Comparator.comparingDouble(Candidate::relevance).reversed().thenComparing(Candidate::id)).toList()));
        selected.put("greedy",greedy(input));selected.put("optimum",optimum.ids());
        ObjectNode methods=result.putObject("methods");
        selected.forEach((name,ids)-> {
            ObjectNode row=methods.putObject(name);row.set("ids",JSON.valueToTree(ids));
            Score score=ids.isEmpty()?new Score(0,0,0,0,0,0,0):RecommendationVerifier.recompute(input,ids).orElseThrow();
            row.set("score",JSON.valueToTree(score));row.put("feasible",true);
            if(score.total()>optimum.score().total()+0.000001)throw new IllegalStateException("baseline score exceeds independently verified optimum");
        });
        List<String> left=input.candidates().stream().map(Candidate::id).toList();
        List<String> right=input.candidates().stream().sorted(Comparator.comparingDouble(Candidate::relevance).reversed().thenComparing(Candidate::id)).map(Candidate::id).toList();
        // 未取得人工相关标签，全部保持未知。同一ID始终共享标签，输出只表示可能检查成本区间。
        result.set("firstHitAudit",JSON.valueToTree(FirstHitAudit.audit(left,right,Map.of(),false)));
        List<String> optimizedFirst=new ArrayList<>(optimum.ids());
        right.stream().filter(id->!optimizedFirst.contains(id)).forEach(optimizedFirst::add);
        if(optimizedFirst.size()!=left.size()||!new HashSet<>(optimizedFirst).equals(new HashSet<>(left)))
            throw new IllegalStateException("first-hit rankings must use the same full candidate set");
        ObjectNode optimizedAudit=result.putObject("firstHitOptimizedAudit").put("comparison","retrieval-vs-optimized-first")
                .put("rankingScope","offline audit only; selected IDs in canonical displayed order, remaining IDs in semantic order")
                .put("labelScope","same resource shares one unknown label across both complete rankings");
        optimizedAudit.set("retrievalOrder",JSON.valueToTree(left));optimizedAudit.set("optimizedFirstOrder",JSON.valueToTree(optimizedFirst));
        optimizedAudit.set("audit",JSON.valueToTree(FirstHitAudit.audit(left,optimizedFirst,Map.of(),false)));
        ObjectNode ablations=result.putObject("ablations");
        for(String removed:List.of("semantic_relevance","coverage","redundancy")) {
            List<String> ids=ablation(input,removed);ObjectNode row=ablations.putObject("without_"+removed);
            row.set("ids",JSON.valueToTree(ids));row.set("originalObjectiveScore",JSON.valueToTree(ids.isEmpty()?new Score(0,0,0,0,0,0,0):RecommendationVerifier.recompute(input,ids).orElseThrow()));
            row.put("definition","Set named objective coefficient to zero without renormalization; evaluate selected set under original objective");
        }
        ablations.putObject("without_gate").put("status","EVALUATED_SEPARATELY_ON_72_CLASSIFICATION_CASES")
                .put("reason","Counterfactual always-search policy compared with actual classification decisions; no suppressed queries are sent to a search engine.");
        ablations.set("feedback_target_sensitivity",feedbackAblation(input));
        return result;
    }

    /**
     * 明确控制的反馈情景：按固定顺序选一个当前来源支持目标作重点，再移除该重点。
     * 只改变目标集合，候选、真实元数据、真实语义特征与硬约束保持一致；不是学生试验。
     */
    static ObjectNode feedbackAblation(Input input) {
        ObjectNode result=JSON.createObjectNode().put("mode","controlled-feedback-fixture").put("humanStudentFeedback",false)
                .put("scenarioRule","Choose lexicographically first source-supported current target as requested supplement; no ability inference")
                .put("claimBoundary","Selection sensitivity under an authored priority request, not measured feedback or learning benefit");
        if(input.targetGoals().isEmpty())return result.put("status","NOT_COMPUTED_NO_SOURCE_SUPPORTED_TARGET");
        String goal=input.targetGoals().stream().sorted().findFirst().orElseThrow();
        Input focused=new Input(input.candidates(),Set.of(goal),input.budgetSeconds(),input.preferenceAvailable(),input.pairSimilarities());
        Selection without=RecommendationOptimizer.select(input),with=RecommendationOptimizer.select(focused);
        result.put("status","computed").put("priorityGoal",goal).put("selectionChanged",!without.ids().equals(with.ids()));
        result.set("sourceSupportedBaseGoals",JSON.valueToTree(input.targetGoals()));
        result.set("withoutFeedback",JSON.valueToTree(without));result.set("withControlledFeedback",JSON.valueToTree(with));
        ObjectNode withoutFocus=result.putObject("withoutFeedbackEvaluatedOnPriority");
        var projected=RecommendationVerifier.recompute(focused,without.ids());
        withoutFocus.set("score",JSON.valueToTree(projected.orElse(new Score(0,0,0,0,0,0,0))));
        result.set("focusedVerification",JSON.valueToTree(RecommendationVerifier.verify(focused,with)));
        return result;
    }

    /** 消融只改变选优系数；约束仍通过独立Verifier检查，不用修改候选伪造消融结果。 */
    static List<String> ablation(Input input,String removed) {
        List<String> winner=List.of();Score winnerScore=null;double best=Double.NEGATIVE_INFINITY;
        for(int mask=1;mask<(1<<input.candidates().size());mask++) {
            if(Integer.bitCount(mask)>3)continue;List<String> ids=new ArrayList<>();
            for(int i=0;i<input.candidates().size();i++)if((mask&(1<<i))!=0)ids.add(input.candidates().get(i).id());ids.sort(String::compareTo);
            Score score=RecommendationVerifier.recompute(input,ids).orElse(null);if(score==null)continue;
            double factor=input.preferenceAvailable()?1:1/0.9;
            double value=score.total()-switch(removed) {case "semantic_relevance"->.3*factor*score.relevance();case "coverage"->.4*factor*score.coverage();case "redundancy"->-.2*score.redundancy();default->throw new IllegalArgumentException("ablation");};
            value=Math.round(value*1_000_000d)/1_000_000d;
            if(value>best || value==best && (winnerScore==null || score.durationSeconds()<winnerScore.durationSeconds()
                    || score.durationSeconds()==winnerScore.durationSeconds() && (score.redundancy()<winnerScore.redundancy()
                    || score.redundancy()==winnerScore.redundancy() && String.join("\n",ids).compareTo(String.join("\n",winner))<0))) {
                best=value;winner=ids;winnerScore=score;
            }
        }
        return List.copyOf(winner);
    }

    static void replay(Path decisions,Path output) throws Exception {
        Files.createDirectories(output);ObjectNode report=base("FIXED_INPUT_REAL_METADATA_FEATURE_REPLAY");
        report.put("baselineVersion",BASELINE_VERSION);ArrayNode rows=report.putArray("queries");
        List<Long> timings=new ArrayList<>();int completed=0,total=0;
        try(Stream<Path> paths=Files.list(decisions)) {
            for(Path path:paths.filter(p->p.getFileName().toString().matches("BILI-\\d+\\.json")).sorted().toList()) {
                total++;JsonNode entry=JSON.readTree(path.toFile());ObjectNode row=rows.addObject().put("id",entry.path("id").asText()).put("sourceStatus",entry.path("status").asText());
                JsonNode raw=entry.path("decision").path("audit").path("optimizerInput");
                if(!raw.isObject()){row.put("status","NOT_COMPUTED_NO_VERIFIED_FEATURE_INPUT");continue;}
                Input input=JSON.treeToValue(raw,Input.class);ObjectNode actual=compare(input);
                addLexicalBaseline(actual,input,entry.path("query").asText(),entry.path("decision").path("audit").path("metadata"));
                ObjectNode sensitivity=(ObjectNode)actual.path("ablations").path("feedback_target_sensitivity");
                for(JsonNode goal:entry.path("decision").path("audit").path("classification").path("goals"))
                    if(goal.path("text").asText().equals(sensitivity.path("priorityGoal").asText()))sensitivity.set("priorityGoalSourceAnchor",goal.deepCopy());
                row.set("comparison",actual);row.put("status","completed");completed++;
                // 预热后只计优化器；网络、独立验证器不混入该性能指标。
                for(int i=0;i<5;i++)RecommendationOptimizer.select(input);
                for(int i=0;i<10;i++){long start=System.nanoTime();RecommendationOptimizer.select(input);timings.add(System.nanoTime()-start);}
            }
        }
        timings.sort(Long::compareTo);report.put("totalSnapshotOutcomes",total).put("comparableQueries",completed).put("notComparableQueries",total-completed)
                .put("optimizerTimingHost",System.getProperty("os.name")).put("optimizerTimingScope","local fixed input; server P95 requires separate server run");
        if(!timings.isEmpty())report.put("optimizerP95Ms",timings.get(Math.max(0,(int)Math.ceil(timings.size()*.95)-1))/1_000_000d);
        write(output.resolve("algorithm-comparison.json"),report);
        String md="# 固定输入推荐工程评测\n\n输入结果数："+total+"；可比较："+completed+"；保留但不可比较："+(total-completed)+"。\n\n"
                +"检索顺序、语义排序、贪心与穷举最优共用候选和硬约束。得分是公开目标函数的代理指标，不是人工相关性或教学效果。"
                +"真实搜索失败、无可靠特征与模型失败仍在分母中，不填零分冒充负例。首次命中标签全部未知时只给相容区间。\n\n"
                +"消融移除相关度、覆盖、重复项。门控以72分类夹具上的无门控策略作离线反事实比较，绝不外搜私密输入。"
                +"反馈目标敏感性采用明确标注controlled-feedback-fixture的成对输入：把已有来源支持目标之一设为重点，对比移除重点后的组合；不是学生反馈收益。\n\n"
                +"详细证据见 algorithm-comparison.json，各来源目录保留原查询、模型请求来源和完整固定输入。\n";
        Files.writeString(output.resolve("algorithm-comparison.md"),md,StandardCharsets.UTF_8);
        System.out.println("Replay outcomes="+total+" comparable="+completed+" (finite objective only)");
    }

    static void addLexicalBaseline(ObjectNode report,Input input,String query,JsonNode metadata) {
        Set<String> queryTerms=bigrams(query);Map<String,Double> similarity=new HashMap<>();
        for(JsonNode item:metadata) {
            Set<String> terms=bigrams(item.path("title").asText()+" "+item.path("partTitle").asText()+" "+item.path("description").asText());
            Set<String> overlap=new HashSet<>(queryTerms);overlap.retainAll(terms);
            // 查询二元字组召回率，范围[0,1]；仅是无需embedding的词面排序参照。
            similarity.put(item.path("id").asText(),queryTerms.isEmpty()?0:overlap.size()/(double)queryTerms.size());
        }
        List<Candidate> order=input.candidates().stream().sorted(Comparator.<Candidate>comparingDouble(c->similarity.getOrDefault(c.id(),0d)).reversed().thenComparing(Candidate::id)).toList();
        List<String> ids=feasibleTop(input,order);ObjectNode row=((ObjectNode)report.path("methods")).putObject("lexical_bigram_rank");
        row.set("ids",JSON.valueToTree(ids));row.put("rankingVersion","normalized-char-bigram-query-recall-v1");row.put("feasible",true);
        row.set("score",JSON.valueToTree(ids.isEmpty()?new Score(0,0,0,0,0,0,0):RecommendationVerifier.recompute(input,ids).orElseThrow()));
        row.set("lexicalScores",JSON.valueToTree(similarity));
    }
    static Set<String> bigrams(String value) {
        String normalized=value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]","");
        int[] points=normalized.codePoints().toArray();Set<String> tokens=new TreeSet<>();
        if(points.length==1)tokens.add(new String(points,0,1));
        for(int i=0;i+1<points.length;i++)tokens.add(new String(points,i,2));return tokens;
    }

    static void runCases(JsonNode cases,Path output,String name,int parallel,Function<JsonNode,ObjectNode> run) throws Exception {
        Path records=output.resolve(name+"-cases");Files.createDirectories(records);
        try(ExecutorService pool=Executors.newFixedThreadPool(parallel)) {
            List<Future<?>> pending=new ArrayList<>();
            for(JsonNode item:cases) {
                Path file=records.resolve(item.path("id").asText()+".json");if(Files.exists(file))continue;
                pending.add(pool.submit(()->{try{ObjectNode result=run.apply(item);write(file,result);System.out.println(item.path("id").asText()+" "+result.path("status").asText()+" expectation="+result.path("matchesEngineeringExpectation").asText("unknown"));}catch(Exception e){throw new CompletionException(e);}}));
            }
            for(Future<?> item:pending)item.get();
        }
        ObjectNode report=base("LIVE_ENGINEERING_EXPECTATION_CHECK");ArrayNode rows=report.putArray("cases");int completed=0,matched=0,called=0;
        for(JsonNode item:cases) {
            JsonNode row=JSON.readTree(records.resolve(item.path("id").asText()+".json").toFile());rows.add(row);
            if(row.path("status").asText().equals("completed"))completed++;if(row.path("matchesEngineeringExpectation").asBoolean())matched++;if(row.path("modelCalled").asBoolean())called++;
        }
        report.put("totalCases",cases.size()).put("completedCases",completed).put("failedOrUnknown",cases.size()-completed).put("matchesEngineeringExpectation",matched).put("modelCalledCases",called)
                .put("metricMeaning","contract agreement against unaudited authored expectations, never teacher accuracy");
        if(name.equals("classification")) {
            int pipelineWouldSearch=0, prohibitedBlocked=0, prohibitedTotal=0, unresolved=0;
            ArrayNode decisions=report.putArray("gateAblationCases");
            for(int i=0;i<cases.size();i++) {
                JsonNode fixture=cases.get(i),actual=rows.get(i);boolean wouldSearch=actual.path("actualStatus").asText().equals("eligible");
                boolean prohibited=fixture.path("expectedStatus").asText().equals("ineligible");
                if(wouldSearch)pipelineWouldSearch++;if(prohibited){prohibitedTotal++;if(!wouldSearch)prohibitedBlocked++;}
                if(!actual.path("status").asText().equals("completed"))unresolved++;
                decisions.addObject().put("caseId",fixture.path("id").asText()).put("actualPipelineWouldSearch",wouldSearch)
                        .put("withoutGateCounterfactualWouldSearch",true).put("expectedProhibited",prohibited).put("networkSearchExecuted",false)
                        .put("modelFailure",!actual.path("status").asText().equals("completed"));
            }
            report.putObject("gateAblation").put("mode","counterfactual-always-search-policy").put("inputCases",cases.size())
                    .put("actualPipelineWouldSearch",pipelineWouldSearch).put("withoutGateWouldSearch",cases.size())
                    .put("authoredProhibitedCases",prohibitedTotal).put("authoredProhibitedCasesBlocked",prohibitedBlocked)
                    .put("modelFailureCasesRetained",unresolved).put("networkSearchesForThisAblation",0)
                    .put("claimBoundary","Policy suppression against authored engineering expectations, not human-rated classification accuracy");
        }
        write(output.resolve(name+"-results.json"),report);
        System.out.println(name+" total="+cases.size()+" completed="+completed+" agreement="+matched);
    }

    static final class MemoryStore extends ClassroomStudyStore {
        private final ConcurrentMap<String,ObjectNode> rows=new ConcurrentHashMap<>();
        MemoryStore(){super(null,JSON);}
        @Override public Optional<ObjectNode> find(String kind,String id){return Optional.ofNullable(rows.get(kind+":"+id)).map(ObjectNode::deepCopy);}
        @Override public boolean create(String kind,String id,String course,ObjectNode value){return rows.putIfAbsent(kind+":"+id,value.deepCopy())==null;}
        @Override public void save(String kind,String id,String course,ObjectNode value){rows.put(kind+":"+id,value.deepCopy());}
        @Override public List<ObjectNode> list(String kind,String course,int limit){return rows.entrySet().stream().filter(e->e.getKey().startsWith(kind+":")&&e.getValue().path("courseId").asText().equals(course)).limit(limit).map(e->e.getValue().deepCopy()).toList();}
        @Override public List<ObjectNode> listForScene(String kind,String course,String scene,int limit){return rows.entrySet().stream().filter(e->e.getKey().startsWith(kind+":")&&e.getValue().path("courseId").asText().equals(course)&&e.getValue().path("sceneId").asText().equals(scene)).limit(limit).map(e->e.getValue().deepCopy()).toList();}
    }
    static ObjectNode base(String mode){ObjectNode result=JSON.createObjectNode().put("schemaVersion","research-evaluation-v1").put("mode",mode).put("createdAt",Instant.now().toString()).put("labelScope",LABEL_SCOPE).put("humanReviewed",false).put("runPurpose",runPurpose).put("independentGeneralizationClaim",false);result.set("buildEvidence",buildEvidence());return result;}
    /** 记录实际加载的字节码与工作区源码身份；旧编译目录无法冒充新的harness修订。 */
    static ObjectNode buildEvidence(){
        ObjectNode result=JSON.createObjectNode().put("harnessRevision",HARNESS_REVISION).put("javaVersion",System.getProperty("java.version"));
        try(var code=ResearchEvaluationCli.class.getResourceAsStream("ResearchEvaluationCli.class")){
            if(code==null)throw new IllegalStateException("harness bytecode unavailable");
            result.put("loadedClassSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code.readAllBytes())));
            Path source=Path.of("src/test/java/com/demo/edu/classroom/evaluation/ResearchEvaluationCli.java");
            if(Files.exists(source))result.put("workspaceSourceSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
            result.put("loadedFrom",ResearchEvaluationCli.class.getProtectionDomain().getCodeSource().getLocation().toString());
        }catch(Exception failed){throw new IllegalStateException("evaluation build identity unavailable",failed);}
        return result;
    }
    static ObjectNode caseBase(JsonNode item,String mode){ObjectNode row=base(mode).put("id",item.path("id").asText()).put("category",item.path("category").asText()).put("split",item.path("split").asText());row.set("expected",item.has("expectedStatus")?item.path("expectedStatus"):item.path("expectedStatuses"));return row;}
    static void failure(ObjectNode row,RuntimeException error){row.put("status","failed_or_unknown").put("errorType",error.getClass().getSimpleName());if(error instanceof ClassroomAiGateway.Failure failure){row.put("failureKind",failure.failureKind());row.set("modelEvidence",failure.evidence());row.put("modelCalled",failure.evidence().path("attemptCount").asInt()>0);}else if(error instanceof ClassroomAiGateway.Cancelled cancelled){row.set("modelEvidence",cancelled.evidence());row.put("modelCalled",cancelled.evidence().path("attemptCount").asInt()>0);}}
    static Set<String> strings(JsonNode values){Set<String> out=new TreeSet<>();values.forEach(v->out.add(v.asText()));return out;}
    static void write(Path path,JsonNode value) throws Exception{Files.createDirectories(path.toAbsolutePath().getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),value);}
    // 直接字段注入测试依赖，避免调试日志打印凭据值。
    static void setEmbeddingField(EmbeddingService service,String name,Object value) {try {var field=EmbeddingService.class.getDeclaredField(name);field.setAccessible(true);field.set(service,value);}catch(ReflectiveOperationException e){throw new IllegalStateException("embedding evaluation binding failed",e);}}
    static String environment(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    static String required(Map<String,String> args,String name){String value=args.get(name);if(value==null||value.isBlank())throw new IllegalArgumentException("--"+name+" required");return value;}
    static Map<String,String> arguments(String[] args){if(args.length%2!=0)throw new IllegalArgumentException("Use --name value pairs");Map<String,String> out=new HashMap<>();for(int i=0;i<args.length;i+=2){if(!args[i].startsWith("--"))throw new IllegalArgumentException("option expected");out.put(args[i].substring(2),args[i+1]);}return out;}
}
