import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
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
        if (answer instanceof Error) child.stdin.write(JSON.stringify({ type: 'llm.result', id: value.id, error: answer.message }) + '\n');
        else child.stdin.write(JSON.stringify({ type: 'llm.result', id: value.id, text: typeof answer === 'string' ? answer : JSON.stringify(answer) }) + '\n');
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
  assert.deepEqual(response.value[2].quizConfig.questionTypes, ['single', 'multiple']);
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
    [{ type: 'text', content: '试一试。' }],
  ]);
  assert.equal(result.code, 0, result.received.at(-1)?.message);
  const question = result.received.at(-1).value.content.questions[0];
  assert.equal(question.id, `${outline().id}-q1`);
  assert.deepEqual(question.answer, ['A']);
  assert.equal(question.points, 1);
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
