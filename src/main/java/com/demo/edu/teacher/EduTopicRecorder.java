package com.demo.edu.teacher;

import com.demo.edu.mapper.EduTopicAskMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 专题提问计数：学生问到教育专题时，给所在班级的当日该专题 +1。
 *
 * <p>与危机计数同款隐私口径：<b>只写 班级+日期+专题+计数</b>，不写 user_id、不写提问原文。
 * 统计口径是"提问条数"而不是"人数"——不引入用户维度，就不可能被反推到某个学生。
 *
 * <p>永不抛异常、永不阻塞主流程：它是旁路统计，失败了也不该影响学生拿到回答。
 */
@Slf4j
@Component
public class EduTopicRecorder {

    private static final int MAX_TOPIC_LEN = 32;

    private final EduTopicAskMapper topicMapper;
    private final EduUserClassMapper userClassMapper;

    public EduTopicRecorder(EduTopicAskMapper topicMapper, EduUserClassMapper userClassMapper) {
        this.topicMapper = topicMapper;
        this.userClassMapper = userClassMapper;
    }

    /**
     * @param userId 学生标识（仅用于查班，不落库）
     * @param topic  专题标识（如 lifesafety / sexedu）
     */
    public void record(String userId, String topic) {
        try {
            if (userId == null || userId.isBlank() || topic == null || topic.isBlank()) return;
            String classId = userClassMapper.selectClassIdByUserId(userId);
            if (classId == null || classId.isBlank()) {
                log.debug("专题提问未计数：用户未归属班级");
                return;
            }
            String normalized = topic.length() > MAX_TOPIC_LEN ? topic.substring(0, MAX_TOPIC_LEN) : topic;
            topicMapper.increment(classId, LocalDate.now(), normalized);
        } catch (Exception e) {
            log.debug("专题提问计数失败（不影响回答）：{}", e.toString());
        }
    }
}
