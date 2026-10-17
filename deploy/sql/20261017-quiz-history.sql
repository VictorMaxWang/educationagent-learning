-- 作答事件保持追加；请求票据及错题队列状态独立存放，不重写原quiz_answer或QQ演示答卷。
CREATE TABLE IF NOT EXISTS quiz_attempt_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    paper_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    question_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'prepared',
    result_json MEDIUMTEXT NULL,
    last_evaluation_id VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_quiz_attempt_request (user_id,request_id),
    KEY idx_quiz_attempt_paper (paper_id,created_at)
);

CREATE TABLE IF NOT EXISTS quiz_mistake_resolution (
    user_id VARCHAR(128) NOT NULL,
    question_id BIGINT NOT NULL,
    resolved TINYINT NOT NULL DEFAULT 0,
    request_sequence BIGINT NOT NULL,
    resolution_answer_id BIGINT NULL,
    resolution_kind VARCHAR(16) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id,question_id),
    KEY idx_quiz_resolution_sequence (request_sequence)
);
