import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { sanitizeProse, restrictInteractiveHtml } from '../dist/content-policy.js';

const worker = fileURLToPath(new URL('../dist/worker.js', import.meta.url));
const input = { courseId: 'course-1', topic: '分数的意义', grade: 4, subject: '数学', sourceText: '把一块饼平均分成四份。', generationContext: '分母表示平均分成的份数。' };
const outline = (type = 'slide') => ({ id: 'course-1-scene-1', order: 0, title: '认识分数', type, description: '把一块饼平均分。', keyPoints: ['分子', '分母'], ...(type === 'interactive' ? { widgetType: 'simulation', widgetOutline: { concept: '分数', keyVariables: ['parts'] } } : {}) });

/** 使用真实子进程验证 Java 所需的逐行协议，无网络、凭据或运行中服务依赖。 */
function runJob(job, answers, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [worker], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
    const received = [];
    let stderr = '', requestCount = 0;
    const timer = setTimeout(() => { child.kill(); reject(new Error('worker test timeout')); }, 20_000);
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
    child.on('error', (error) => { clearTimeout(timer); reject(error); });
    lines.on('line', (line) => {
      let value;
      try { value = JSON.parse(line); } catch { child.kill(); reject(new Error(`stdout is not JSONL: ${line}`)); return; }
      received.push(value);
      if (value.type === 'llm.request') {
        if (options.closeInput) { child.stdin.end(); return; }
        const answer = answers[requestCount++];
        const budget = options.remainingAfterReply?.[requestCount - 1];
        const response = answer instanceof Error ? { type: 'llm.result', id: value.id, error: answer.message }
          : { type: 'llm.result', id: value.id, text: typeof answer === 'string' ? answer : JSON.stringify(answer) };
        if (budget !== undefined) response.remainingMs = budget;
        child.stdin.write(JSON.stringify(response) + '\n');
      }
    });
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, received, stderr }); });
    child.stdin.write(JSON.stringify({ type: 'job', id: 'job-1', input, ...job }) + '\n');
  });
}

test('outline uses source/RAG and stable IDs over the injected model callback', async () => {
  const result = await runJob({ operation: 'outline' }, [{ languageDirective: '中文', outlines: [outline(), outline('interactive'), outline('quiz')] }]);
  assert.equal(result.code, 0);
  const request = result.received.find((item) => item.type === 'llm.request');
  assert.equal(request.messages[0].role, 'system');
  assert.match(request.messages[1].content, /一块饼/);
  assert.match(request.messages[1].content, /分母表示/);
  const response = result.received.at(-1);
  assert.equal(response.type, 'result');
  assert.deepEqual(response.value.map((item) => item.id), ['course-1-scene-1', 'course-1-scene-2', 'course-1-scene-3']);
  assert.deepEqual(response.value.map((item) => item.order), [0, 1, 2]);
  assert.deepEqual(response.value[2].quizConfig.questionTypes, ['single', 'multiple', 'judge', 'fill', 'short_answer']);
});

test('numeric grades 1, 7 and 12 retain primary, middle and high school teaching levels', async () => {
  for (const [grade, label] of [[1, '小学一年级'], [7, '初中一年级'], [12, '高中三年级']]) {
    const result = await runJob({ operation: 'outline', input: { ...input, grade } }, [{ outlines: [outline(), outline('interactive'), outline('quiz')] }]);
    assert.equal(result.code, 0, result.received.at(-1)?.message);
    const request = result.received.find((item) => item.type === 'llm.request');
    assert.ok(request.messages[0].content.includes(label), label);
    assert.ok(request.messages[1].content.includes(label), label);
  }
});

test('invalid grades and incomplete lesson outlines fail explicitly', async () => {
  for (const grade of [0, 13, '7', 1.5]) {
    const result = await runJob({ operation: 'outline', input: { ...input, grade } }, []);
    assert.equal(result.code, 1);
    assert.match(result.received.at(-1).message, /1至12/);
    assert.equal(result.received.some((item) => item.type === 'llm.request'), false);
  }
  const missingPractice = await runJob({ operation: 'outline' }, [{ outlines: [outline(), outline('interactive')] }]);
  assert.equal(missingPractice.code, 1);
  assert.match(missingPractice.received.at(-1).message, /小练习和动手探索/);
  const missingExplore = await runJob({ operation: 'outline' }, [{ outlines: [outline(), outline('quiz')] }]);
  assert.equal(missingExplore.code, 1);
  assert.match(missingExplore.received.at(-1).message, /小练习和动手探索/);
});

test('zero-based outline order becomes one-based SDK context with the right previous narration', async () => {
  const first = { ...outline(), id: 'first', order: 0, title: '第一课' };
  const second = { ...outline(), id: 'second', order: 1, title: '第二课' };
  const third = { ...outline(), id: 'third', order: 2, title: '第三课' };
  const result = await runJob({ operation: 'scene', input: { ...input, outlines: [first, second, third], scenes: [{ id: 'third', order: 2, actions: [{ id: 's3', type: 'speech', text: '不要读取未来页的讲述' }] }, { id: 'first', order: 0, actions: [{ id: 's1', type: 'speech', text: '上一页已经认识了分母' }] }] }, outline: second }, [
    { elements: [{ type: 'text', left: 80, top: 60, width: 840, height: 100, content: '<p>分子</p>' }] },
    [{ type: 'text', content: '再来看看分子。' }],
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  assert.equal(result.received.at(-1).value.order, 1);
  const actionsRequest = result.received.filter((item) => item.type === 'llm.request')[1];
  assert.match(actionsRequest.messages[1].content, /Page 2 of 3/);
  assert.match(actionsRequest.messages[1].content, /上一页已经认识了分母/);
  assert.doesNotMatch(actionsRequest.messages[1].content, /不要读取未来页/);
});

test('slide generation assembles DSL and strips active prose and unknown actions', async () => {
  const result = await runJob({ operation: 'scene', outline: outline() }, [
    { elements: [{ id: 'text-1', type: 'text', left: 80, top: 60, width: 840, height: 100, content: '<p onclick="alert(1)">四分之一<script>alert(1)</script></p>' }] },
    [{ type: 'text', content: '将整体平均分成四份。' }, { type: 'action', name: 'wb_open', params: {} }, { type: 'action', name: 'discussion', params: { topic: '两份是多少？', agentId: 'unknown' } }],
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const scene = result.received.at(-1).value;
  assert.equal(scene.id, outline().id);
  assert.equal(scene.stageId, input.courseId);
  assert.equal(scene.order, 0);
  for (const call of result.received.filter((item) => item.type === 'llm.request')) {
    assert.match(call.messages[1].content, /把一块饼平均分成四份/);
    assert.match(call.messages[1].content, /分母表示平均分成的份数/);
  }
  assert.match(scene.content.canvas.elements[0].content, /四分之一/);
  assert.doesNotMatch(scene.content.canvas.elements[0].content, /script|onclick/);
  assert.deepEqual(scene.actions.map((item) => item.type), ['speech', 'wb_open', 'discussion']);
  assert.equal(scene.actions[2].agentId, 'peer');
});

test('quiz preserves server grading keys and binds question IDs to the scene', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [
    [{ type: 'single', question: '四份中的一份是？', options: [{ value: 'A', label: '1/4' }, { value: 'B', label: '1/2' }], answer: ['A'], analysis: '平均分成四份，取一份。', points: 5 }],
    [{ id: 'course-1-scene-1-q1', answer: 'A', confidence: 'high', reason: '四份取其一' }],
    [{ type: 'text', content: '试一试。' }],
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const question = result.received.at(-1).value.content.questions[0];
  assert.equal(question.id, `${outline().id}-q1`);
  assert.deepEqual(question.answer, ['A']);
  assert.equal(question.points, 1);
  assert.equal(question.review.verdict, 'PASS');
});

test('host quiz supports all five types and retains semantic grading guidance', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [
    [{ type: 'single', question: '哪个是1/2？', options: [{ value: 'A', label: '2/4' }, { value: 'B', label: '1/4' }], answer: ['A'], analysis: '整体分成四份取两份就是二分之一。' },
      { type: 'multiple', question: '选偶数', options: [{ value: 'A', label: '2' }, { value: 'B', label: '4' }], answer: ['A', 'B'], analysis: '2 和 4 都能被 2 整除。' },
      { type: 'judge', question: '同样大的整体才方便比较面积？', answer: true, analysis: '基准不同时无法直接比较份额。' },
      { type: 'fill', question: '8乘3是多少？', answer: 24, analysis: '8 个 3 相加等于 24。' },
      { type: 'short_answer', question: '为何要统一整体？', answer: '比较基准一致', gradingPoints: ['同样大小的整体', '相同基准'], analysis: '比较需要共同基准' }],
    [{ id: 'course-1-scene-1-q1', answer: 'A', confidence: 'high', reason: '四份取两份' },
      { id: 'course-1-scene-1-q2', answer: ['A', 'B'], confidence: 'high', reason: '两者皆为偶数' },
      { id: 'course-1-scene-1-q3', answer: '对', confidence: 'high', reason: '基准必须一致' },
      { id: 'course-1-scene-1-q4', answer: '24', confidence: 'high', reason: '8×3=24' }],
    [{ type: 'text', content: '先独立完成，再检查思路。' }],
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const questions = result.received.at(-1).value.content.questions;
  assert.deepEqual(questions.map(q => q.type), ['single', 'multiple', 'judge', 'fill', 'short_answer']);
  assert.equal(questions[2].answer, '对'); assert.equal(questions[3].answer, 24);
  assert.deepEqual(questions[4].gradingPoints, ['同样大小的整体', '相同基准']);
  // 简答题无法用比对答案复核，必须如实记 UNVERIFIED，不能冒充 PASS。
  assert.deepEqual(questions.map(q => q.review.verdict), ['PASS', 'PASS', 'PASS', 'PASS', 'UNVERIFIED']);
});

test('direction profile ignores academic subject for wellbeing and exploration', async () => {
  const result = await runJob({ operation: 'outline', input: { ...input, direction: 'exploration', subject: '数学' } }, [{ outlines: [outline(), outline('interactive'), outline('quiz')] }]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const request = result.received.find(item => item.type === 'llm.request');
  assert.match(request.messages[0].content, /学习方向：实践探索/);
  assert.doesNotMatch(request.messages[0].content, /学科：数学/);
});

test('interactive preserves working controls while blocking external resources', async () => {
  const html = '<!DOCTYPE html><html><head><script src="https://example.com/x.js"></script></head><body><input id="parts" type="range"><output id="value"></output><script>document.getElementById("parts").oninput=function(){document.getElementById("value").textContent=this.value}</script></body></html>';
  const result = await runJob({ operation: 'scene', outline: outline('interactive') }, [html, [{ type: 'text', content: '拖动滑块，观察分母变化。' }]]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const out = result.received.at(-1).value.content.html;
  assert.match(out, /id="parts"/);
  assert.match(out, /this.value/);
  assert.match(out, /connect-src 'none'/);
  assert.doesNotMatch(out, /<script[^>]+src=|https:\/\/example.com|cdn.jsdelivr/);
});

test('invalid model output and model errors finish as errors, not fabricated lessons', async () => {
  const malformed = await runJob({ operation: 'scene', outline: outline('quiz') }, ['not a quiz']);
  assert.equal(malformed.code, 1);
  assert.equal(malformed.received.at(-1).type, 'error');
  const unavailable = await runJob({ operation: 'outline' }, [new Error('model unavailable')]);
  assert.equal(unavailable.code, 1);
  assert.match(unavailable.received.at(-1).message, /model unavailable/);
});

test('worker exits when its parent closes stdin while a model response is pending', async () => {
  const result = await runJob({ operation: 'outline' }, [], { closeInput: true });
  assert.equal(result.code, 0);
  assert.equal(result.received.filter((item) => item.type === 'result').length, 0);
});

test('rich text policy and interactive CSP handle malformed markup independently', () => {
  const prose = sanitizeProse('<p style="color:red" onclick="alert(1)">正文<img src=x onerror=alert(1)><svg><foreignObject><script>x</script></foreignObject></svg><a href="javascript:x">链接</a></p>');
  assert.match(prose, /正文/);
  assert.doesNotMatch(prose, /onerror|onclick|javascript|foreignObject|script/);
  const html = restrictInteractiveHtml('<html><head><base href="https://bad/"></head><body><form><input></form><iframe src="https://bad/"></iframe><p>\\(x^2\\)</p></body></html>');
  assert.match(html, /font-src data:/);
  assert.match(html, /^<!DOCTYPE html>/);
  assert.match(html, /data:font\/woff2;base64/);
  assert.doesNotMatch(html, /<iframe|<form|<base/);
  assert.ok(Buffer.byteLength(html) < 1_000_000);
  const customMath = restrictInteractiveHtml('<html><head></head><body><input id="x"><script>document.getElementById("x").oninput=function(){renderMathInElement(document.body);}</script><p>\\(x\\)</p></body></html>');
  assert.match(customMath, /oninput=function/);
});

// ---------------------------------------------------------------------------
// 习题两级审核：以下用例都跑真实子进程，验证 Java 实际会看到的 JSONL 流量。
// ---------------------------------------------------------------------------

const reviewFixtures = JSON.parse(readFileSync(fileURLToPath(new URL('./fixtures/quiz-review-cases.json', import.meta.url)), 'utf8'));
/** 解析干净的错题：L1 放行，只有盲解能抓。 */
const wrongKeyQuiz = reviewFixtures.reported.volumeCleanButWrongKey;
/** 重生成后的替换题：题干与选项都换过，标答与 480 不再冲突。 */
const repairedQuiz = { type: 'single', question: '长方体的长是10厘米、宽是4厘米、高是2厘米，体积是多少立方厘米？', options: [{ label: '80', value: 'A' }, { label: '16', value: 'B' }, { label: '28', value: 'C' }], answer: ['A'], analysis: '10 × 4 × 2 = 80。' };
const disagree = [{ id: 'course-1-scene-1-q1', answer: 'NONE', confidence: 'high', reason: '20×8×3=480，选项中没有 480' }];
const agreeRepaired = [{ id: 'course-1-scene-1-q1', answer: 'A', confidence: 'high', reason: '10×4×2=80' }];
const trailingActions = [{ type: 'text', content: '先独立完成，再检查思路。' }];

test('a disputed answer key is regenerated and the corrected question is what gets stored', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [[wrongKeyQuiz], disagree, repairedQuiz, agreeRepaired, trailingActions]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const scene = result.received.at(-1).value;
  assert.deepEqual(scene.content.questions[0].answer, ['A']);
  assert.equal(scene.content.questions[0].review.verdict, 'REGENERATED');
  // 修好的题留在计分数组里，不该被降级。thinking 只在有降级题时出现，空时不写该字段。
  assert.equal((scene.content.thinking ?? []).length, 0);
});

test('the review request declares its purpose and carries neither the key nor the explanation', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [[wrongKeyQuiz], disagree, repairedQuiz, agreeRepaired, trailingActions]);
  const requests = result.received.filter((item) => item.type === 'llm.request');
  const review = requests.find((item) => item.purpose === 'quiz-review');
  assert.ok(review, '缺少带 purpose 的复核请求：Java 无法据此路由到 0 温度模型');
  // 复核请求只允许带题干与选项；带标答或解析会把复核变成批量盖章。
  const marker = '只给题干和选项。\n';
  const content = review.messages[1].content;
  const payload = JSON.parse(content.slice(content.indexOf(marker) + marker.length).split('\n\n')[0]);
  assert.equal(payload.length, 1);
  for (const item of payload) {
    for (const key of Object.keys(item)) assert.ok(['id', 'type', 'question', 'options'].includes(key), `复核请求里出现了违规字段 ${key}`);
  }
  // 解析正文绝不出现在复核请求里（它可能直接写出错误结论）。
  assert.equal(content.includes('长方体体积由长'), false);
  // 重生成走生成模型，不占用复核用途。
  assert.ok(requests.some((item) => item.purpose === 'quiz-regenerate'));
  assert.ok(requests.filter((item) => item.purpose === 'quiz-review').length <= 2);
});

test('a failed review call degrades to UNVERIFIED instead of failing the course', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [[wrongKeyQuiz], new Error('model unavailable'), trailingActions]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const scene = result.received.at(-1).value;
  // 「没查出来」不等于「查出错」：复核失败时题目保留计分，不因一次模型抖动清空练习。
  assert.equal(scene.content.questions.length, 1);
  assert.equal(scene.content.questions[0].review.verdict, 'UNVERIFIED');
  assert.equal(scene.content.questions[0].review.method, 'none');
});

test('garbage review output is UNVERIFIED, never PASS', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [[wrongKeyQuiz], '抱歉，我无法完成这道题。', trailingActions]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  assert.equal(result.received.at(-1).value.content.questions[0].review.verdict, 'UNVERIFIED');
});

test('an unrepairable question becomes a non-scored thinking item without leaking the answer', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [[wrongKeyQuiz], disagree, '不是JSON', '还是不是JSON', trailingActions]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const content = result.received.at(-1).value.content;
  // 降级必须移出计分数组：留在里面会强制学生作答，还会用错的标答判分。
  assert.equal(content.questions.length, 0);
  assert.equal(content.thinking.length, 1);
  assert.equal(content.thinking[0].review.verdict, 'RISKY_THINK');
  assert.match(content.thinking[0].question, /长方体/);
  // 思考题只有题干：带选项或标答就退化回计分题了。
  assert.equal('options' in content.thinking[0], false);
  assert.equal('answer' in content.thinking[0], false);
  // 复核理由里含模型算出的 480，写进思考题等于泄题。
  assert.equal(JSON.stringify(content.thinking[0]).includes('480'), false);
});

test('the read-only review operation reports a disputed key without changing any content', async () => {
  const job = { operation: 'review', input: { courseId: 'course-1', questions: [wrongKeyQuiz] } };
  // 补审以 courseId 作为题目前缀，因此这里的题目 id 是 course-1-q1（不是场景测试里的 course-1-scene-1-q1）。
  const result = await runJob(job, [[{ id: 'course-1-q1', answer: 'NONE', confidence: 'high', reason: '20×8×3=480，选项中没有 480' }]]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const value = result.received.at(-1).value;
  assert.equal(value.reviewed, 1);
  assert.equal(value.ruleDropped, 0);
  assert.equal(value.callFailed, false);
  assert.equal(value.findings.length, 1);
  const finding = value.findings[0];
  assert.equal(finding.stage, 'model');
  assert.equal(finding.outcome, 'disagree');
  // 报告必须给出复核自己算出的答案，但原标答保持原样 —— 只读工具不写任何东西。
  assert.equal(finding.suggestedAnswer, 'NONE');
  assert.deepEqual(finding.currentAnswer, ['B']);
  assert.equal(JSON.stringify(value).includes('"thinking"'), false);
  assert.equal(JSON.stringify(value).includes('"questions"'), false);
  // 补审走的仍然是 0 温度复核模型，不是生成模型。
  const request = result.received.find((item) => item.type === 'llm.request');
  assert.equal(request.purpose, 'quiz-review');
});

test('the review operation separates rule hits from an agreeing re-solve', async () => {
  const leaky = reviewFixtures.reported.volumeWithCotLeak;
  const clean = reviewFixtures.cleanNegatives[3];
  const job = { operation: 'review', input: { courseId: 'course-1', questions: [leaky, clean] } };
  const result = await runJob(job, [[{ id: 'course-1-q2', answer: '24', confidence: 'high', reason: '8×3' }]]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const value = result.received.at(-1).value;
  assert.equal(value.ruleDropped, 1);
  assert.equal(value.reviewed, 1);
  const [rule, model] = value.findings;
  assert.equal(rule.stage, 'rule');
  assert.match(rule.outcome, /analysis-meta/);
  assert.equal(model.stage, 'model');
  assert.equal(model.outcome, 'agree', `复核未判定：${model.detail} / suggested=${model.suggestedAnswer}`);
});

test('the review operation stays honest when the re-solve call fails', async () => {
  const job = { operation: 'review', input: { courseId: 'course-1', questions: [wrongKeyQuiz] } };
  const result = await runJob(job, [new Error('model unavailable')]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const value = result.received.at(-1).value;
  // 复核失败必须如实报 callFailed，不能把"没查出来"当成通过。
  assert.equal(value.callFailed, true);
  assert.equal(value.findings[0].outcome, 'unjudged');
  assert.match(value.findings[0].detail, /复核调用失败/);
});

test('the repair operation proposes an in-place replacement when the right answer is not among the options', async () => {
  const job = { operation: 'repair', input: { courseId: 'course-1', questions: [wrongKeyQuiz] } };
  const result = await runJob(job, [
    [{ id: 'course-1-q1', answer: 'NONE', confidence: 'high', reason: '20×8×3=480，选项中没有 480' }],
    repairedQuiz,
    [{ id: 'course-1-q1', answer: 'A', confidence: 'high', reason: '10×4×2=80' }],
    { id: 'course-1-q1', status: 'consistent', reason: '完整解析与80及选项A一致' },
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const change = result.received.at(-1).value.changes[0];
  // 480 不在选项里 → 改标答救不了，只能就地重出。
  assert.equal(change.action, 'regenerate');
  assert.equal(change.before.question, wrongKeyQuiz.question);
  assert.deepEqual(change.before.answer, ['B']);
  assert.match(change.after.question, /10厘米/);
  assert.deepEqual(change.after.answer, ['A']);
  assert.doesNotMatch(change.after.analysis, /等等|重新计算|修正选项/);
});

test('the repair operation also fixes a question whose explanation leaked the model reasoning', async () => {
  const job = { operation: 'repair', input: { courseId: 'course-1', questions: [reviewFixtures.reported.volumeWithCotLeak] } };
  const result = await runJob(job, [
    [{ id: 'course-1-q1', answer: 'NONE', confidence: 'high', reason: '20×8×3=480，选项中没有 480' }],
    repairedQuiz,
    [{ id: 'course-1-q1', answer: 'A', confidence: 'high', reason: '10×4×2=80' }],
    { id: 'course-1-q1', status: 'consistent', reason: '完整解析与80及选项A一致' },
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const change = result.received.at(-1).value.changes[0];
  assert.equal(change.action, 'regenerate');
  // 原始解析确实泄漏了推敲过程（这正是 L1 命中它的原因），改完必须干净。
  assert.match(change.before.analysis, /等等/);
  assert.doesNotMatch(change.after.analysis, /等等|重新计算|修正选项/);
});

test('the repair operation replaces and checks the explanation even when the right key already exists', async () => {
  const wrongKeyChoice = { type: 'single', question: '3 的平方是多少？',
    options: [{ label: '6', value: 'A' }, { label: '9', value: 'B' }, { label: '12', value: 'C' }],
    answer: ['A'], analysis: '平方就是自己乘自己。' };
  const job = { operation: 'repair', input: { courseId: 'course-1', questions: [wrongKeyChoice] } };
  const result = await runJob(job, [
    [{ id: 'course-1-q1', answer: 'B', confidence: 'high', reason: '3×3=9' }],
    { ...wrongKeyChoice, answer: ['B'], analysis: '平方是自己乘自己，3×3=9，所以选择B。' },
    [{ id: 'course-1-q1', answer: 'B', confidence: 'high', reason: '3×3=9' }],
    { id: 'course-1-q1', status: 'consistent', reason: '解析和标答均对应9与B' },
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const change = result.received.at(-1).value.changes[0];
  assert.equal(change.action, 'regenerate');
  assert.deepEqual(change.after.answer, ['B']);
  // 同题型完整重出可以保留有效题面，但解析也必须明确给出新标答的理由。
  assert.equal(change.after.question, wrongKeyChoice.question);
  assert.deepEqual(change.after.options, wrongKeyChoice.options);
  assert.match(change.after.analysis, /3×3=9/);
  assert.equal(result.received.filter((item) => item.type === 'llm.request').length, 4);
});

test('the repair operation leaves unflagged questions completely alone', async () => {
  const job = { operation: 'repair', input: { courseId: 'course-1', questions: [reviewFixtures.cleanNegatives[3]] } };
  const result = await runJob(job, [[{ id: 'course-1-q1', answer: '24', confidence: 'high', reason: '8×3' }]]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  assert.deepEqual(result.received.at(-1).value.changes, []);
});

test('a repair that cannot be verified is handed to manual work instead of being written', async () => {
  const job = { operation: 'repair', input: { courseId: 'course-1', questions: [wrongKeyQuiz] } };
  const result = await runJob(job, [
    [{ id: 'course-1-q1', answer: 'NONE', confidence: 'high', reason: '480 不在选项中' }],
    repairedQuiz,
    [{ id: 'course-1-q1', answer: 'B', confidence: 'high', reason: '复核仍不同意' }],
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const change = result.received.at(-1).value.changes[0];
  // 重出后复核不过 → 绝不当作修好，交给人工。
  assert.equal(change.action, 'none');
  assert.equal(change.after, undefined);
  assert.match(change.reason, /重出后复核未通过/);
});

test('a quiz scene keeps its model request count bounded', async () => {
  const result = await runJob({ operation: 'scene', outline: outline('quiz') }, [[wrongKeyQuiz], disagree, repairedQuiz, agreeRepaired, trailingActions]);
  const requests = result.received.filter((item) => item.type === 'llm.request');
  // 出题 1 + 复核 1 + 重生成 1 + 重生成后复核 1 + 讲授动作 1 = 5。
  assert.ok(requests.length <= 6, `实际发出 ${requests.length} 次模型请求`);
});

test('worker propagates a bounded call budget and only shortens the job on parent remaining-time updates', async () => {
  const result = await runJob({ operation: 'scene', remainingMs: 420_000, outline: outline('quiz') },
    [[wrongKeyQuiz], disagree], { remainingAfterReply: [900_000, 10_000] });
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const calls = result.received.filter(item => item.type === 'llm.request');
  assert.equal(calls.length, 2);
  for (const call of calls) assert.ok(Number.isInteger(call.timeoutMs) && call.timeoutMs > 0 && call.timeoutMs <= 180_000);
  const scene = result.received.at(-1).value;
  assert.equal(scene.content.questions.length, 0);
  assert.equal(scene.content.thinking.length, 1);
  assert.match(scene.content.thinking[0].review.reason, /预算/);
  assert.ok(scene.actions.some(action => action.type === 'speech'));
});

test('an exhausted parent job budget cannot trigger a model request', async () => {
  const result = await runJob({ operation: 'outline', remainingMs: 0 }, []);
  assert.equal(result.code, 1);
  assert.equal(result.received.filter(item => item.type === 'llm.request').length, 0);
  assert.match(result.received.at(-1).message, /预算|时限/);
});
