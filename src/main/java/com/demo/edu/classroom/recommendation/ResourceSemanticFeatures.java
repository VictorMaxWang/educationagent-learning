package com.demo.edu.classroom.recommendation;

import com.demo.chroma.base.EmbeddingService;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** 新资源的独立向量缓存；不触碰既有知识库集合，不把同维度误认为同一个语义空间。 */
@Component
public class ResourceSemanticFeatures {
    public static final String NORMALIZATION="metadata-whitespace-v1";
    private final EmbeddingService embeddings;private final ClassroomStudyStore store;private final ObjectMapper json;
    public record Features(Map<String,Double> relevance,Map<String,Double> pairs,ObjectNode evidence) {}
    public static final class Failure extends IllegalStateException {
        private final ObjectNode evidence;
        Failure(String reason,ObjectNode evidence){super(reason);this.evidence=evidence.deepCopy();}
        public ObjectNode evidence(){return evidence.deepCopy();}
    }
    public ResourceSemanticFeatures(EmbeddingService embeddings,ClassroomStudyStore store,ObjectMapper json) {this.embeddings=embeddings;this.store=store;this.json=json;}
    public ObjectNode identity(){return json.valueToTree(embeddings.describeIdentity());}

    public Features compute(String courseId,String query,List<BilibiliVideoCatalog.Video> videos) {
        return compute(courseId,query,videos,Duration.ofSeconds(60));
    }
    public Features compute(String courseId,String query,List<BilibiliVideoCatalog.Video> videos,Duration budget) {
        RecommendationDeadline deadline=new RecommendationDeadline(budget);
        EmbeddingService.Identity identity=embeddings.describeIdentity();
        if(identity==null||identity.dimension()<1||identity.model()==null||identity.model().isBlank())throw new IllegalStateException("EMBEDDING_IDENTITY_UNAVAILABLE");
        ObjectNode basis=json.valueToTree(identity);basis.put("normalization",NORMALIZATION);
        ObjectNode evidence=json.createObjectNode().put("source","existing-EmbeddingService-once").put("adapterMode",identity.provider())
                .put("requestedModel",identity.model()).put("dimension",identity.dimension()).put("normalization",NORMALIZATION)
                .put("endpointFingerprint",identity.endpointFingerprint()).put("similarity","clamped-cosine-0-1")
                .put("featurePrecision",10000).put("scope","metadata").put("count",videos.size()+1).put("cacheHitCount",0);
        evidence.putArray("batches");evidence.putArray("cachedOrigins");
        List<String> texts=new ArrayList<>();texts.add(query);videos.forEach(v->texts.add(v.metadataText()));
        List<List<Float>> vectors;
        try { vectors=vectors(courseId,texts,basis,identity.dimension(),deadline,evidence); }
        catch(Failure failure){throw failure;}
        catch(RuntimeException unknown){throw new Failure(unknown.getMessage()==null?"EMBEDDING_RESPONSE_UNKNOWN":unknown.getMessage(),evidence);}
        List<Float> queryVector=vectors.getFirst();
        Map<String,List<Float>> all=new LinkedHashMap<>();Map<String,Double> relevance=new TreeMap<>(),pairs=new TreeMap<>();
        for(int i=0;i<videos.size();i++) {
            BilibiliVideoCatalog.Video video=videos.get(i);
            List<Float> vector=vectors.get(i+1);all.put(video.id(),vector);
            relevance.put(video.id(),cosine01(queryVector,vector));
        }
        for(int i=0;i<videos.size();i++)for(int j=i+1;j<videos.size();j++) {
            String left=videos.get(i).id(),right=videos.get(j).id();pairs.put(RecommendationOptimizer.pairKey(left,right),cosine01(all.get(left),all.get(right)));
        }
        // 配置在一次计算中变化时整次结果失效，不能把两种模型的结果拼接为一个决策。
        if(!identity.equals(embeddings.describeIdentity()))throw new IllegalStateException("EMBEDDING_IDENTITY_CHANGED");
        deadline.check();
        evidence.put("mode",evidence.path("batches").isEmpty()?"cache-only":"live");
        return new Features(Collections.unmodifiableMap(relevance),Collections.unmodifiableMap(pairs),evidence);
    }
    private List<List<Float>> vectors(String courseId,List<String> raw,ObjectNode identity,int dimension,RecommendationDeadline deadline,ObjectNode evidence) {
        List<String> texts=raw.stream().map(ClassroomStudyContext::normalize).toList();List<String> ids=new ArrayList<>();
        List<List<Float>> all=new ArrayList<>(Collections.nCopies(texts.size(),null));List<Integer> missing=new ArrayList<>();
        List<ObjectNode> origins=new ArrayList<>(Collections.nCopies(texts.size(),null));Set<String> cachedOrigins=new HashSet<>();
        ModelSpace modelSpace=new ModelSpace(identity,evidence);
        for(int i=0;i<texts.size();i++) {
            deadline.check();
            String id=ClassroomStudyContext.hash(identity.deepCopy().put("text",texts.get(i)));ids.add(id);
            Optional<ObjectNode> cached=store.find("recommendation_vector",id);
            if(cached.isPresent()) {
                ObjectNode saved=cached.get();List<Float> values=new ArrayList<>();saved.path("values").forEach(n->values.add(n.isNumber()?n.floatValue():Float.NaN));
                if(saved.path("identity").equals(identity)&&valid(values,dimension)){
                    all.set(i,List.copyOf(values));evidence.put("cacheHitCount",evidence.path("cacheHitCount").asInt()+1);
                    if(saved.path("origin") instanceof ObjectNode origin&&cachedOrigins.add(ClassroomStudyContext.hash(origin)))
                        ((ArrayNode)evidence.path("cachedOrigins")).add(origin.deepCopy().put("cacheHit",true));
                    modelSpace.observe(saved.path("origin"));
                    continue;
                }
            }
            missing.add(i);
        }
        for(int start=0;start<missing.size();start+=10) {
            assertIdentity(identity);
            List<Integer> indices=missing.subList(start,Math.min(start+10,missing.size()));
            EmbeddingService.BatchResult result=embeddings.generateEmbeddingsOnceWithEvidence(indices.stream().map(texts::get).toList(),deadline.remaining(Duration.ofSeconds(20)));
            if(result==null)throw new Failure("EMBEDDING_RESPONSE_UNKNOWN",evidence);
            ObjectNode call=result.evidence().deepCopy().put("inputCount",indices.size());
            ((ArrayNode)evidence.path("batches")).add(call);
            modelSpace.observe(call);
            List<List<Float>> batch=result.vectors();
            if(batch==null||batch.size()!=indices.size()||batch.stream().anyMatch(v->!valid(v,dimension)))
                throw new Failure("EMBEDDING_RESPONSE_UNKNOWN",evidence);
            assertIdentity(identity);
            for(int j=0;j<indices.size();j++){all.set(indices.get(j),List.copyOf(batch.get(j)));origins.set(indices.get(j),call);}
        }
        // 所有批次均有效才保存，半途未知不会形成一份看似完整的新推荐。
        for(int index:missing) {
            deadline.check();
            ObjectNode document=json.createObjectNode().put("createdAt",Instant.now().toString());document.set("identity",identity);document.set("values",json.valueToTree(all.get(index)));
            document.set("origin",origins.get(index));
            store.save("recommendation_vector",ids.get(index),courseId,document);
        }
        return all;
    }

    /**
     * 兼容网关的请求模型可能是别名。同次计算含缓存若收到两个不同的真实返回模型，停止混算。
     * 没有返回 model 只是未知：保留 null 并标记无法完整核验，绝不补成配置模型后制造冲突。
     */
    private final class ModelSpace {
        private final ObjectNode identity,evidence;private final Set<String> known=new TreeSet<>();private boolean unknown;
        ModelSpace(ObjectNode identity,ObjectNode evidence){this.identity=identity;this.evidence=evidence;}
        void observe(com.fasterxml.jackson.databind.JsonNode origin) {
            var model=origin.path("model");
            if(model.isTextual()&&!model.asText().isBlank())known.add(model.asText());else unknown=true;
            evidence.set("actualModels",json.valueToTree(known));
            evidence.put("versionVerification",known.size()>1?"changed":unknown?known.isEmpty()?"unknown":"partial":"complete");
            if(known.size()<=1)return;
            evidence.put("failureKind","MODEL_SPACE_CHANGED");ObjectNode invalidation=evidence.putObject("cacheInvalidation");
            invalidation.put("scope","recommendation_vector:same-identity");
            try{invalidation.put("cleared",true).put("removedRows",store.deleteRecommendationVectors(identity));}
            catch(RuntimeException unavailable){invalidation.put("cleared",false).putNull("removedRows");}
            throw new Failure("MODEL_SPACE_CHANGED",evidence);
        }
    }
    private void assertIdentity(ObjectNode expected) {
        ObjectNode current=json.valueToTree(embeddings.describeIdentity());current.put("normalization",NORMALIZATION);
        if(!expected.equals(current))throw new IllegalStateException("EMBEDDING_IDENTITY_CHANGED");
    }
    private static boolean valid(List<Float> values,int dimension) {
        return values!=null&&values.size()==dimension&&values.stream().allMatch(v->v!=null&&Float.isFinite(v))&&values.stream().anyMatch(v->v!=0);
    }
    static double cosine01(List<Float> left,List<Float> right) {
        if(left.size()!=right.size())throw new IllegalArgumentException("embedding dimensions differ");
        double dot=0,a=0,b=0;for(int i=0;i<left.size();i++){dot+=left.get(i)*right.get(i);a+=left.get(i)*left.get(i);b+=right.get(i)*right.get(i);}
        if(a==0||b==0)throw new IllegalArgumentException("zero vector");
        return Math.round(Math.max(0,Math.min(1,dot/Math.sqrt(a*b)))*10000d)/10000d;
    }
}
