import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import KnowledgeCenterView from '../src/study/KnowledgeCenterView';
import QuizView from '../src/study/QuizView';

const api = vi.hoisted(() => Object.fromEntries([
  'uploadFile', 'saveKnowledge', 'listKnowledge', 'viewKnowledge', 'deleteKnowledge', 'searchKnowledge', 'askKnowledge',
  'getKnowledge', 'toggleKnowledgeFav', 'getKnowledgeQuestions', 'toggleQuestionFav', 'getFavQuestions',
  'generateQuiz', 'submitQuiz', 'gradeOneQuiz', 'getQuizPapers', 'loadQuizPaper', 'passageAudio', 'deleteQuizPaper',
].map(name => [name, vi.fn()])));
vi.mock('../src/api/chatApi', () => api);
let dom: JSDOM, root: Root, host: HTMLElement;
beforeEach(() => {
  vi.resetAllMocks();
  dom = new JSDOM('<div id="root"></div>', { url: 'https://example.test' });
  vi.stubGlobal('window', dom.window); vi.stubGlobal('document', dom.window.document); vi.stubGlobal('navigator', dom.window.navigator);
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.getElementById('root')!; root = createRoot(host);
  api.listKnowledge.mockResolvedValue([]); api.getKnowledge.mockResolvedValue([]); api.getFavQuestions.mockResolvedValue([]);
  api.getQuizPapers.mockResolvedValue([{ quizId: 7, title: '统计测试卷', questionCount: 2 }]);
  api.loadQuizPaper.mockResolvedValue({ quizId: 7, title: '统计测试卷', questions: [{ no: 1, type: 'single', stem: '第一题', options: ['A. 甲', 'B. 乙'] }] });
});
afterEach(async () => { await act(async () => root.unmount()); dom.window.close(); vi.unstubAllGlobals(); });
async function click(text: string) {
  const button = [...host.querySelectorAll('button')].find(candidate => candidate.textContent?.includes(text));
  expect(button, text).toBeDefined(); await act(async () => button!.click());
}
async function showMastery() { await act(async () => root.render(<KnowledgeCenterView userId="student" />)); await click('学习掌握'); }
async function finishQuiz(result: unknown) {
  api.submitQuiz.mockResolvedValue(result);
  await act(async () => root.render(<QuizView userId="student" embedded />)); await click('统计测试卷'); await click('交卷');
}

it('全部待确认的知识点不归入薄弱区，也不显示零正确率', async () => {
  api.getKnowledge.mockResolvedValue([{ knowledgePoint: '尚未核对', accuracy: null, questionCount: 2, correctCount: 0, confirmedCount: 0, pendingCount: 2 }]);
  await showMastery();
  expect(host.querySelector('.mastery-overview-pct')?.textContent).toBe('待确认');
  expect(host.querySelector('.tier-chip-weak')?.textContent).toContain('薄弱 0');
  const pendingTier = [...host.querySelectorAll('.mastery-tier')].find(tier => tier.querySelector('.mastery-tier-title')?.textContent?.includes('待确认'));
  expect(pendingTier?.textContent).toContain('尚未核对');
  expect(host.querySelector('.knowledge-acc')?.textContent).toBe('待确认');
  expect(host.querySelector('.knowledge-count')?.textContent).toContain('待确认 2 题');
});

it('知识点平均值排除待确认项，部分确认项使用确认题数作分母', async () => {
  api.getKnowledge.mockResolvedValue([
    { knowledgePoint: '部分确认', accuracy: 50, questionCount: 3, correctCount: 1, confirmedCount: 2, pendingCount: 1 },
    { knowledgePoint: '尚未核对', accuracy: null, questionCount: 1, correctCount: 0, confirmedCount: 0, pendingCount: 1 },
  ]);
  await showMastery();
  expect(host.querySelector('.mastery-overview-pct')?.textContent).toBe('50%');
  const row = [...host.querySelectorAll('.knowledge-row')].find(item => item.textContent?.includes('部分确认'))!;
  expect(row.querySelector('.knowledge-count')?.textContent).toContain('1/2');
  expect(row.textContent).toContain('共 3 题'); expect(row.textContent).toContain('待确认 1 题');
});

it('知识点旧接口缺少确认统计时保留原有题数显示', async () => {
  api.getKnowledge.mockResolvedValue([{ knowledgePoint: '旧数据', accuracy: 50, questionCount: 4, correctCount: 2 }]);
  await showMastery();
  expect(host.querySelector('.knowledge-count')?.textContent).toBe('2/4');
  expect(host.querySelector('.mastery-overview-pct')?.textContent).toBe('50%');
});

it('刷题结果将待确认题数与确认得分分开显示', async () => {
  await finishQuiz({ score: 1, total: 3, confirmedCount: 2, pendingCount: 1, results: [{ no: 1, correct: true }, { no: 2, correct: false }, { no: 3, correct: null }] });
  expect(host.querySelector('.quiz-result h3')?.textContent).toContain('1 / 2');
  expect(host.querySelector('.quiz-score-sub')?.textContent).toContain('共 3 题');
  expect(host.querySelector('.quiz-score-sub')?.textContent).toContain('待确认 1 题');
});

it('刷题全部待确认时不输出伪造的零得分比例', async () => {
  await finishQuiz({ score: 0, total: 2, confirmedCount: 0, pendingCount: 2, results: [{ no: 1, correct: null }, { no: 2, correct: null }] });
  expect(host.querySelector('.quiz-result h3')?.textContent).toContain('结果待确认');
  expect(host.querySelector('.quiz-result h3')?.textContent).not.toContain('0 /');
  expect(host.querySelector('.quiz-score-sub')?.textContent).toContain('待确认 2 题');
});

it('刷题旧接口缺少新字段时维持原得分与自查显示', async () => {
  await finishQuiz({ score: 1, total: 2, results: [{ no: 1, correct: true }, { no: 2, correct: null }] });
  expect(host.querySelector('.quiz-result h3')?.textContent).toContain('1 / 2');
  expect(host.querySelector('.quiz-score-sub')?.textContent).toContain('AI 未判 1 题');
});
