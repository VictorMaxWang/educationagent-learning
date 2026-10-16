package com.demo.edu.classroom.study;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * 推荐与学习反馈的增量存储。独立于课程正文，避免保存一张卡片覆盖播放位置或旧成绩。
 * 演示站沿用共享 classroom 身份；kind 与 entryId 只是业务记录的命名空间，不伪装成账号隔离。
 */
@Repository
public class ClassroomStudyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ClassroomStudyStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    public Optional<ObjectNode> find(String kind, String entryId) {
        check(kind,entryId);
        List<String> rows=jdbc.queryForList("SELECT document FROM edu_classroom_study_state WHERE kind=? AND entry_id=?",String.class,kind,entryId);
        return rows.isEmpty()?Optional.empty():Optional.of(parse(rows.getFirst()));
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

    public List<ObjectNode> list(String kind,String courseId,int limit) {
        check(kind,"list");checkCourse(courseId);
        return jdbc.queryForList("SELECT document FROM edu_classroom_study_state WHERE kind=? AND course_id=? ORDER BY updated_at DESC,entry_id LIMIT ?",
            String.class,kind,courseId,Math.max(1,Math.min(limit,200))).stream().map(this::parse).toList();
    }

    /** 先按页面过滤再限量，不能让另一页的大量尝试把当前页历史挤出最近记录窗口。 */
    public List<ObjectNode> listForScene(String kind,String courseId,String sceneId,int limit) {
        check(kind,"list");checkCourse(courseId);
        if(sceneId==null||sceneId.isBlank()||sceneId.length()>80)throw new IllegalArgumentException("页面标识无效");
        // 旧尝试查看提示会更新 updated_at，但不能使它在新版本之前重新成为当前学习事实。
        return jdbc.queryForList("SELECT document FROM edu_classroom_study_state WHERE kind=? AND course_id=? AND scene_id=? ORDER BY created_at DESC,entry_id LIMIT ?",
            String.class,kind,courseId,sceneId,Math.max(1,Math.min(limit,200))).stream().map(this::parse).toList();
    }

    private static String scene(ObjectNode document) {
        String value=document.path("sceneId").asText("");
        if(value.length()>80)throw new IllegalArgumentException("页面标识过长");
        return value;
    }

    /** 仅清除可重建的新推荐向量；不按模糊模型名删除，也不触碰课程、学习尝试或旧 Chroma。 */
    public int deleteRecommendationVectors(ObjectNode identity) {
        if(identity==null||identity.isEmpty())throw new IllegalArgumentException("向量身份不能为空");
        int deleted=0;
        for(var row:jdbc.query("SELECT entry_id,document FROM edu_classroom_study_state WHERE kind='recommendation_vector'",
                (rs,index)->Map.entry(rs.getString("entry_id"),rs.getString("document")))) {
            String entryId=row.getKey();
            ObjectNode document=parse(row.getValue());
            if(document.path("identity").equals(identity))
                deleted+=jdbc.update("DELETE FROM edu_classroom_study_state WHERE kind='recommendation_vector' AND entry_id=?",entryId);
        }
        return deleted;
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
