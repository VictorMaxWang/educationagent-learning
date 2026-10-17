package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/**
 * 补充资源的业务编排。共享 classroom 身份下，同内容/预算/偏好合并为一个低优先级任务。
 * M001 方法适配用于有限目标函数的差额复用；M003 的已核验学习遗漏只影响同课同内容目标。
 */
@Service
public class ClassroomRecommendationService implements AutoCloseable {
    static final String MATCHING_VERSION="video-matching-compact-v1";
    static final String SETTINGS="recommendation_settings",CACHE="recommendation_cache",DECISION="recommendation_decision";
    static final String INTENT="recommendation_intent";
    static final String PAGE_INTENT="recommendation_page_intent";
    static final Duration CACHE_AGE=Duration.ofHours(24);
    private final ClassroomCourseService courses;private final ClassroomStudyStore store;private final ObjectMapper json;
    private final VideoTeachingClassifier classifier;private final BilibiliVideoCatalog catalog;
    private final ResourceSemanticFeatures semantic;private final ClassroomAiGateway ai;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"classroom-video-recommendation");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private Clock clock=Clock.systemUTC();
    @Value("${classroom.recommendation.queue-timeout-ms:300000}") private long queueTimeoutMs=300_000;
    private volatile boolean closed;

    public record RefreshRequest(String sceneId,Integer budgetMinutes,String preference,boolean manual) {
        public RefreshRequest(String sceneId,Integer budgetMinutes,String preference){this(sceneId,budgetMinutes,preference,false);}
    }
    public record FeedbackRequest(String decisionId,String resourceId,String feedback,String requestId) {}
    private record Settings(int minutes,String preference) {}
    private record Request(ClassroomStudyContext.Snapshot context,Settings settings,List<String> targets,String key,String settingKey) {}
    private record Job(Request request,String id,ObjectNode response,ObjectNode previous,ObjectNode working,ObjectNode intent,long queueDeadline) {}
    @Autowired public ClassroomRecommendationService(@Lazy ClassroomCourseService courses,ClassroomStudyStore store,ObjectMapper json,
                                         VideoTeachingClassifier classifier,BilibiliVideoCatalog catalog,ResourceSemanticFeatures semantic,ClassroomAiGateway ai) {
        this(courses,store,json,classifier,catalog,semantic,ai,true);
    }
    ClassroomRecommendationService(ClassroomCourseService courses,ClassroomStudyStore store,ObjectMapper json,
                                   VideoTeachingClassifier classifier,BilibiliVideoCatalog catalog,ResourceSemanticFeatures semantic,ClassroomAiGateway ai,boolean schedule) {
        this.courses=courses;this.store=store;this.json=json;this.classifier=classifier;this.catalog=catalog;this.semantic=semantic;this.ai=ai;
        if(schedule)worker.scheduleWithFixedDelay(()->{try{tick();}catch(RuntimeException unavailable){/* 单次持久化失败不能终止之后所有调度；未发出的任务仍受排队截止时间限制。 */}},500,1500,TimeUnit.MILLISECONDS);
    }

    /** 仅入队当前节；课程生成完成后由宿主调用，不阻塞生成线程。 */
    public void prepareIfAbsent(String courseId) {try{refresh(courseId,new RefreshRequest(null,null,null));}catch(RuntimeException ignored){/* 推荐不可影响原课堂 ready。 */}}
    public ObjectNode get(String courseId,String sceneId) {
        Request request=request(courseId,sceneId,null);
        synchronized(jobs){
            Job active=jobs.get(request.key());if(active!=null)return active.response().deepCopy();
            ObjectNode unknown=unfinishedIntent(request);if(unknown!=null)return unknown;
            Optional<ObjectNode> saved=store.find(CACHE,request.key());
            return saved.isPresent()&&fresh(saved.get())?publicView(saved.get()):base(request,"idle");
        }
    }

    /** 导出只读取本课当前内容、设置和模型身份对应的有效缓存；绝不因导出启动外搜或模型任务。 */
    public ArrayNode portableRecommendations(String courseId) {
        ObjectNode course=courses.studySnapshot(courseId);ArrayNode result=json.createArrayNode();
        for(JsonNode scene:course.path("scenes")) {
            String sceneId=scene.path("id").asText();if(sceneId.isBlank())continue;
            ObjectNode current=get(courseId,sceneId);
            if(!"ready".equals(current.path("status").asText())||!fresh(current))continue;
            for(JsonNode item:current.path("items")) {
                String url=item.path("url").asText();
                if(!url.matches("https://www\\.bilibili\\.com/video/BV[0-9A-Za-z]{10}/(?:\\?p=[1-9][0-9]{0,4})?"))continue;
                try{if(Instant.parse(item.path("checkedAt").asText()).plus(CACHE_AGE).isBefore(Instant.now()))continue;}catch(RuntimeException invalid){continue;}
                ObjectNode exported=result.addObject().put("sceneId",sceneId);
                for(String key:List.of("id","title","url","author","durationSeconds","purpose","goals","reason","checkedAt","sourceKind","sourceLabel","part","durationScope","verificationScope"))if(item.has(key))exported.set(key,item.get(key).deepCopy());
            }
        }
        return result;
    }
    public ObjectNode refresh(String courseId,RefreshRequest input) {
        if(input==null)input=new RefreshRequest(null,null,null);
        Request request=request(courseId,input.sceneId(),input);
        synchronized(jobs) {
            if(closed)throw new ClassroomException(503,"补充资料服务正在停止");
            Job active=jobs.get(request.key());if(active!=null){acceptExisting(request);return active.response().deepCopy();}
            if(!input.manual()) {
                ObjectNode unknown=unfinishedIntent(request);if(unknown!=null){acceptExisting(request,false);return unknown;}
            }
            Optional<ObjectNode> saved=store.find(CACHE,request.key());
            if(!input.manual()&&saved.isPresent()&&fresh(saved.get())){acceptExisting(request);return publicView(saved.get());}
            List<Job> superseded=queue.stream().filter(job->job.request().settingKey().equals(request.settingKey())).toList();
            if(jobs.size()-superseded.size()>=32)throw new ClassroomException(429,"补充资料正在准备，请稍后重试");
            String id=UUID.randomUUID().toString();ObjectNode response=base(request,"queued").put("decisionId",id).put("nextPollMs",1500);
            long deadline=clock.millis()+Math.max(1000,Math.min(900_000,queueTimeoutMs));
            ObjectNode intent=base(request,"queued").put("requestKey",request.key()).put("settingKey",request.settingKey()).put("courseId",courseId)
                    .put("decisionId",id).put("phase","queued").put("createdAt",Instant.ofEpochMilli(clock.millis()).toString()).put("queueDeadline",Instant.ofEpochMilli(deadline).toString());
            List<ClassroomStudyStore.Write> writes=new ArrayList<>();
            for(Job old:superseded)writes.addAll(queuedTerminationWrites(old,"superseded","NEWER_PAGE_REQUEST"));
            writes.add(new ClassroomStudyStore.Write(INTENT,request.key(),courseId,intent));writes.add(settingsWrite(request));writes.add(pageIntentWrite(request));
            // 通过容量检查后才提交设置和执行意图；429与持久化失败都不能悄悄改掉当前预算。
            store.saveBatch(writes);
            for(Job old:superseded){queue.remove(old);jobs.remove(old.request().key());}
            Job job=new Job(request,id,response,saved.orElse(null),json.createObjectNode(),intent,deadline);
            jobs.put(request.key(),job);queue.addLast(job);return response.deepCopy();
        }
    }
    public ObjectNode feedback(String courseId,FeedbackRequest input) {
        courses.studySnapshot(courseId);
        if(input==null||!Set.of("helpful","not_helpful","too_long","tried","opened").contains(input.feedback()==null?"":input.feedback())
                ||!validId(input.requestId())||!validId(input.decisionId())||input.resourceId()==null||input.resourceId().length()>80)
            throw new ClassroomException(400,"推荐反馈格式无效");
        ObjectNode decision=store.find(DECISION,input.decisionId()).orElseThrow(()->new ClassroomException(404,"这次推荐已不可用"));
        if(!courseId.equals(decision.path("courseId").asText())||!containsItem(decision,input.resourceId()))throw new ClassroomException(404,"推荐资源不存在");
        String id=ClassroomStudyContext.hash(json.valueToTree(List.of(courseId,input.requestId())));
        ObjectNode document=json.createObjectNode().put("courseId",courseId).put("decisionId",input.decisionId()).put("resourceId",input.resourceId())
                .put("feedback",input.feedback()).put("requestId",input.requestId()).put("createdAt",Instant.now().toString())
                .put("sceneId",decision.path("sceneId").asText()).put("contentFingerprint",decision.path("contentFingerprint").asText());
        if(!store.create("recommendation_feedback",id,courseId,document)) {
            ObjectNode previous=store.find("recommendation_feedback",id).orElseThrow();
            for(String field:List.of("decisionId","resourceId","feedback"))if(!previous.path(field).equals(document.path(field)))throw new ClassroomException(409,"这个提交编号已经用于另一条反馈");
        }
        // opened/tried 仅是用户事件，不修改题目得分、播放位置或知识掌握度。
        return json.createObjectNode().put("saved",true).put("requestId",input.requestId());
    }

    private Request request(String courseId,String sceneId,RefreshRequest update) {
        ClassroomStudyContext.Snapshot context=ClassroomStudyContext.from(courses.studySnapshot(courseId),sceneId);
        String settingKey=ClassroomStudyContext.hash(json.valueToTree(List.of(courseId,context.sceneId())));
        ObjectNode saved=store.find(SETTINGS,settingKey).orElseGet(json::createObjectNode);
        int budget=update!=null&&update.budgetMinutes()!=null?update.budgetMinutes():saved.path("budgetMinutes").asInt(10);
        String preference=update!=null&&update.preference()!=null?ClassroomStudyContext.normalize(update.preference()):saved.path("preference").asText("");
        if(!Set.of(5,10,20).contains(budget)||preference.length()>80||preference.chars().anyMatch(Character::isISOControl))throw new ClassroomException(400,"补充学习时间或用途偏好无效");
        List<String> targets=verifiedFeedbackTargets(context);
        ObjectNode basis=json.createObjectNode().put("courseId",courseId).put("sceneId",context.sceneId()).put("fingerprint",context.fingerprint())
                .put("minutes",budget).put("preference",preference).put("algorithm",RecommendationOptimizer.ALGORITHM_VERSION).put("classifier",VideoTeachingClassifier.VERSION)
                .put("catalog",BilibiliVideoCatalog.VERSION).put("matchingVersion",MATCHING_VERSION);
        basis.set("feedbackTargets",json.valueToTree(targets));basis.set("embedding",semantic.identity());
        return new Request(context,new Settings(budget,preference),targets,ClassroomStudyContext.hash(basis),settingKey);
    }
    private ClassroomStudyStore.Write settingsWrite(Request request){return new ClassroomStudyStore.Write(SETTINGS,request.settingKey(),request.context().courseId(),json.createObjectNode().put("sceneId",request.context().sceneId()).put("budgetMinutes",request.settings().minutes()).put("preference",request.settings().preference()));}
    private ClassroomStudyStore.Write pageIntentWrite(Request request){return new ClassroomStudyStore.Write(PAGE_INTENT,request.settingKey(),request.context().courseId(),json.createObjectNode().put("intentKey",request.key()).put("sceneId",request.context().sceneId()).put("contentFingerprint",request.context().fingerprint()).put("budgetMinutes",request.settings().minutes()).put("preference",request.settings().preference()));}
    private void acceptExisting(Request request){acceptExisting(request,true);}
    private void acceptExisting(Request request,boolean replacePointer) {
        List<Job> old=queue.stream().filter(job->job.request().settingKey().equals(request.settingKey())&&!job.request().key().equals(request.key())).toList();
        List<ClassroomStudyStore.Write> writes=new ArrayList<>();old.forEach(job->writes.addAll(queuedTerminationWrites(job,"superseded","NEWER_PAGE_REQUEST")));
        writes.add(settingsWrite(request));if(replacePointer)writes.add(pageIntentWrite(request));store.saveBatch(writes);
        old.forEach(job->{queue.remove(job);jobs.remove(job.request().key());});
    }

    /** 没有本进程活跃任务但有未完成持久意图，说明前次结果未知；GET永远不会自动重发。 */
    private ObjectNode unfinishedIntent(Request request) {
        ObjectNode intent=store.find(INTENT,request.key()).orElse(null);
        if(intent==null||Set.of("finished","superseded").contains(intent.path("phase").asText())) {
            ObjectNode pointer=store.find(PAGE_INTENT,request.settingKey()).orElse(null);
            if(pointer==null||!request.context().fingerprint().equals(pointer.path("contentFingerprint").asText())
                    ||request.settings().minutes()!=pointer.path("budgetMinutes").asInt()||!request.settings().preference().equals(pointer.path("preference").asText()))return null;
            String previousKey=pointer.path("intentKey").asText();
            if(previousKey.isBlank()||jobs.containsKey(previousKey))return null;
            intent=store.find(INTENT,previousKey).orElse(null);if(intent==null)return null;
        }
        String phase=intent.path("phase").asText();if(Set.of("finished","superseded").contains(phase))return null;
        ObjectNode result=base(request,"error").put("decisionId",intent.path("decisionId").asText()).put("updatedAt",Instant.now().toString());
        if(!Set.of("unknown","expired").contains(phase)) {
            ObjectNode recovered=intent.deepCopy().put("phase","unknown").put("status","error").put("updatedAt",Instant.now().toString());
            ObjectNode audit=result.deepCopy().put("courseId",request.context().courseId());audit.putObject("audit").put("failureKind","RESTART_OUTCOME_UNKNOWN");
            String intentKey=intent.path("requestKey").asText(request.key());
            store.saveBatch(List.of(new ClassroomStudyStore.Write(INTENT,intentKey,request.context().courseId(),recovered),
                    new ClassroomStudyStore.Write(DECISION,intent.path("decisionId").asText(),request.context().courseId(),audit),
                    new ClassroomStudyStore.Write(CACHE,request.key(),request.context().courseId(),audit)));
        }
        return result;
    }
    private List<ClassroomStudyStore.Write> queuedTerminationWrites(Job job,String phase,String reason) {
        ObjectNode record=base(job.request(),"error").put("courseId",job.request().context().courseId()).put("decisionId",job.id()).put("updatedAt",Instant.now().toString());
        record.putObject("audit").put("failureKind",reason).put("modelCalls",0);
        ObjectNode intent=job.intent().deepCopy().put("phase",phase).put("status","error").put("updatedAt",Instant.now().toString());
        return List.of(new ClassroomStudyStore.Write(DECISION,job.id(),job.request().context().courseId(),record),
                new ClassroomStudyStore.Write(INTENT,job.request().key(),job.request().context().courseId(),intent));
    }
    private List<String> verifiedFeedbackTargets(ClassroomStudyContext.Snapshot context) {
        List<ObjectNode> attempts=new ArrayList<>(store.listForScene("learning_attempt",context.courseId(),context.sceneId(),30));
        // hint/self-check 会更新持久层 updated_at，但不得令旧表述盖过学生较新的原始表述。
        attempts.removeIf(attempt->createdAt(attempt)==null);
        attempts.sort(Comparator.comparing((ObjectNode attempt)->createdAt(attempt)).reversed()
                .thenComparing(attempt->attempt.path("id").asText()));
        for(ObjectNode attempt:attempts) {
            if(!"checked".equals(attempt.path("status").asText())||!context.courseId().equals(attempt.path("courseId").asText())||!context.sceneId().equals(attempt.path("sceneId").asText())
                    ||!context.fingerprint().equals(attempt.path("contentFingerprint").asText()))continue;
            TreeSet<String> goals=new TreeSet<>();
            for(JsonNode entry:attempt.path("analysis"))if(Set.of("missing","conflict").contains(entry.path("status").asText())) {
                try {
                    VideoTeachingClassifier.validateAnchor(context,entry);
                    if(context.anchors().stream().noneMatch(a->a.id().equals(entry.path("anchorId").asText())&&Set.of("reference","lesson").contains(a.kind())))continue;
                    String goal=VideoTeachingClassifier.requireText(entry,"goal",100);goals.add(goal);
                }catch(IllegalArgumentException ignored){/* 旧模型或未验证记录不能成为新的推荐目标。 */}
            }
            return goals.stream().limit(5).toList();
        }
        return List.of();
    }
    private static Instant createdAt(ObjectNode attempt) {
        try{return Instant.parse(attempt.path("createdAt").asText());}catch(RuntimeException invalid){return null;}
    }
    void tick() {
        Job job;
        synchronized(jobs){
            if(closed)return;
            List<Job> expired=queue.stream().filter(item->clock.millis()>=item.queueDeadline()).toList();
            if(!expired.isEmpty()) {
                List<ClassroomStudyStore.Write> writes=new ArrayList<>();expired.forEach(item->writes.addAll(queuedTerminationWrites(item,"expired","QUEUE_WAIT_EXPIRED")));
                store.saveBatch(writes);expired.forEach(item->{queue.remove(item);jobs.remove(item.request().key());});
            }
            if(queue.isEmpty())return;
        }
        try{if(courses.hasActiveGeneration())return;}catch(RuntimeException unavailable){return;}
        synchronized(jobs){job=queue.peekFirst();if(job==null)return;
            ObjectNode running=job.intent().deepCopy().put("phase","running").put("status","running").put("updatedAt",Instant.now().toString());
            store.save(INTENT,job.request().key(),job.request().context().courseId(),running);queue.removeFirst();job.response().put("status","running");}
        try{
            Request current=request(job.request().context().courseId(),job.request().context().sceneId(),null);
            if(!current.key().equals(job.request().key())){store.saveBatch(queuedTerminationWrites(job,"superseded","PAGE_REQUEST_CHANGED_BEFORE_DISPATCH"));return;}
            execute(job);
        }catch(ClassroomAiGateway.Failure error){fail(job,error.failureKind(),error.evidence());}
        catch(ResourceSemanticFeatures.Failure error){
            ObjectNode audit=job.working().path("audit") instanceof ObjectNode existing?existing:job.working().putObject("audit");
            audit.set("embedding",error.evidence());fail(job,"MODEL_SPACE_CHANGED".equals(error.getMessage())?"MODEL_SPACE_CHANGED":"EMBEDDING_RESPONSE_UNKNOWN",null);
        }
        catch(RuntimeException error){String reason=error instanceof CancellationException?"CANCELLED":"RESOURCE_PREPARATION_UNAVAILABLE";
            if(error instanceof IllegalStateException&&Set.of("EMBEDDING_RESPONSE_UNKNOWN","EMBEDDING_IDENTITY_CHANGED","EMBEDDING_IDENTITY_UNAVAILABLE","OPTIMIZER_VERIFICATION_FAILED","RESOURCE_PREPARATION_DEADLINE").contains(error.getMessage()==null?"":error.getMessage()))reason=error.getMessage();
            fail(job,reason,null);}
        finally{synchronized(jobs){jobs.remove(job.request().key());}}
    }
    private void execute(Job job) {
        long start=System.nanoTime();Request request=job.request();ClassroomStudyContext.Snapshot context=request.context();
        RecommendationDeadline deadline=new RecommendationDeadline(Duration.ofSeconds(90));
        ObjectNode record=job.working();record.setAll(base(request,"hidden"));record.put("courseId",context.courseId()).put("decisionId",job.id()).put("algorithmVersion",RecommendationOptimizer.ALGORITHM_VERSION);
        VideoTeachingClassifier.Classification classification=classifier.classify(context,deadline.remaining(Duration.ofSeconds(40)));
        record.set("classification",classification.publicValue(json));
        ObjectNode audit=record.putObject("audit");audit.put("totalBudgetMs",90000);audit.set("classification",classification.raw());audit.set("classificationCall",classification.evidence());
        if(!"eligible".equals(classification.status())){deadline.check();finish(job,record,start);return;}
        // M003：已核验的遗漏/冲突成为本次补充目标；仅做并集会让原已包含的目标无法影响推荐。
        // 完整课堂目标仍是语义判断背景，既不推断能力，也不改变年级或题目难度。
        TreeSet<String> targets=new TreeSet<>(request.targets().isEmpty()?classification.goals():request.targets());
        audit.set("contextGoals",json.valueToTree(classification.goals()));audit.set("feedbackTargets",json.valueToTree(request.targets()));audit.set("targetGoals",json.valueToTree(targets));
        audit.put("targetSource",request.targets().isEmpty()?"classification":"verified-learning-feedback");
        BilibiliVideoCatalog.Search search=catalog.search(classification.query(),request.settings().minutes()*60,deadline.remaining(Duration.ofSeconds(40)));
        audit.set("search",search.evidence());ArrayNode rejected=audit.putArray("rejected");search.rejected().forEach(rejected::add);
        audit.set("metadata",json.valueToTree(search.videos()));
        if(search.videos().isEmpty()){deadline.check();finish(job,record,start);return;}
        List<Matched> matches=match(context,classification,targets,request.settings().preference(),search.videos(),audit,rejected,deadline.remaining(Duration.ofSeconds(45)));
        if(matches.isEmpty()){deadline.check();finish(job,record,start);return;}
        List<BilibiliVideoCatalog.Video> videos=matches.stream().map(Matched::video).toList();
        ResourceSemanticFeatures.Features features=semantic.compute(context.courseId(),classification.publicTopic()+" "+String.join(" ",targets),videos,deadline.remaining(Duration.ofSeconds(60)));
        audit.set("embedding",features.evidence());
        Map<String,String> groups=duplicateGroups(videos);
        List<RecommendationOptimizer.Candidate> candidates=matches.stream().map(match->new RecommendationOptimizer.Candidate(match.video().id(),match.video().durationSeconds(),
                match.goals(),match.purpose(),features.relevance().get(match.video().id()),complementarity(context,match.purpose()),
                match.preference(),true,groups.get(match.video().id()))).toList();
        RecommendationOptimizer.Input input=new RecommendationOptimizer.Input(candidates,targets,request.settings().minutes()*60,!request.settings().preference().isBlank(),features.pairs());
        long optimizeStart=System.nanoTime();List<String> old=oldSelection(job.previous(),context);
        RecommendationOptimizer.ReuseDecision selected=RecommendationOptimizer.reuse(input,old,0.02);
        long optimizeMicros=TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-optimizeStart);
        RecommendationVerifier.Verification verification=RecommendationVerifier.verify(input,selected.optimum());
        if(!verification.optimal())throw new IllegalStateException("OPTIMIZER_VERIFICATION_FAILED");
        if(!selected.ids().isEmpty()&&!RecommendationVerifier.recompute(input,selected.ids()).filter(selected.selectedScore()::equals).isPresent())
            throw new IllegalStateException("OPTIMIZER_VERIFICATION_FAILED");
        audit.set("optimizerInput",json.valueToTree(input));audit.set("selection",json.valueToTree(selected));audit.set("verification",json.valueToTree(verification));
        audit.put("optimizerMicros",optimizeMicros);
        ArrayNode items=record.putArray("items");
        for(String id:selected.ids()) {
            Matched match=matches.stream().filter(m->m.video().id().equals(id)).findFirst().orElseThrow();BilibiliVideoCatalog.Video video=match.video();
            ObjectNode item=items.addObject().put("id",id).put("url",video.url()).put("title",video.part()>1?video.title()+" · "+video.partTitle():video.title())
                    .put("author",video.author()).put("durationSeconds",video.durationSeconds()).put("reason",match.reason()).put("purpose",match.purpose())
                    .put("verificationScope",video.part()==0?"search-metadata":"metadata").put("part",video.part())
                    .put("durationScope",video.part()==0?"whole-video":"part").put("checkedAt",video.checkedAt());item.set("goals",json.valueToTree(match.goals()));
            boolean fromCache="verified-metadata-cache".equals(video.metadataSources().path("source").asText());
            item.put("sourceKind",video.part()==0?"bilibili-search-metadata":fromCache?"verified-metadata-cache":"bilibili-public-api")
                    .put("sourceLabel",video.part()==0?"B站搜索资料":fromCache?"已核验资料缓存":"已核验视频资料");
        }
        if(!items.isEmpty())record.put("status","ready");deadline.check();finish(job,record,start);
    }
    private record Matched(BilibiliVideoCatalog.Video video,Set<String> goals,String purpose,String reason,double preference) {}
    private List<Matched> match(ClassroomStudyContext.Snapshot context,VideoTeachingClassifier.Classification classification,Set<String> targets,
                                String preference,List<BilibiliVideoCatalog.Video> videos,ObjectNode audit,ArrayNode rejected,Duration budget) {
        ObjectNode input=json.createObjectNode().put("grade",context.grade()).put("subject",context.subject()).put("topic",classification.publicTopic())
                .put("purpose",classification.purpose()).put("explicitPreference",preference);
        input.set("contextGoals",json.valueToTree(classification.goals()));input.set("targetGoals",json.valueToTree(targets));
        ObjectNode background=context.toJson(json);background.remove("visibleText"); // anchors 已保留正文，无须重复整段文本。
        input.set("classroomContext",background);input.set("existingPurposes",json.valueToTree(context.existingPurposes()));
        // 模型只看教学匹配所需字段。完整原始metadata仍在audit.metadata，避免重复URL/查询追踪吞占45秒预算。
        ArrayNode projected=input.putArray("videos");
        for(BilibiliVideoCatalog.Video video:videos)projected.addObject().put("id",video.id()).put("title",video.title()).put("partTitle",video.partTitle())
                .put("description",video.description()).put("author",video.author()).put("part",video.part()).put("durationSeconds",video.durationSeconds())
                .put("durationScope",video.part()==0?"whole-video":"part");
        audit.put("matchingVersion",MATCHING_VERSION).put("matchingInputCandidateCount",videos.size()).put("matchingInputChars",input.toString().length());
        ClassroomAiGateway.Result result=ai.structured("video-metadata-matching","""
            只根据已核验的视频元数据判断它是否适合当前学生及本节目标，不假装观看了视频。必须明确适龄且具体目标有支持才eligible。
            part=0是当前搜索返回的整视频资料，时长覆盖整个视频条目；不能据此假设分P、已读取详情或已验证播放可用性。
            contextGoals/classroomContext是完整课堂背景，targetGoals是本次优先补充的目标；只覆盖背景而不覆盖本次目标的视频不合格。
            本次目标可能来自已经按课程资料核对的遗漏或冲突，不据此推断学生能力，不降级年级或题目难度。
            泛讲整套课程、标题相近但内容不符、广告引流、危险操作、无法确定适龄返回eligible:false。
            返回JSON {candidates:[...]}，每个输入视频恰好一项，必须完整覆盖全部输入id，不能省略不合格项。
            不合格或无法确定适龄的条目只返回{id:输入视频id,eligible:false}，不输出理由、引文或其他字段。
            合格条目返回{id:输入视频id,eligible:true,gradeCompatibility:'compatible',goals:仅输入targetGoals中的目标数组,
            purpose:'recitation'|'pronunciation'|'listening'|'observation'|'procedure'|'concept'|'worked_example',
            reason:一句简短补充理由(不超过80字),metadataQuote:标题/分P标题/简介中最短且足以支持匹配的连续原文(不超过120字),preferenceMatch:boolean}。
            不复述整段简介，不引用视频外信息。合格项必须goals非空、明确适龄、有真实metadataQuote；不得为缩短输出放宽合格标准。
            preferenceMatch只表示是否符合用户explicitPreference；没有偏好时false。不提供置信度或推断用户能力。
            """,input.toString(),budget,value->validateMatches(value,targets,videos));
        audit.set("matchingCall",result.evidence());audit.set("matching",result.value());List<Matched> matches=new ArrayList<>();
        Map<String,BilibiliVideoCatalog.Video> byId=new HashMap<>();videos.forEach(v->byId.put(v.id(),v));
        for(JsonNode item:result.value().path("candidates")) {
            String id=item.path("id").asText();if(!item.path("eligible").asBoolean()) {rejected.addObject().put("id",id).put("reason","SEMANTIC_OR_GRADE_MISMATCH");continue;}
            TreeSet<String> goals=new TreeSet<>();item.path("goals").forEach(g->goals.add(g.asText()));
            matches.add(new Matched(byId.get(id),goals,item.path("purpose").asText(),item.path("reason").asText(),item.path("preferenceMatch").asBoolean()?1:0));
        }
        return matches;
    }
    static void validateMatches(JsonNode value,Set<String> targets,List<BilibiliVideoCatalog.Video> videos) {
        if(!value.path("candidates").isArray()||value.path("candidates").size()!=videos.size())throw new IllegalArgumentException("candidate coverage");
        Map<String,BilibiliVideoCatalog.Video> byId=new HashMap<>();videos.forEach(v->byId.put(v.id(),v));Set<String> seen=new HashSet<>();
        for(JsonNode item:value.path("candidates")) {
            String id=item.path("id").asText();BilibiliVideoCatalog.Video video=byId.get(id);
            if(video==null||!seen.add(id)||!item.path("eligible").isBoolean())throw new IllegalArgumentException("candidate identity");
            if(!item.path("eligible").asBoolean())continue;
            if(!"compatible".equals(item.path("gradeCompatibility").asText())||!VideoTeachingClassifier.PURPOSES.containsKey(item.path("purpose").asText())
                    ||!item.path("goals").isArray()||item.path("goals").isEmpty()||!item.path("preferenceMatch").isBoolean())throw new IllegalArgumentException("candidate compatibility");
            for(JsonNode goal:item.path("goals"))if(!goal.isTextual()||!targets.contains(goal.asText()))throw new IllegalArgumentException("unrelated goal");
            String quote=VideoTeachingClassifier.requireText(item,"metadataQuote",600);
            if(quote.length()<2||!ClassroomStudyContext.normalize(video.metadataText()).contains(quote))throw new IllegalArgumentException("metadata quote");
            VideoTeachingClassifier.requireText(item,"reason",200);
        }
    }
    static Map<String,String> duplicateGroups(List<BilibiliVideoCatalog.Video> videos) {
        Map<String,String> parent=new HashMap<>();videos.forEach(v->parent.put(v.id(),v.id()));
        for(int i=0;i<videos.size();i++)for(int j=i+1;j<videos.size();j++) {
            var a=videos.get(i);var b=videos.get(j);
            String aTitle=(a.title()+a.partTitle()).replaceAll("[\\p{P}\\p{Z}\\s]","").toLowerCase(Locale.ROOT);
            String bTitle=(b.title()+b.partTitle()).replaceAll("[\\p{P}\\p{Z}\\s]","").toLowerCase(Locale.ROOT);
            if(a.bvid().equals(b.bvid())||aTitle.equals(bTitle)) {String left=find(parent,a.id()),right=find(parent,b.id());if(left.compareTo(right)>0)parent.put(left,right);else parent.put(right,left);}
        }
        Map<String,String> result=new HashMap<>();videos.forEach(v->result.put(v.id(),find(parent,v.id())));return result;
    }
    private static String find(Map<String,String> parent,String id){String current=id;while(!parent.get(current).equals(current))current=parent.get(current);return current;}
    static double complementarity(ClassroomStudyContext.Snapshot context,String purpose) {
        if(Set.of("recitation","pronunciation","listening","observation","procedure").contains(purpose))return 1;
        return context.existingPurposes().contains("explanation")?0.5:0.8;
    }
    private List<String> oldSelection(ObjectNode previous,ClassroomStudyContext.Snapshot context) {
        if(previous==null||!context.fingerprint().equals(previous.path("contentFingerprint").asText())
                ||!RecommendationOptimizer.ALGORITHM_VERSION.equals(previous.path("algorithmVersion").asText()))return List.of();
        List<String> ids=new ArrayList<>();previous.path("items").forEach(i->ids.add(i.path("id").asText()));return ids;
    }
    private void finish(Job job,ObjectNode record,long started) {
        // 生成过程中课程被编辑时不发布旧内容的推荐；旧决策仅保留为其自身指纹下的历史证据。
        ClassroomStudyContext.Snapshot current=ClassroomStudyContext.from(courses.studySnapshot(job.request().context().courseId()),job.request().context().sceneId());
        Request currentRequest=request(current.courseId(),current.sceneId(),null);
        boolean obsolete=!current.fingerprint().equals(job.request().context().fingerprint())||!currentRequest.key().equals(job.request().key());
        if(obsolete){record.put("status","hidden");record.putArray("items");record.put("obsolete",true);}
        record.put("updatedAt",Instant.now().toString()).put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        ObjectNode intent=job.intent().deepCopy().put("phase",obsolete?"superseded":"finished").put("status",record.path("status").asText()).put("updatedAt",Instant.now().toString());
        List<ClassroomStudyStore.Write> writes=new ArrayList<>();writes.add(new ClassroomStudyStore.Write(DECISION,job.id(),current.courseId(),record));
        if(!obsolete)writes.add(new ClassroomStudyStore.Write(CACHE,job.request().key(),current.courseId(),record));
        writes.add(new ClassroomStudyStore.Write(INTENT,job.request().key(),current.courseId(),intent));store.saveBatch(writes);
    }
    private void fail(Job job,String kind,ObjectNode evidence) {
        try {
            ObjectNode record=job.working().isEmpty()?base(job.request(),"error"):job.working().deepCopy();record.put("status","error").putArray("items");
            record.put("courseId",job.request().context().courseId()).put("decisionId",job.id()).put("updatedAt",Instant.now().toString());
            ObjectNode audit=record.path("audit") instanceof ObjectNode existing?existing:record.putObject("audit");audit.put("failureKind",kind);if(evidence!=null)audit.set("modelCall",evidence);
            ObjectNode intent=job.intent().deepCopy().put("phase","unknown").put("status","error").put("updatedAt",Instant.now().toString());
            store.saveBatch(List.of(new ClassroomStudyStore.Write(DECISION,job.id(),job.request().context().courseId(),record),
                    new ClassroomStudyStore.Write(CACHE,job.request().key(),job.request().context().courseId(),record),
                    new ClassroomStudyStore.Write(INTENT,job.request().key(),job.request().context().courseId(),intent)));
        }catch(RuntimeException ignored){/* 持久层暂不可用时不能泄漏完整异常到卡片或阻断课堂。 */}
    }
    private ObjectNode base(Request request,String status) {
        ObjectNode result=json.createObjectNode().put("status",status).put("contentFingerprint",request.context().fingerprint())
                .put("sceneId",request.context().sceneId()).put("budgetMinutes",request.settings().minutes());result.putArray("items");return result;
    }
    static boolean fresh(ObjectNode value) {
        try {
            Instant now=Instant.now(),updated=Instant.parse(value.path("updatedAt").asText());
            if(updated.isAfter(now)||!updated.plus(CACHE_AGE).isAfter(now))return false;
            if("ready".equals(value.path("status").asText())) {
                if(!value.path("items").isArray()||value.path("items").isEmpty())return false;
                // 决策刚生成不能延长上游资料的核验期；取每条资料与决策时间的最早期限。
                for(JsonNode item:value.path("items")) {
                    Instant checked=Instant.parse(item.path("checkedAt").asText());
                    if(checked.isAfter(now)||!checked.plus(CACHE_AGE).isAfter(now))return false;
                }
            }
            return true;
        }catch(RuntimeException invalid){return false;}
    }
    private ObjectNode publicView(ObjectNode saved) {
        ObjectNode out=json.createObjectNode();for(String key:List.of("status","contentFingerprint","decisionId","sceneId","budgetMinutes","classification","items","nextPollMs","updatedAt"))
            if(saved.has(key))out.set(key,saved.get(key).deepCopy());return out;
    }
    private static boolean containsItem(ObjectNode decision,String resource){for(JsonNode item:decision.path("items"))if(resource.equals(item.path("id").asText()))return true;return false;}
    private static boolean validId(String value){return value!=null&&!value.isBlank()&&value.length()<=100&&value.matches("[A-Za-z0-9._:-]+");}
    @Override @PreDestroy public void close(){closed=true;worker.shutdownNow();synchronized(jobs){queue.clear();jobs.clear();}}
}
