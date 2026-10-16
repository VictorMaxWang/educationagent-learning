-- 增量、可重复执行；发布回退不删除已产生的学习记录。
CREATE TABLE IF NOT EXISTS edu_classroom_study_state (
    kind VARCHAR(40) NOT NULL,
    entry_id VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
    course_id VARCHAR(64) NOT NULL,
    scene_id VARCHAR(80) NOT NULL DEFAULT '',
    document LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (kind, entry_id),
    KEY idx_study_course_kind_updated (course_id, kind, scene_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='共享课堂推荐与可追溯学习反馈';
