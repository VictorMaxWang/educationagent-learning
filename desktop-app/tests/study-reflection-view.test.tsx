// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LearningAttempt } from '../src/classroom/study-types';
import { readFileSync } from 'node:fs';
const reflectionCss = readFileSync('src/classroom/study-reflection.css', 'utf8');
const api = vi.hoisted(() => ({ history: vi.fn(), save: vi.fn(), check: vi.fn(), hint: vi.fn(), prepare: vi.fn(), submit: vi.fn() }));
vi.mock('../src/classroom/study-api', () => ({ getLearningAttempts: api.history, saveLearningAttempt: api.save, checkLearningAttempt: api.check, getLearningHint: api.hint, prepareSelfCheck: api.prepare, submitSelfCheck: api.submit }));
import StudyReflectionCard, { reflectionKey } from '../src/classroom/StudyReflectionCard';
import { currentLearningHistory } from '../src/classroom/study-reflection-model';

const attempt = (changes: Partial<LearningAttempt> = {}): LearningAttempt => ({ id: 'attempt', courseId: 'course', sceneId: 'scene', contentFingerprint: 'content', understanding: '一层有十二个小方块', checkPoint: '为什么要乘层数', createdAt: '2026-10-16T10:00:00Z', status: 'saved', ...changes });
const checked = (changes: Partial<LearningAttempt> = {}) => attempt({ status: 'checked', summary: '你已经找到了每层的小方块。', analysis: [{ goal: '先数一层', status: 'covered', feedback: '你说出了每层的个数。', anchorId: 'source-1', quote: '每层有十二个小方块。' }, { goal: '乘层数', status: 'missing', feedback: '再想想有几层。', anchorId: 'source-1', quote: '体积等于每层的个数乘层数。' }, { goal: '单位', status: 'uncertain', feedback: '现有资料没有说明单位。' }], ...changes });
let host: HTMLDivElement, root: Root;
const ask = vi.fn();
const button = (label: string) => Array.from(host.querySelectorAll('button')).find(element => element.textContent?.includes(label))!;
const render = (revision = 1, sceneId = 'scene') => root.render(<StudyReflectionCard courseId="course" sceneId={sceneId} revision={revision} sceneTitle="长方体体积" onAskTeacher={ask} />);
const draft = () => localStorage.setItem(reflectionKey('course', 'scene', 1), JSON.stringify({ understanding: attempt().understanding, checkPoint: attempt().checkPoint }));
const fill = (index: number, value: string) => {
  const textarea = host.querySelectorAll('textarea')[index];
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(textarea, value);
  textarea.dispatchEvent(new Event('input', { bubbles: true }));
};
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); Object.values(api).forEach(mock => mock.mockReset()); ask.mockReset(); localStorage.clear();
  api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [] }); api.save.mockResolvedValue(attempt()); api.check.mockResolvedValue(checked());
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });

describe('从自述到核对的可跳过流程', () => {
  it('旧本机草稿仅显式保存时上传，原文和内容指纹随请求发送', async () => {
    draft(); await act(async () => render()); await act(async () => button('先用自己的话').click());
    expect(host.querySelector('textarea')?.value).toBe(attempt().understanding); expect(api.save).not.toHaveBeenCalled(); expect(api.check).not.toHaveBeenCalled();
    await act(async () => button('保存这次思考').click());
    expect(api.save).toHaveBeenCalledWith('course', expect.objectContaining({ sceneId: 'scene', understanding: attempt().understanding, checkPoint: attempt().checkPoint, expectedContentFingerprint: 'content', requestId: expect.any(String) }), expect.any(AbortSignal));
    expect(api.check).not.toHaveBeenCalled(); await act(async () => button('请小陶对照').click());
    expect(host.textContent).toContain('已说清楚'); expect(host.textContent).toContain('还可以补充'); expect(host.textContent).toContain('暂时无法判断');
    expect(host.querySelector('blockquote')?.textContent).toBe('每层有十二个小方块。');
    expect(host.textContent).not.toContain('掌握分');
  });
  it('保存后修改会创建关联的新版本，旧的首次表述仍在历史中', async () => {
    draft(); api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [attempt()] });
    api.save.mockImplementation(async (_course, body) => attempt({ id: 'second', parentAttemptId: body.parentAttemptId, understanding: body.understanding }));
    await act(async () => render()); await act(async () => button('先用自己的话').click());
    await act(async () => fill(0, '一层十二个，共有三层')); expect(button('请小陶对照').disabled).toBe(true);
    await act(async () => button('保存新的思考').click());
    expect(api.save.mock.calls[0][1]).toMatchObject({ parentAttemptId: 'attempt', understanding: '一层十二个，共有三层' });
    expect(host.textContent).toContain('网站保存的思考（2）'); expect(host.textContent).toContain('一层有十二个小方块');
  });
  it('保存网络未知时重试复用 requestId，不重复生成首次记录', async () => {
    draft(); api.save.mockRejectedValueOnce(new Error('网络连接暂时断开')).mockResolvedValue(attempt());
    await act(async () => render()); await act(async () => button('先用自己的话').click());
    await act(async () => button('保存这次思考').click()); expect(host.textContent).toContain('网络连接暂时断开');
    await act(async () => button('保存这次思考').click());
    expect(api.save.mock.calls[0][1].requestId).toBe(api.save.mock.calls[1][1].requestId);
  });
  it('可以收起跳过，也可把思路带入原小陶问答入口', async () => {
    draft(); await act(async () => render()); await act(async () => button('先用自己的话').click());
    await act(async () => button('带着这段思考').click()); expect(ask).toHaveBeenCalledWith(expect.stringContaining('一层有十二个小方块'));
    await act(async () => button('稍后再想').click()); expect(host.querySelector('textarea')).toBeNull(); expect(api.save).not.toHaveBeenCalled(); expect(api.check).not.toHaveBeenCalled();
  });
  it('历史只展示同课程、同页面、同指纹，内容修改会中止旧核对', async () => {
    const wrong = [attempt({ id: 'other-course', courseId: 'other' }), attempt({ id: 'other-scene', sceneId: 'other' }), attempt({ id: 'old', contentFingerprint: 'old' }), attempt({ id: 'stale', status: 'stale' })];
    expect(currentLearningHistory({ contentFingerprint: 'content', attempts: [...wrong, attempt()] }, 'course', 'scene')).toEqual([attempt()]);
    draft(); api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [attempt()] });
    let done!: (value: LearningAttempt) => void; api.check.mockReturnValue(new Promise<LearningAttempt>(resolve => { done = resolve; }));
    await act(async () => render()); await act(async () => button('先用自己的话').click()); await act(async () => button('请小陶对照').click());
    await act(async () => render(2)); expect(api.check.mock.calls[0][2].aborted).toBe(true);
    await act(async () => done(checked({ summary: '这是旧页的迟到反馈' }))); await act(async () => button('先用自己的话').click());
    expect(host.textContent).not.toContain('这是旧页的迟到反馈'); expect(host.querySelector('textarea')?.value).toBe('');
    expect(JSON.parse(localStorage.getItem(reflectionKey('course', 'scene', 1))!).understanding).toBe(attempt().understanding);
  });
  it('提示按1至4逐级请求，不直接跳到示例答案', async () => {
    draft(); api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [checked()] });
    api.hint.mockImplementation(async (_id, level) => checked({ hintLevel: level, hints: Array.from({ length: level }, (_, index) => ({ level: index + 1, text: `第${index + 1}层提示`, anchorId: 'source-1', quote: '体积等于每层的个数乘层数。' })) }));
    await act(async () => render()); await act(async () => button('先用自己的话').click());
    for (const label of ['回看哪里', '想一个问题', '给一点线索', '看看示例解释']) await act(async () => button(label).click());
    expect(api.hint.mock.calls.map(args => args[1])).toEqual([1, 2, 3, 4]); expect(host.textContent).toContain('提示已经看完');
  });
  it('跨设备读取已保存思考，不会直接覆盖当前未保存草稿', async () => {
    localStorage.setItem(reflectionKey('course', 'scene', 1), JSON.stringify({ understanding: '我还在想自己的例子', checkPoint: '' }));
    api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [checked()] });
    await act(async () => render()); await act(async () => button('先用自己的话').click());
    expect(host.querySelector('textarea')?.value).toBe('我还在想自己的例子'); expect(host.querySelector('[aria-label="小陶的资料核对"]')).toBeNull();
    await act(async () => button('一层有十二个小方块').click()); expect(host.querySelector('textarea')?.value).toBe(attempt().understanding);
  });
});

describe('自检与自述分别记录', () => {
  it('长自述历史与自检选项可收缩换行，不把反馈网格撑进问答栏', async () => {
    const style = document.createElement('style'); style.textContent = reflectionCss; document.head.append(style);
    const longText = '长方体面积和通分比较的完整思路'.repeat(60);
    const longOption = 'https://example.invalid/' + 'unbrokenResourceReference'.repeat(25);
    const record = checked({ understanding: longText, selfCheck: { id: 'long-check', type: 'single', question: longText, hasAnswer: false, options: [{ value: 'A', label: longOption }, { value: 'B', label: '先确认整体，再比较部分。' }] } });
    localStorage.setItem(reflectionKey('course', 'scene', 1), JSON.stringify({ understanding: longText, checkPoint: record.checkPoint }));
    api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [record] });
    try {
      await act(async () => render()); await act(async () => button('先用自己的话').click());
      const computed = (selector: string) => getComputedStyle(host.querySelector(selector)!);
      // jsdom不计算真实网格尺寸；检查匹配真实DOM的约束规则，像素验收由浏览器完成。
      const gridRule = Array.from(style.sheet!.cssRules).find(rule => rule instanceof CSSStyleRule && rule.selectorText.includes('.cr-reflection-body') && rule.style.getPropertyValue('grid-template-columns')) as CSSStyleRule;
      expect(gridRule.style.getPropertyValue('grid-template-columns')).toMatch(/minmax\(0,\s*1fr\)/);
      expect(computed('.cr-reflection-history>button>span').minWidth).toBe('0px');
      expect(computed('.cr-reflection-options label>span').minWidth).toBe('0px');
      expect(computed('.cr-reflection-options label>span').overflowWrap).toBe('anywhere');
      expect(computed('.cr-reflection-options label').flexDirection).toBe('row');
      expect(computed('.cr-reflection-self-check legend').maxWidth).toBe('100%');
      expect(computed('.cr-reflection-actions>.cr-button').whiteSpace).toBe('normal');
      expect(host.querySelector('.cr-reflection-options label>span')?.textContent).toBe(longOption);
    } finally { style.remove(); }
  });
  it('准备一题、填写并真实提交，未知评阅保留同ID重试且确定后锁定', async () => {
    draft(); api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [checked()] });
    const withQuestion = checked({ selfCheck: { id: 'self-1', type: 'short_answer', question: '每层十二个，共三层，怎样算总数？', hasAnswer: false } });
    api.prepare.mockResolvedValue(withQuestion);
    api.submit.mockImplementationOnce(async (_id, answer, submissionId) => ({ ...withQuestion, selfCheckAnswer: answer, selfCheckSubmissionId: submissionId, selfCheckResult: { correct: null, explanation: '暂时无法完成评阅' } })).mockImplementationOnce(async (_id, answer, submissionId) => ({ ...withQuestion, selfCheckAnswer: answer, selfCheckSubmissionId: submissionId, selfCheckResult: { correct: true, correctAnswer: '12×3=36', explanation: '把三层的个数相加。' } }));
    await act(async () => render()); await act(async () => button('先用自己的话').click()); await act(async () => button('试一道自检题').click());
    expect(api.submit).not.toHaveBeenCalled(); await act(async () => fill(2, '12乘3等于36')); await act(async () => button('核对自检答案').click());
    expect(host.textContent).toContain('暂时没有确定的评阅结果'); await act(async () => button('再核对这次答案').click());
    expect(api.submit.mock.calls[0][2]).toBe(api.submit.mock.calls[1][2]); expect(host.textContent).toContain('这道自检题答对了');
    expect((host.querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
  });
  it('刷新后恢复未知评阅的原答和提交ID，继续同一次提交', async () => {
    draft(); const unresolved = checked({ selfCheck: { id: 'self-1', type: 'fill', question: '十二乘三是多少？', hasAnswer: true }, selfCheckAnswer: '36', selfCheckSubmissionId: 'saved-submission', selfCheckResult: { correct: null, explanation: '上次尚未完成' } });
    api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [unresolved] }); api.submit.mockResolvedValue(unresolved);
    await act(async () => render()); await act(async () => button('先用自己的话').click()); expect(host.querySelectorAll('textarea')[2].value).toBe('36');
    await act(async () => button('再核对这次答案').click()); expect(api.submit).toHaveBeenCalledWith('attempt', '36', 'saved-submission', expect.any(AbortSignal));
  });
  it('判断题使用明确的正确/错误值，不能把选择动作当成绩', async () => {
    draft(); api.history.mockResolvedValue({ contentFingerprint: 'content', attempts: [checked({ selfCheck: { id: 'self-1', type: 'judge', question: '三层要乘三？', hasAnswer: false } })] }); api.submit.mockResolvedValue(checked());
    await act(async () => render()); await act(async () => button('先用自己的话').click()); await act(async () => (host.querySelectorAll('input')[1] as HTMLInputElement).click());
    expect(api.submit).not.toHaveBeenCalled(); await act(async () => button('核对自检答案').click()); expect(api.submit.mock.calls[0][1]).toBe('错');
  });
});
