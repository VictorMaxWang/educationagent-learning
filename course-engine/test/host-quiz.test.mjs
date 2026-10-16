import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeQuizQuestions } from '../dist/engine.js';

test('host boundary rejects ungradable or ambiguous model answers', () => {
  for (const raw of [
    [{ type: 'judge', question: '判断', answer: 'unknown' }],
    [{ type: 'fill', question: '填空', answer: ['A'] }],
    [{ type: 'short_answer', question: '简答' }],
    [{ type: 'multiple', question: '多选', options: [{ value: 'A', label: '甲' }, { value: 'B', label: '乙' }], answer: ['A', 'A'] }],
  ]) assert.throws(() => normalizeQuizQuestions(raw, 'scene'));
  assert.equal(normalizeQuizQuestions([{ type: 'fill', question: '结果为零', answer: 0 }], 's')[0].answer, 0);
  assert.deepEqual(normalizeQuizQuestions([{ type: 'short_answer', question: '说理由', gradingPoints: ['相同整体'] }], 's')[0].gradingPoints, ['相同整体']);
});
