package com.demo.chroma.eval;

/**
 * 已有评估批次在运行 —— 触发方（手动 /run /retry 或定时任务）重入。
 *
 * <p>对应 HTTP 409，而不是 500：这不是服务出错，是「稍后再试」。</p>
 */
public class RagEvalBusyException extends RuntimeException {

    public RagEvalBusyException(String message) {
        super(message);
    }
}
