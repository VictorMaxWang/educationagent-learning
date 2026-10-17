package com.demo.edu.classroom.study;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;

/**
 * 推荐与学习反馈的增量存储。独立于课程正文，避免保存一张卡片覆盖播放位置或旧成绩。
 * 演示站沿用共享 classroom 身份；kind 与 entryId 只是业务记录的命名空间，不伪装成账号隔离。
 */
@Repository
public class ClassroomStudyStore {
    // LIMIT必须留在派生表里，先从覆盖索引选中少量ID，再按主键取正文；避免排序历史记录时读取全部LONGTEXT。
    // 外层排序只处理已经限量的键，且单条SQL保持同一读快照，避免两次查询之间记录移页或被删除造成混合结果。
    private static final String RECENT_DOCUMENTS_SQL="SELECT body.document FROM (SELECT entry_id,updated_at FROM edu_classroom_study_state "
            +"WHERE kind=? AND course_id=? ORDER BY updated_at DESC,entry_id LIMIT ?) recent_ids "
            +"JOIN edu_classroom_study_state body ON body.kind=? AND body.entry_id=recent_ids.entry_id ORDER BY recent_ids.updated_at DESC,recent_ids.entry_id";
    private static final String SCENE_DOCUMENTS_SQL="SELECT body.document FROM (SELECT entry_id,created_at FROM edu_classroom_study_state "
            +"WHERE kind=? AND course_id=? AND scene_id=? ORDER BY created_at DESC,entry_id LIMIT ?) recent_ids "
            +"JOIN edu_classroom_study_state body ON body.kind=? AND body.entry_id=recent_ids.entry_id ORDER BY recent_ids.created_at DESC,recent_ids.entry_id";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @Value("${classroom.study-cache.vector-retention-days:30}") private int vectorRetentionDays=30;

    public ClassroomStudyStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    public Optional<ObjectNode> find(String kind, String entryId) {
        check(kind,entryId);
        List<String> rows=jdbc.queryForList("SELECT document FROM edu_classroom_study_state WHERE kind=? AND entry_id=?",String.class,kind,entryId);
        return rows.isEmpty()?Optional.empty():Optional.of(parse(rows.getFirst()));
    }

    /** 公开课程列表批量读可见性标记；最多200项，一次参数化查询，避免N+1读取。 */
    public Map<String,ObjectNode> findMany(String kind,Collection<String> entryIds) {
        check(kind,"batch");
        if(entryIds==null||entryIds.size()>200)throw new IllegalArgumentException("学习记录批量标识无效");
        if(entryIds.isEmpty())return Map.of();
        var ids=new LinkedHashSet<String>();for(String id:entryIds){check(kind,id);ids.add(id);}
        var parameters=new ArrayList<Object>();parameters.add(kind);parameters.addAll(ids);
        var result=new LinkedHashMap<String,ObjectNode>();
        jdbc.query("SELECT entry_id,document FROM edu_classroom_study_state WHERE kind=? AND entry_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",
            rs->{result.put(rs.getString("entry_id"),parse(rs.getString("document")));},parameters.toArray());
        return Collections.unmodifiableMap(result);
    }

    /** 首次尝试用 create 保证不可被同 ID 重放覆盖；后续修订应另建 ID 并关联父尝试。 */
    public boolean create(String kind,String entryId,String courseId,ObjectNode document) {
        check(kind,entryId);checkCourse(courseId);
        return jdbc.update("INSERT IGNORE INTO edu_classroom_study_state(kind,entry_id,course_id,scene_id,document) VALUES(?,?,?,?,?)",
            kind,entryId,courseId,scene(document),document.toString())==1;
    }

    public void save(String kind,String entryId,String courseId,ObjectNode document) {
        check(kind,entryId);checkCourse(courseId);
        jdbc.update("INSERT INTO edu_classroom_study_state(kind,entry_id,course_id,scene_id,document) VALUES(?,?,?,?,?) "
            +"ON DUPLICATE KEY UPDATE course_id=VALUES(course_id),scene_id=VALUES(scene_id),document=VALUES(document),updated_at=CURRENT_TIMESTAMP(3)",
            kind,entryId,courseId,scene(document),document.toString());
    }

    public record Write(String kind,String entryId,String courseId,ObjectNode document){}
    /** 意图、当前设置与决策回执应一起提交，避免重启时把“写了一半”误判为可自动重发。 */
    @Transactional
    public void saveBatch(List<Write> writes) {
        if(writes==null||writes.isEmpty()||writes.size()>64)throw new IllegalArgumentException("学习记录批量写入无效");
        for(Write write:writes){if(write==null||write.document()==null)throw new IllegalArgumentException("学习记录不能为空");check(write.kind(),write.entryId());checkCourse(write.courseId());scene(write.document());}
        for(Write write:writes)save(write.kind(),write.entryId(),write.courseId(),write.document());
    }

    public List<ObjectNode> list(String kind,String courseId,int limit) {
        check(kind,"list");checkCourse(courseId);
        return jdbc.queryForList(RECENT_DOCUMENTS_SQL,String.class,kind,courseId,Math.max(1,Math.min(limit,200)),kind).stream().map(this::parse).toList();
    }

    /** 先按页面过滤再限量，不能让另一页的大量尝试把当前页历史挤出最近记录窗口。 */
    public List<ObjectNode> listForScene(String kind,String courseId,String sceneId,int limit) {
        check(kind,"list");checkCourse(courseId);
        if(sceneId==null||sceneId.isBlank()||sceneId.length()>80)throw new IllegalArgumentException("页面标识无效");
        // 旧尝试查看提示会更新 updated_at，但不能使它在新版本之前重新成为当前学习事实。
        return jdbc.queryForList(SCENE_DOCUMENTS_SQL,String.class,kind,courseId,sceneId,Math.max(1,Math.min(limit,200)),kind).stream().map(this::parse).toList();
    }

    private static String scene(ObjectNode document) {
        String value=document.path("sceneId").asText("");
        if(value.length()>80)throw new IllegalArgumentException("页面标识过长");
        return value;
    }

    /** 仅清除可重建的新推荐向量；不按模糊模型名删除，也不触碰课程、学习尝试或旧 Chroma。 */
    public int deleteRecommendationVectors(ObjectNode identity) {
        if(identity==null||identity.isEmpty())throw new IllegalArgumentException("向量身份不能为空");
        int deleted=0;boolean exactCount=true;String after="";
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("向量清理已取消");
            // 主键(kind,entry_id)键集分页：单批最多200条，不把全部向量正文一次载入JVM。
            var rows=jdbc.query("SELECT entry_id,document FROM edu_classroom_study_state WHERE kind='recommendation_vector' AND entry_id>? ORDER BY entry_id LIMIT 200",
                (rs,index)->Map.entry(rs.getString("entry_id"),rs.getString("document")),after);
            if(rows.isEmpty())break;
            var matching=rows.stream().filter(row->parse(row.getValue()).path("identity").equals(identity)).toList();
            if(!matching.isEmpty()) {
                int[] counts=jdbc.batchUpdate("DELETE FROM edu_classroom_study_state WHERE kind='recommendation_vector' AND entry_id=? AND document=?",new org.springframework.jdbc.core.BatchPreparedStatementSetter(){
                    public int getBatchSize(){return matching.size();}
                    public void setValues(java.sql.PreparedStatement statement,int index)throws java.sql.SQLException{statement.setString(1,matching.get(index).getKey());statement.setString(2,matching.get(index).getValue());}
                });
                for(int count:counts){if(count==java.sql.Statement.EXECUTE_FAILED)throw new org.springframework.dao.DataAccessResourceFailureException("向量缓存批次清理未完成");if(count>=0)deleted+=count;else exactCount=false;}
            }
            after=rows.getLast().getKey();
        }
        return exactCount?deleted:-1;
    }

    /** 只清理可重建缓存。学习、推荐决策审计、幂等回执和发布记录永不进入此白名单。 */
    @Scheduled(fixedDelayString="${classroom.study-cache.cleanup-ms:600000}",initialDelayString="${classroom.study-cache.cleanup-ms:600000}")
    public void cleanupRebuildableCaches() {
        try {
            purgeExpiredCache("recommendation_cache",Instant.now().minus(Duration.ofHours(24)),200);
            purgeExpiredCache("bili_verified_metadata",Instant.now().minus(Duration.ofHours(24)),200);
            purgeExpiredCache("recommendation_vector",Instant.now().minus(Duration.ofDays(Math.max(1,Math.min(365,vectorRetentionDays)))),200);
        } catch(org.springframework.dao.DataAccessException unavailable){/* 定时维护失败不阻断课堂；下一个有界批次再继续。 */}
    }

    public int purgeExpiredCache(String kind,Instant cutoff,int limit) {
        if(!java.util.Set.of("recommendation_cache","bili_verified_metadata","recommendation_vector").contains(kind)||cutoff==null)
            throw new IllegalArgumentException("只允许清理可重建推荐缓存");
        int bounded=Math.max(1,Math.min(200,limit));Timestamp before=Timestamp.from(cutoff);
        List<String> ids=jdbc.queryForList("SELECT entry_id FROM edu_classroom_study_state WHERE kind=? AND updated_at<? ORDER BY updated_at,entry_id LIMIT ?",String.class,kind,before,bounded);
        if(ids.isEmpty())return 0;
        var parameters=new ArrayList<Object>();parameters.add(kind);parameters.add(before);parameters.addAll(ids);
        // 删除前再次核对时间，不能删除选中后已被另一请求更新的新缓存。
        return jdbc.update("DELETE FROM edu_classroom_study_state WHERE kind=? AND updated_at<? AND entry_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",parameters.toArray());
    }

    private ObjectNode parse(String text) {
        try { var value=json.readTree(text);if(value instanceof ObjectNode object)return object; }
        catch(Exception ignored) { /* 异常记录不当作空历史，保留数据库原文供修复。 */ }
        throw new ClassroomException(500,"学习记录格式异常，请稍后重试");
    }
    private static void check(String kind,String id) {
        if(kind==null||!kind.matches("[a-z][a-z0-9_-]{0,39}")||id==null||id.isBlank()||id.length()>160)
            throw new IllegalArgumentException("学习记录标识无效");
    }
    private static void checkCourse(String id) {
        if(id==null||id.isBlank()||id.length()>64)throw new IllegalArgumentException("课程标识无效");
    }
}
