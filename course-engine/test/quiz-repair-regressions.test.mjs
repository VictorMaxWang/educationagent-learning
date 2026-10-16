import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditQuiz, generateScene, planQuizRepair } from '../dist/engine.js';

const source = { id: 'course-1-q1', no: 1, type: 'single', question: '长方体长20厘米、宽8厘米、高3厘米，体积是多少立方厘米？', options: [{ value: 'A', label: '480' }, { value: 'B', label: '240' }], answer: ['B'], analysis: '20×8×3=240，所以选B。' };
const corrected = { ...source, answer: ['A'], analysis: '体积等于长乘宽乘高，20×8×3=480立方厘米，所以选A。' };
const reply = (id, answer, confidence = 'high') => [{ id, answer, confidence, reason: '体积由长乘宽乘高得到' }];
function responses(...values) {
  const calls = [];
  const call = async (system, user, purpose) => {
    calls.push({ system, user, purpose });
    assert.ok(values.length, '不得在未知结果后额外自动重发模型请求');
    const value = values.shift();
    if (value instanceof Error) throw value;
    return typeof value === 'string' ? value : JSON.stringify(value);
  };
  return { call, calls };
}

test('repair regenerates the whole same-type question instead of retaining a contradictory explanation', async () => {
  const stub = responses(reply(source.id, 'A'), corrected, reply(source.id, 'A'), { id: source.id, status: 'consistent', reason: '解析按体积公式得到480并指向A' });
  const result = await planQuizRepair({ courseId: 'course-1', questions: [source] }, stub.call);
  assert.equal(result.changes.length, 1);
  assert.equal(result.changes[0].action, 'regenerate');
  assert.equal(result.changes[0].id, source.id);
  assert.equal(result.changes[0].after.type, 'single');
  assert.deepEqual(result.changes[0].after.answer, ['A']);
  assert.match(result.changes[0].after.analysis, /480/);
  assert.doesNotMatch(result.changes[0].after.analysis, /240/);
  assert.equal(stub.calls.length, 4);
});

test('repair never accepts a regenerated question of another type', async () => {
  const stub = responses(reply(source.id, 'A'), { type: 'judge', question: '长乘宽乘高得到体积。', answer: '对', analysis: '长方体体积公式是长乘宽乘高。' });
  const result = await planQuizRepair({ courseId: 'course-1', questions: [source] }, stub.call);
  assert.equal(result.changes[0].action, 'none');
  assert.equal(result.changes[0].after, undefined);
  assert.equal(stub.calls.length, 2);
});

test('unknown recheck result does not trigger another paid blind-solving request', async () => {
  const stub = responses(reply(source.id, 'A'), corrected, new Error('network outcome unknown'));
  const result = await planQuizRepair({ courseId: 'course-1', questions: [source] }, stub.call);
  assert.equal(result.changes[0].action, 'none');
  assert.equal(stub.calls.length, 3);
});

test('a blind-solve agreement alone cannot approve a still contradictory repair explanation', async () => {
  const brokenExplanation = { ...corrected, analysis: source.analysis };
  const stub = responses(reply(source.id, 'A'), brokenExplanation, reply(source.id, 'A'), { id: source.id, status: 'inconsistent', reason: '解析仍声称240并指向B' });
  const result = await planQuizRepair({ courseId: 'course-1', questions: [source] }, stub.call);
  assert.equal(result.changes[0].action, 'none');
  assert.equal(result.changes[0].after, undefined);
  assert.equal(stub.calls.length, 4);
});

test('existing six-question page is audited in bounded batches with the original identities', async () => {
  const questions = Array.from({ length: 6 }, (_, index) => ({ ...corrected, id: `old-${index + 1}`, no: index + 1 }));
  const stub = responses(questions.slice(0, 5).map(q => reply(q.id, 'A')[0]), reply(questions[5].id, 'A'));
  const result = await auditQuiz({ courseId: 'course-1', questions }, stub.call);
  assert.equal(result.reviewed, 6);
  assert.equal(result.findings.length, 6);
  assert.deepEqual(result.findings.map(item => item.id), questions.map(item => item.id));
  assert.ok(result.findings.every(item => item.outcome === 'agree'));
  assert.equal(stub.calls.length, 2);
});

test('all rule-rejected questions remain a nonempty unscored thinking page', async () => {
  const leaky = { ...source, analysis: '让我重新计算一下，修正选项后答案是480。' };
  const stub = responses([leaky], [{ type: 'text', content: '先说说长方体体积的计算思路。' }]);
  const outline = { id: 'scene-1', type: 'quiz', order: 0, title: '体积练习', description: '说明体积计算思路', keyPoints: ['长方体体积'], quizConfig: { questionCount: 1, questionTypes: ['single'], difficulty: 'easy' } };
  const result = await generateScene({ courseId: 'course-1', topic: '长方体体积', grade: 5, subject: '数学', outlines: [outline] }, outline, stub.call);
  assert.equal(result.content.questions.length, 0);
  assert.equal(result.content.thinking.length, 1);
  assert.deepEqual(Object.keys(result.content.thinking[0]).filter(key => key !== 'review').sort(), ['id', 'question']);
  assert.equal(stub.calls.length, 2);
});

test('generation stops regeneration on transport failure and safely retains thinking', async () => {
  const stub = responses([source], reply('scene-1-q1', 'A'), new Error('network outcome unknown'), [{ type: 'text', content: '先说说长方体体积的思路。' }]);
  const outline = { id: 'scene-1', type: 'quiz', order: 0, title: '体积练习', description: '说明体积计算思路', keyPoints: ['长方体体积'], quizConfig: { questionCount: 1, questionTypes: ['single'], difficulty: 'easy' } };
  const result = await generateScene({ courseId: 'course-1', topic: '长方体体积', grade: 5, subject: '数学', outlines: [outline] }, outline, stub.call);
  assert.equal(result.content.questions.length, 0);
  assert.equal(result.content.thinking.length, 1);
  assert.equal(stub.calls.filter(call => call.purpose === 'quiz-regenerate').length, 1);
});
