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

test('re-solve parsing rejects the entire response when an unknown id is present', () => {
  assert.equal(parseReSolve(null, []).size, 0);
  assert.equal(parseReSolve('抱歉，我无法完成。', []).size, 0);
  const questions = [norm(fixtures.cleanNegatives[3])];
  const parsed = parseReSolve('```json\n[{"id":"无关题目","answer":"1","confidence":"high","reason":""},{"id":"scene-1-q1","answer":"24","confidence":"high","reason":"8×3"}]\n```', questions);
  assert.equal(parsed.size, 0);
});

test('review sends complete stems and option labels or skips the whole question', () => {
  const base = norm(fixtures.cleanNegatives[0]);
  const tooLongOption = { ...base, id: 'long-option', options: [{ value: 'A', label: '甲'.repeat(201) }, { value: 'B', label: '乙' }] };
  const tooLongStem = { ...base, id: 'long-stem', question: '甲'.repeat(801) };
  const exactLimit = { ...base, id: 'limit', question: '甲'.repeat(800), options: [{ value: 'A', label: '乙'.repeat(200) }, { value: 'B', label: '丙' }] };
  const prompt = buildReSolvePrompt([tooLongOption, tooLongStem, exactLimit]);
  assert.deepEqual(prompt.reviewed, ['limit']);
  assert.deepEqual(prompt.skipped, ['long-option', 'long-stem']);
  assert.equal(prompt.payload[0].question, exactLimit.question);
  assert.deepEqual(prompt.payload[0].options, exactLimit.options);
  assert.equal(buildReSolvePrompt([tooLongOption, tooLongStem]), null);
});

test('single-call review cap reports every omitted id and keeps its complete prompt within budget', () => {
  const base = norm(fixtures.cleanNegatives[0]);
  const questions = Array.from({ length: 7 }, (_, i) => ({ ...base, id: `q-${i}` }));
  const prompt = buildReSolvePrompt(questions);
  assert.deepEqual(prompt.reviewed, questions.slice(0, 5).map(q => q.id));
  assert.deepEqual(prompt.skipped, ['q-5', 'q-6']);
  const heavy = questions.slice(0, 5).map(q => ({ ...q, question: '题'.repeat(800), options: Array.from({ length: 8 }, (_, i) => ({ value: String.fromCharCode(65 + i), label: String(i) + '字'.repeat(199) })) }));
  const bounded = buildReSolvePrompt(heavy);
  assert.ok(bounded.user.length <= 8000);
  assert.equal(bounded.reviewed.length + bounded.skipped.length, 5);
});

test('partial, repeated, conflicting, malformed or wrapped re-solve batches fail closed', () => {
  const questions = [
    { ...norm(fixtures.cleanNegatives[0]), id: 'first' },
    { ...norm(fixtures.cleanNegatives[3]), id: 'second' },
  ];
  const first = { id: 'first', answer: 'B', confidence: 'high', reason: '约分后相等' };
  const second = { id: 'second', answer: '24', confidence: 'high', reason: '8乘3' };
  for (const batch of [
    [first], [first, first], [first, { ...first, answer: 'A' }],
    [first, second, { ...first, id: 'unknown' }], [first, null], [first, []],
    [first, { ...second, answer: { value: '24' } }],
    [first, { ...second, confidence: 'certain' }],
    [first, { id: 'second', answer: '24', confidence: 'high' }],
    [first, { ...second, reasoning: '隐藏的额外字段' }],
  ]) assert.equal(parseReSolve(JSON.stringify(batch), questions).size, 0, JSON.stringify(batch));
  const valid = JSON.stringify([first, second]);
  assert.equal(parseReSolve(`前缀说明 ${valid}`, questions).size, 0);
  assert.equal(parseReSolve(JSON.stringify({ results: [first, second] }), questions).size, 0);
  assert.equal(parseReSolve(`\x60\x60\x60json\n${valid}\n\x60\x60\x60`, questions).size, 2);
  assert.equal(parseReSolve(valid, [questions[0], questions[0]]).size, 0);
});

test('valid re-solve shapes preserve single, multiple, judge and numeric fill answers', () => {
  const questions = [
    { ...norm(fixtures.cleanNegatives[0]), id: 'single' },
    { ...norm(fixtures.cleanNegatives[5]), id: 'multiple' },
    { ...norm({ type: 'judge', question: '2是偶数。', answer: '对', analysis: '能被2整除。' }), id: 'judge' },
    { ...norm(fixtures.cleanNegatives[3]), id: 'fill' },
  ];
  const entries = [
    { id: 'single', answer: ['B'], confidence: 'high', reason: '约分' },
    { id: 'multiple', answer: ['C', 'A'], confidence: 'high', reason: '被2整除' },
    { id: 'judge', answer: true, confidence: 'high', reason: '被2整除' },
    { id: 'fill', answer: 24, confidence: 'high', reason: '乘法' },
  ];
  const parsed = parseReSolve(JSON.stringify(entries), questions);
  assert.equal(parsed.size, 4);
  for (const question of questions) assert.equal(compareReSolve(question, parsed.get(question.id)).status, 'agree', question.type);
  assert.equal(compareReSolve(questions[0], { answer: 'B', confidence: 'unknown', reason: '' }).status, 'unjudged');
});

test('invalid answer arrays cannot be filtered or collapsed into a passing answer', () => {
  const question = { ...norm(fixtures.cleanNegatives[5]), id: 'multiple' };
  for (const answer of [['A', null, 'C'], ['A', '', 'C'], ['A', 'A', 'C'], ['A', 'C', 'Z'], 'AC']) {
    const raw = JSON.stringify([{ id: question.id, answer, confidence: 'high', reason: '' }]);
    assert.equal(parseReSolve(raw, [question]).size, 0, JSON.stringify(answer));
  }
});

test('NONE cannot reject short answers or non-comparable text fills', () => {
  const shortAnswer = norm(fixtures.cleanNegatives[4]);
  const textFill = norm({ type: 'fill', question: '我国首都是？', answer: '北京', analysis: '我国首都是北京。' });
  for (const question of [shortAnswer, textFill]) {
    const outcome = compareReSolve(question, { answer: 'NONE', confidence: 'high', reason: '无法确定' });
    assert.equal(outcome.status, 'unjudged');
    assert.equal(outcome.issue, undefined);
  }
});

test('decimal comparison preserves exact large integers and fine-grained decimals', () => {
  assert.notEqual(normalizeForCompare('fill', '9007199254740992'), normalizeForCompare('fill', '9007199254740993'));
  assert.notEqual(normalizeForCompare('fill', '0.10000000000000000001'), normalizeForCompare('fill', '0.10000000000000000002'));
  assert.equal(normalizeForCompare('fill', '００２４．０００'), '24');
  assert.equal(normalizeForCompare('fill', '－０００．００'), '0');
  assert.equal(normalizeForCompare('fill', '-000.0100'), '-0.01');
  assert.equal(normalizeForCompare('fill', '9'.repeat(400)), '9'.repeat(400));
  assert.equal(normalizeForCompare('fill', Number.MAX_SAFE_INTEGER + 1), null);
  assert.equal(normalizeForCompare('fill', Infinity), null);
  const question = { ...norm(fixtures.cleanNegatives[3]), answer: '9007199254740992' };
  assert.equal(compareReSolve(question, { answer: '9007199254740993', confidence: 'high', reason: '' }).status, 'disagree');
});

test('numeric JSON answer tokens retain their decimal precision before comparison', () => {
  for (const [key, token] of [
    ['9007199254740992', '9007199254740993'],
    ['0.1', '0.10000000000000000001'],
  ]) {
    const question = { ...norm(fixtures.cleanNegatives[3]), answer: key };
    const raw = `[{"id":"${question.id}","answer":${token},"confidence":"high","reason":"数值计算"}]`;
    const parsed = parseReSolve(raw, [question]);
    assert.equal(parsed.get(question.id)?.answer, token);
    assert.equal(compareReSolve(question, parsed.get(question.id)).status, 'disagree');
  }
});

test('regeneration keeps each original type and demands a complete coherent replacement', () => {
  const issue = { index: 0, code: 'analysis-empty', detail: '解析缺失', repairable: true };
  for (const type of ['single', 'multiple', 'judge', 'fill', 'short_answer']) {
    const question = { ...norm(fixtures.cleanNegatives[4]), type };
    const prompt = buildRegeneratePrompt(question, issue);
    assert.ok(prompt.user.includes(`保持原题型 ${type}`));
    assert.match(prompt.user, /题干、选项、答案、解析与评分要点必须相互一致/);
    assert.match(prompt.user, /完整重出/);
    assert.match(prompt.user, /禁止只修改标答/);
    assert.ok(prompt.user.includes('gradingPoints'));
  }
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
