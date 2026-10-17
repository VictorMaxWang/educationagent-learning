package com.demo.edu.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4 后置内容校验测试（不真调 LLM：通过 {@code setLlmCallerForTest} 注入假裁判）。
 *
 * <h3>守三件容易做错的事</h3>
 * <ol>
 *   <li><b>失败方向</b>：裁判超时/熔断/输出格式不对时一律"无法判定 = 不阻断"——
 *       内容是"再加一道保险"，不是安全前提，不能让它自己挂了就把所有回答替换掉；</li>
 *   <li><b>模式语义</b>：log 只记录、enforce 才替换/追加更正；</li>
 *   <li><b>固定话术不被复核</b>：热线引导话术是逐字审过的，再花一次 LLM 既浪费、
 *       又有被误判后把热线号码替换掉的风险。</li>
 * </ol>
 */
class EduContentSafetyJudgeTest {

    /** 构造一个假裁判返回体：{"safe":X,"category":"...","reason":"..."} */
    private static String llmResponse(boolean safe, String category) {
        return "{\"choices\":[{\"message\":{\"content\":\"{\\\"safe\\\":" + safe
                + ",\\\"category\\\":\\\"" + category + "\\\",\\\"reason\\\":\\\"测试\\\"}\"}}]}";
    }

    private static EduContentSafetyProperties props(String mode) {
        EduContentSafetyProperties p = new EduContentSafetyProperties();
        p.setMode(mode);
        return p;
    }

    @Test
    @DisplayName("enforce：判定不合适时给出替换话术")
    void enforceReplacesUnsafeAnswer() {
        EduContentSafetyProperties p = props("enforce");
        EduContentSafetyJudge judge = new EduContentSafetyJudge(p);
        judge.setLlmCallerForTest(prompt -> llmResponse(false, "sex_explicit"));

        var replaced = judge.replacementIfUnsafe("一段不合适的回答");
        assertTrue(replaced.isPresent(), "enforce 模式应替换");
        assertEquals(p.getReplacement(), replaced.get(), "应当返回配置里的替换话术（而不是另写一份）");
        assertTrue(replaced.get().contains("合适"), "替换话术要让用户看得懂发生了什么");
    }

    @Test
    @DisplayName("log：判定不合适也只记录、不替换")
    void logModeOnlyRecords() {
        EduContentSafetyJudge judge = new EduContentSafetyJudge(props("log"));
        judge.setLlmCallerForTest(prompt -> llmResponse(false, "violence"));

        assertTrue(judge.replacementIfUnsafe("一段不合适的回答").isEmpty(), "log 模式不该替换");
        assertTrue(judge.streamCorrectionIfUnsafe("一段不合适的回答").isEmpty(), "log 模式不该追加更正");

        var verdict = judge.judge("一段不合适的回答");
        assertTrue(verdict.evaluated(), "仍然要判定并记录（这就是误报率的原始数据）");
        assertFalse(verdict.safe());
        assertEquals("violence", verdict.category());
    }

    @Test
    @DisplayName("片段与正文一起判：检索片段会进入同一次裁判提示词")
    void snippetsGoIntoSameJudgeCall() {
        StringBuilder seen = new StringBuilder();
        AtomicInteger calls = new AtomicInteger();
        EduContentSafetyJudge judge = new EduContentSafetyJudge(props("log"));
        judge.setLlmCallerForTest(prompt -> {
            calls.incrementAndGet();
            seen.append(prompt);
            return llmResponse(true, "");
        });

        judge.judge("回答正文", java.util.List.of("检索到的片段内容A", "片段B"));

        assertEquals(1, calls.get(), "片段与正文必须同一次调用判完，不能翻倍");
        assertTrue(seen.toString().contains("检索到的片段内容A"), "片段应进入提示词：" + seen);
        assertTrue(seen.toString().contains("回答正文"));
        assertTrue(seen.toString().contains("参考资料片段"), "提示词应标明片段区块");
    }

    @Test
    @DisplayName("安全的回答不替换、不追加")
    void safeAnswerPassesThrough() {
        EduContentSafetyJudge judge = new EduContentSafetyJudge(props("enforce"));
        judge.setLlmCallerForTest(prompt -> llmResponse(true, ""));

        assertTrue(judge.replacementIfUnsafe("正常的科普回答").isEmpty());
        assertTrue(judge.streamCorrectionIfUnsafe("正常的科普回答").isEmpty());
    }

    @Test
    @DisplayName("流式路径：enforce 下返回追加更正（而不是替换——已输出的撤回不了）")
    void streamPathAppendsCorrection() {
        EduContentSafetyJudge judge = new EduContentSafetyJudge(props("enforce"));
        judge.setLlmCallerForTest(prompt -> llmResponse(false, "horror"));

        var correction = judge.streamCorrectionIfUnsafe("已经推给用户的回答");
        assertTrue(correction.isPresent());
        assertTrue(correction.get().contains("⚠️"), "更正提示要显眼：" + correction.get());
        assertTrue(correction.get().contains("老师") || correction.get().contains("教材"),
                "更正提示要给出正确做法：" + correction.get());
    }

    @Test
    @DisplayName("裁判失败/超时 → 未判定（不阻断），且连续失败后熔断不再调用")
    void failuresDegradeToUnknownAndOpenCircuit() {
        AtomicInteger calls = new AtomicInteger();
        EduContentSafetyJudge judge = new EduContentSafetyJudge(props("enforce"));
        judge.setLlmCallerForTest(prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("超时");
        });

        // 连续失败 3 次（阈值）→ 每次都按"未判定"处理
        for (int i = 0; i < 3; i++) {
            var v = judge.judge("文本");
            assertFalse(v.evaluated(), "失败时不该判定");
            assertTrue(v.safe(), "未判定必须按通过处理，不能把保险做成单点故障");
        }
        assertEquals(3, calls.get());

        // 第 4 次：熔断已打开 → 不应该再调 LLM
        var afterOpen = judge.judge("文本");
        assertFalse(afterOpen.evaluated());
        assertEquals(3, calls.get(), "熔断打开后不应继续调用裁判");
        assertTrue(judge.circuitOpen());

        // 失败的判定也不能产生替换
        assertTrue(judge.replacementIfUnsafe("文本").isEmpty());
    }

    @Test
    @DisplayName("裁判输出格式不对 → 未判定（不因为格式问题把回答替换掉）")
    void malformedLlmOutputIsNotUnsafe() {
        EduContentSafetyJudge judge = new EduContentSafetyJudge(props("enforce"));
        judge.setLlmCallerForTest(prompt -> "{\"choices\":[{\"message\":{\"content\":\"我觉得还行\"}}]}");

        var verdict = judge.judge("文本");
        assertFalse(verdict.evaluated(), "解析不出 JSON 就是未判定");
        assertNull(verdict.category());
        assertTrue(judge.replacementIfUnsafe("文本").isEmpty());
    }

    @Test
    @DisplayName("解析容错：模型带 ```json 包裹也能取到判据")
    void parseToleratesMarkdownFence() {
        String wrapped = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"safe\\\":false,"
                + "\\\"category\\\":\\\"discrimination\\\",\\\"reason\\\":\\\"测试\\\"}\\n```\"}}]}";
        EduContentSafetyJudge.Verdict v = EduContentSafetyJudge.parseVerdict(wrapped);
        assertTrue(v.evaluated());
        assertFalse(v.safe());
        assertEquals("discrimination", v.category());
    }

    @Test
    @DisplayName("开关关闭 → 未判定（行为与改造前一致）")
    void disabledMeansNoCheck() {
        EduContentSafetyProperties p = props("enforce");
        p.setEnabled(false);
        EduContentSafetyJudge judge = new EduContentSafetyJudge(p);
        judge.setLlmCallerForTest(prompt -> {
            throw new AssertionError("开关关闭时不该调用裁判");
        });
        assertFalse(judge.judge("文本").evaluated());
    }

    @Test
    @DisplayName("固定安全话术不参与复核（避免热线话术被误判后替换掉）")
    void fixedSafetyScriptsAreSkipped() {
        assertTrue(CrisisGuard.isSafetyScript(CrisisGuard.SCRIPT));
        assertTrue(CrisisGuard.isSafetyScript(CrisisGuard.EXPLICIT_SCRIPT));
        assertFalse(CrisisGuard.isSafetyScript("这是一段普通的模型回答"));
        assertFalse(CrisisGuard.isSafetyScript(null));
        assertNotNull(CrisisGuard.SCRIPT);
    }
    @Test void realSemanticBoundarySharesQuotaButDisabledAndEmptyInputsDoNotQueue() throws Exception {
        var limits=new com.demo.edu.classroom.ai.ClassroomModelAdmissionProperties();limits.setMaxConcurrent(1);limits.setMaxWaiting(0);limits.setMaxWait(java.time.Duration.ofMillis(40));
        try(var admission=new com.demo.edu.classroom.ai.ClassroomModelAdmission(limits);var callers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            var busy=callers.submit(()->admission.execute(java.time.Duration.ofSeconds(2),null,budget->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}));
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
            EduContentSafetyProperties p=props("enforce");p.setCircuitFailThreshold(1);var judge=new EduContentSafetyJudge(p,admission);AtomicInteger calls=new AtomicInteger();
            judge.setLlmCallerForTest(prompt->{calls.incrementAndGet();return llmResponse(true,"");});
            p.setEnabled(false);assertEquals("content-check-disabled",judge.judge("正文").reason());
            p.setEnabled(true);assertEquals("empty-text",judge.judge("").reason());
            assertTrue(judge.judge("正文").reason().startsWith("model-capacity-"));assertEquals(0,calls.get());assertFalse(judge.circuitOpen());
            release.countDown();busy.get(1,java.util.concurrent.TimeUnit.SECONDS);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);while(admission.snapshot().active()>0&&System.nanoTime()<end)Thread.onSpinWait();
            assertTrue(judge.judge("正文").evaluated());assertEquals(1,calls.get());
        }
    }
    @Test void skippedAdmissionReleasesHalfOpenProbeWithoutInventingProviderOutcome() {
        var breaker=new com.demo.router.CircuitBreaker("test-content-admission",true,1,0);
        breaker.recordFailure();assertTrue(breaker.allowRequest());assertEquals(com.demo.router.CircuitBreaker.State.HALF_OPEN,breaker.state());
        breaker.recordNotAttempted();assertTrue(breaker.allowRequest(),"上次没有真正发出探测，不能永久锁死半开状态");
        breaker.recordSuccess();assertEquals(com.demo.router.CircuitBreaker.State.CLOSED,breaker.state());
    }
}
