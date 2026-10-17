import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditQuiz, generateScene, planQuizRepair } from '../dist/engine.js';

const outline = { id: 'scene-1', type: 'quiz', order: 0, title: '体积练习', description: '先说说长方体体积的计算思路。', keyPoints: ['体积'], quizConfig: { questionCount: 1, questionTypes: ['single'], difficulty: 'easy' } };
const input = { courseId: 'course-1', topic: '体积', grade: 5, subject: '数学', outlines: [outline], sourceText: 'PRIVATE_SOURCE：标准答案是A', generationContext: 'PRIVATE_RAG：计算结果是480' };
const choice = { type: 'single', question: '长20、宽8、高3的长方体体积是多少？', options: [{ value: 'A', label: '480' }, { value: 'B', label: '240' }], answer: ['A'], analysis: '体积为长乘宽乘高，得到480。' };
const short = { type: 'short_answer', question: '说明体积公式的意义。', answer: '用每层的体积乘层数。', gradingPoints: ['解释每层', '层数'], analysis: '面积乘高度得到体积。' };
const textFill = { type: 'fill', question: '我国首都是？', answer: '北京', analysis: '我国首都是北京。' };
const answer = (id, value = 'A') => ({ id, answer: value, confidence: 'high', reason: '按题干计算' });
const payload = user => JSON.parse(user.split('只给题干和选项。\n')[1].split('\n\n')[0]);
const actions = [{ type: 'text', content: '先独立完成，再检查思路。' }];

test('generation blind solve sees only the requested question data, never source, retrieval or widget constraints', async () => {
  const calls = [];
  const call = async (system, user, purpose) => {
    calls.push({ system, user, purpose });
    if (purpose === 'quiz-review') return JSON.stringify([answer('scene-1-q1')]);
    return JSON.stringify(calls.length === 1 ? [choice] : actions);
  };
  const scene = await generateScene(input, outline, call);
  const review = calls.find(item => item.purpose === 'quiz-review');
  assert.ok(review);
  assert.doesNotMatch(review.system + review.user, /PRIVATE_SOURCE|PRIVATE_RAG|宿主约束|widget_setState|Worker|THREE/);
  assert.deepEqual(Object.keys(payload(review.user)[0]).sort(), ['id', 'options', 'question', 'type']);
  assert.equal(scene.content.questions[0].review.verdict, 'PASS');
  assert.match(calls[0].user, /PRIVATE_SOURCE/);
});

test('mixed quiz blindly solves only comparable questions while retaining original IDs and all five grading types', async () => {
  const generated = [short, choice, textFill, { type: 'judge', question: '2是偶数。', answer: '对', analysis: '2可被2整除。' }, { type: 'fill', question: '8乘3？', answer: 24, analysis: '8乘3等于24。' }];
  const calls = [];
  const call = async (system, user, purpose) => {
    calls.push({ system, user, purpose });
    if (purpose === 'quiz-review') return JSON.stringify([answer('scene-1-q2'), answer('scene-1-q4', '对'), answer('scene-1-q5', '24')]);
    return JSON.stringify(calls.length === 1 ? generated : actions);
  };
  const scene = await generateScene(input, outline, call);
  assert.deepEqual(payload(calls.find(item => item.purpose === 'quiz-review').user).map(item => item.id), ['scene-1-q2', 'scene-1-q4', 'scene-1-q5']);
  assert.deepEqual(scene.content.questions.map(item => item.review.verdict), ['UNVERIFIED', 'PASS', 'UNVERIFIED', 'PASS', 'PASS']);
  assert.deepEqual(scene.content.questions[0].gradingPoints, short.gradingPoints);
});

test('an all-short-answer and text-fill audit makes no blind model request', async () => {
  let calls = 0;
  const result = await auditQuiz({ courseId: 'c', questions: [{ ...short, id: 'short-id' }, { ...textFill, id: 'text-id' }] }, async () => {
    calls++; return JSON.stringify([answer('short-id', 'NONE'), answer('text-id', 'NONE')]);
  });
  assert.equal(calls, 0);
  assert.equal(result.reviewed, 0);
  assert.equal(result.callFailed, false);
  assert.ok(result.findings.every(item => item.outcome === 'unjudged'));
});

test('low remaining job budget skips regeneration and actions, preserving a disputed question only as thinking', async () => {
  let remaining = 420_000;
  const calls = [];
  const call = Object.assign(async (system, user, purpose) => {
    calls.push({ system, user, purpose });
    if (purpose === 'quiz-review') { remaining = 12_000; return JSON.stringify([answer('scene-1-q1', 'B')]); }
    if (purpose === 'quiz-regenerate') return JSON.stringify({ ...choice, answer: ['B'] });
    return JSON.stringify(calls.length === 1 ? [choice] : actions);
  }, { remainingMs: () => remaining });
  const scene = await generateScene(input, outline, call);
  assert.equal(calls.length, 2, '剩余预算用于保存有效页面，不能再启动重生成或讲授动作调用');
  assert.equal(scene.content.questions.length, 0);
  assert.equal(scene.content.thinking.length, 1);
  assert.equal(scene.content.thinking[0].question, choice.question);
  assert.equal(scene.content.thinking[0].review.verdict, 'RISKY_THINK');
  assert.match(scene.content.thinking[0].review.reason, /预算/);
  assert.ok(scene.actions.some(item => item.type === 'speech'));
});

test('regenerated but unchecked content is never scored when the job budget runs out', async () => {
  let remaining = 420_000;
  const calls = [];
  const call = Object.assign(async (system, user, purpose) => {
    calls.push({ system, user, purpose });
    if (purpose === 'quiz-review') return JSON.stringify([answer('scene-1-q1', 'B')]);
    if (purpose === 'quiz-regenerate') { remaining = 3_000; return JSON.stringify({ ...choice, answer: ['B'] }); }
    return JSON.stringify(calls.length === 1 ? [choice] : actions);
  }, { remainingMs: () => remaining });
  const scene = await generateScene(input, outline, call);
  assert.equal(calls.length, 3);
  assert.equal(scene.content.questions.length, 0);
  assert.equal(scene.content.thinking.length, 1);
  assert.ok(scene.actions.some(item => item.type === 'speech'));
});

test('a fully generated page remains usable if the optional action model fails', async () => {
  const slideOutline = { ...outline, type: 'slide', title: '体积公式', description: '面积乘高度得到体积。' };
  let count = 0;
  const scene = await generateScene({ ...input, outlines: [slideOutline] }, slideOutline, async () => {
    if (count++ === 0) return JSON.stringify({ elements: [{ type: 'text', left: 0, top: 0, width: 600, height: 100, content: '<p>长 × 宽 × 高</p>' }] });
    throw new Error('actions call deadline');
  });
  assert.equal(count, 2);
  assert.match(scene.content.canvas.elements[0].content, /长/);
  assert.ok(scene.actions.some(item => item.type === 'speech'));
});

test('existing-course repair respects the remaining job budget without returning a partial replacement', async () => {
  let remaining = 420_000, count = 0;
  const original = { ...choice, id: 'original-id' };
  const call = Object.assign(async (_system, _user, purpose) => {
    count++;
    if (purpose === 'quiz-review') { remaining = 4_000; return JSON.stringify([answer(original.id, 'B')]); }
    return JSON.stringify({ ...choice, answer: ['B'] });
  }, { remainingMs: () => remaining });
  const result = await planQuizRepair({ courseId: 'course-1', questions: [original] }, call);
  assert.equal(count, 1);
  assert.equal(result.changes[0].action, 'none');
  assert.equal(result.changes[0].after, undefined);
  assert.match(result.changes[0].reason, /预算/);
});
