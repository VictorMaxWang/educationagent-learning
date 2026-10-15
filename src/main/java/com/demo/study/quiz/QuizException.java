package com.demo.study.quiz;

/**
 * 刷题业务异常（如文件无法提取、未解析到题目等），
 * 由控制器捕获后返回 400 及错误提示。
 */
public class QuizException extends RuntimeException {

    public QuizException(String message) {
        super(message);
    }
}
