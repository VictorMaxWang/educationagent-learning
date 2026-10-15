package com.demo.chroma.eval;

/**
 * RAG 评估的运行期故障：集合为空、Chroma 不可达、embedding/LLM 不可用等。
 *
 * <p>与 {@link IllegalArgumentException}（入参非法 → 400）和
 * {@link RagEvalBusyException}（已有批次在跑 → 409）区分开，
 * 让 Controller 能映射到正确的 HTTP code。</p>
 */
public class RagEvalException extends RuntimeException {

    public RagEvalException(String message) {
        super(message);
    }

    public RagEvalException(String message, Throwable cause) {
        super(message, cause);
    }
}
