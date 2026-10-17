-- 仅新增查询/维护索引；不删除学习历史、审计或课程数据。可重复执行。
SET @study_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_classroom_study_state' AND index_name='idx_study_course_kind_created'),'SELECT 1','CREATE INDEX idx_study_course_kind_created ON edu_classroom_study_state(course_id,kind,scene_id,created_at DESC,entry_id)');
PREPARE study_index_stmt FROM @study_index_sql; EXECUTE study_index_stmt; DEALLOCATE PREPARE study_index_stmt;
SET @study_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_classroom_study_state' AND index_name='idx_study_course_kind_recent'),'SELECT 1','CREATE INDEX idx_study_course_kind_recent ON edu_classroom_study_state(course_id,kind,updated_at DESC,entry_id)');
PREPARE study_index_stmt FROM @study_index_sql; EXECUTE study_index_stmt; DEALLOCATE PREPARE study_index_stmt;
SET @study_index_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_classroom_study_state' AND index_name='idx_study_kind_updated'),'SELECT 1','CREATE INDEX idx_study_kind_updated ON edu_classroom_study_state(kind,updated_at,entry_id)');
PREPARE study_index_stmt FROM @study_index_sql; EXECUTE study_index_stmt; DEALLOCATE PREPARE study_index_stmt;
