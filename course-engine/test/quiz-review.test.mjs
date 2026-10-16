import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  detectQualityIssues, buildReSolvePrompt, parseReSolve, compareReSolve,
  normalizeForCompare, buildRegeneratePrompt, toThinkingItem, PROMPT_VERSION,
} from '../dist/quiz-review.js';
import { normalizeQuizQuestions } from '../dist/engine.js';

const fixtures = JSON.parse(readFileSync(fileURLToPath(new URL('./fixtures/quiz-review-cases.json', import.meta.url)), 'utf8'));
const SCENE = 'scene-1';
/** 先过结构校验，拿到与生产一致的 HostQuizQuestion（id 由宿主分配）。 */
const norm = (question) => normalizeQuizQuestions([question], SCENE)[0];
const codesOf = (question) => detectQualityIssues([norm(question)]).map((issue) => issue.code);

test('propagates the reported CoT-leaking volume question only through the explanation rule', () => {
  // 结构校验放行：选项合法、互异，标答 B 确实在选项里 —— 这正是原缺陷能溜到学生面前的原因。
  const question = norm(fixtures.reported.volumeWithCotLeak);
  assert.deepEqual(question.answer, ['B']);
  // L1 拦住它靠的是解析里泄漏的推敲过程，不是答案本身。
  assert.ok(codesOf(fixtures.reported.volumeWithCotLeak).includes('analysis-meta'));
});

test('a clean explanation hides a wrong answer key from L1 — only the blind re-solve catches it', () => {
  // 这条是能力边界的诚实记录：L1 对"答案算错了"完全无能为力。
  assert.deepEqual(detectQualityIssues([norm(fixtures.reported.volumeCleanButWrongKey)]), []);
  // 独立求解算出 480，而 480 不在选项里，所以如实回 NONE，而不是硬挑一个选项。
  const outcome = compareReSolve(norm(fixtures.reported.volumeCleanButWrongKey),
    { answer: 'NONE', confidence: 'high', reason: '20×8×3=480，选项中没有 480' });
  assert.equal(outcome.status, 'disagree');
  assert.equal(outcome.issue.code, 'recheck-disagrees');
});

test('clean explanations are never flagged as leaked reasoning', () => {
  // 误报校准守卫：L1 命中即丢题，任何一条误报都会删掉一道正确的题。
  for (const question of fixtures.cleanNegatives) {
    const codes = codesOf(question);
    assert.equal(codes.includes('analysis-meta'), false, `${question._note} → ${codes.join(',')}`);
  }
});

test('requires a student-facing explanation but accepts grading points for short answers', () => {
  assert.ok(codesOf({ type: 'single', question: '哪个是1/2？', options: [{ label: '2/4', value: 'A' }, { label: '1/4', value: 'B' }], answer: ['A'] }).includes('analysis-empty'));
  // 简答的评分要点会被折进 explanation，因此等同有解析，不能丢题。
  assert.equal(codesOf({ type: 'short_answer', question: '为什么？', answer: '因为基准一致。', gradingPoints: ['指出基准'], analysis: '' }).includes('analysis-empty'), false);
});

test('choice keys must be a single uppercase letter', () => {
  for (const value of ['a', '1', 'AB', '甲']) {
    assert.ok(codesOf({ type: 'single', question: '题目', options: [{ label: '一', value }, { label: '二', value: 'B' }], answer: [value], analysis: '解析。' }).includes('option-value-not-letter'), value);
  }
});

test('duplicate option labels are rejected even when the keys differ', () => {
  assert.ok(codesOf({ type: 'single', question: '题目', options: [{ label: '相同', value: 'A' }, { label: '相同', value: 'B' }], answer: ['A'], analysis: '解析。' }).includes('option-label-duplicate'));
});

test('the blind re-solve prompt carries neither the answer key nor the explanation', () => {
  // 全套里最关键的一条：把标答或解析递进复核，会把唯一的检测手段变成批量盖章。
  const prompt = buildReSolvePrompt([norm(fixtures.reported.volumeCleanButWrongKey), norm(fixtures.cleanNegatives[5])]);
  assert.ok(prompt);
  assert.equal(prompt.reviewed.length, 2);
  for (const item of prompt.payload) {
    for (const key of Object.keys(item)) assert.ok(['id', 'type', 'question', 'options'].includes(key), `违规字段：${key}`);
  }
  // 连选项正文里也不该出现"标答指向哪个"的信息，payload 里不允许有 answer。
  assert.equal(JSON.stringify(prompt.payload).includes('"answer"'), false);
  assert.equal(JSON.stringify(prompt.payload).includes('"analysis"'), false);
  // 解析正文本身也不得出现（避免以任何形式泄漏结论）。
  assert.equal(prompt.user.includes('长方体体积由长'), false);
});

test('non-comparable types and unparseable responses stay unjudged, never pass', () => {
  const shortAnswer = norm(fixtures.cleanNegatives[4]);
  // 简答题恒不支持比对：用文本比对会持续误报并触发对正确题目的重生成。
  assert.equal(normalizeForCompare('short_answer', '任意文本'), null);
  assert.equal(compareReSolve(shortAnswer, { answer: '完全不同的表述', confidence: 'high', reason: '' }).status, 'unjudged');
  const fill = norm(fixtures.cleanNegatives[3]);
  // 低置信度不足以判定，不能当成通过。
  assert.equal(compareReSolve(fill, { answer: '25', confidence: 'low', reason: '' }).status, 'unjudged');
  // 没有任何结论时同样是 unjudged。
  assert.equal(compareReSolve(fill, undefined).status, 'unjudged');
  // 数值填空：标答是数字 24，复核回字符串 '24'，必须能对上。
  assert.equal(compareReSolve(fill, { answer: '24', confidence: 'high', reason: '' }).status, 'agree');
  assert.equal(compareReSolve(fill, { answer: '25', confidence: 'high', reason: '' }).status, 'disagree');
});

test('answer normalization accepts the shapes actually produced on each side', () => {
  // 选择题标答是数组、复核回裸字母
  assert.equal(normalizeForCompare('single', ['A']), 'A');
  assert.equal(normalizeForCompare('single', 'a'), 'A');
  // 多选两边顺序不同也要等价
  assert.equal(normalizeForCompare('multiple', ['C', 'A']), normalizeForCompare('multiple', ['A', 'C']));
  // 判断题布尔与中文都要认
  assert.equal(normalizeForCompare('judge', true), '对');
  assert.equal(normalizeForCompare('judge', '对'), '对');
  // 文本填空不比对，避免把格式差异当成答案错
  assert.equal(normalizeForCompare('fill', '二十四'), null);
});

test('re-solve parsing is fail-safe and ignores unknown ids', () => {
  assert.equal(parseReSolve(null, []).size, 0);
  assert.equal(parseReSolve('抱歉，我无法完成。', []).size, 0);
  const questions = [norm(fixtures.cleanNegatives[3])];
  const parsed = parseReSolve('```json\n[{"id":"无关题目","answer":"1","confidence":"high","reason":""},{"id":"scene-1-q1","answer":"24","confidence":"high","reason":"8×3"}]\n```', questions);
  assert.deepEqual([...parsed.keys()], ['scene-1-q1']);
});

test('the regeneration prompt asks for the correct result to appear in the options', () => {
  const question = norm(fixtures.reported.volumeCleanButWrongKey);
  const prompt = buildRegeneratePrompt(question, { index: 0, code: 'recheck-disagrees', detail: '独立求解得到 480，标答为 B', repairable: true });
  assert.match(prompt.user, /让正确结果出现在选项中/);
  assert.match(prompt.user, /不含「等等\/让我重新算\/修正选项」这类字样/);
  assert.ok(PROMPT_VERSION.startsWith('quiz-review-'));
});

test('a downgraded thinking item keeps only the stem', () => {
  const question = norm(fixtures.reported.volumeCleanButWrongKey);
  assert.deepEqual(Object.keys(toThinkingItem(question)).sort(), ['id', 'question']);
  // 复核理由里含模型算出的答案，绝不能写进思考题。
  assert.equal(JSON.stringify(toThinkingItem(question)).includes('480'), false);
});
