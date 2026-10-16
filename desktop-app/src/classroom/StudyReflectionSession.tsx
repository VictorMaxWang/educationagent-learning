import { useEffect, useRef, useState } from 'react';
import { checkLearningAttempt, getLearningAttempts, getLearningHint, prepareSelfCheck, saveLearningAttempt, submitSelfCheck } from './study-api';
import { newLocalId } from './navigation';
import type { LearningAttempt } from './study-types';
import { currentLearningHistory, EMPTY_REFLECTION, reflectionKey, reflectionQuestion, restoreReflection, type StudyReflection } from './study-reflection-model';

const ANALYSIS_LABELS = { covered: '已说清楚', missing: '还可以补充', conflict: '需要再核对', uncertain: '暂时无法判断' };
const HINT_LABELS = ['回看哪里', '想一个问题', '给一点线索', '看看示例解释'];
type BusyAction = '' | 'save' | 'check' | 'hint' | 'prepare' | 'submit';

export default function StudyReflectionSession({ courseId, sceneId, revision, sceneTitle, onAskTeacher, onStudyUpdate }: {
  courseId: string; sceneId: string; revision: number; sceneTitle: string; onAskTeacher(text: string): void;
  onStudyUpdate?(goals: string[]): void;
}) {
  const key = reflectionKey(courseId, sceneId, revision);
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState<StudyReflection>(() => { try { return restoreReflection(localStorage.getItem(key)); } catch { return EMPTY_REFLECTION; } });
  const [history, setHistory] = useState<LearningAttempt[]>([]);
  const [fingerprint, setFingerprint] = useState<string>();
  const [attempt, setAttempt] = useState<LearningAttempt | null>(null);
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState<BusyAction>('');
  const [loadingHistory, setLoadingHistory] = useState(true);
  const [error, setError] = useState('');
  const [historyError, setHistoryError] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const interaction = useRef(false);
  const writeRequest = useRef<{ body: string; id: string } | null>(null);
  const checkRequest = useRef<{ attemptId: string; id: string } | null>(null);
  const selfSubmission = useRef<{ attemptId: string; answer: string; id: string } | null>(null);
  const busyRef = useRef(false);
  useEffect(() => {
    const active = new AbortController(); controller.current = active;
    void getLearningAttempts(courseId, sceneId, active.signal).then(result => {
      if (active.signal.aborted) return;
      const current = currentLearningHistory(result, courseId, sceneId);
      setFingerprint(result.contentFingerprint); setHistory(current);
      // 本机草稿优先；没有编辑动作时才恢复对应服务器版本，旧草稿不会自动上传。
      if (!interaction.current) {
        const match = current.find(item => item.understanding === value.understanding && item.checkPoint === value.checkPoint);
        if (match) { setAttempt(match); setAnswer(match.selfCheckAnswer || ''); }
      }
    }).catch(() => { if (!active.signal.aborted) setHistoryError(true); })
      .finally(() => { if (!active.signal.aborted) setLoadingHistory(false); });
    return () => { active.abort(); controller.current = null; };
    // 会话由内容 key 创建，播放状态变化不重新读取或重置草稿。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [courseId, sceneId, key]);
  useEffect(() => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 不影响本次思考与显式保存。 */ } }, [key, value]);
  const dirty = !attempt || attempt.understanding !== value.understanding || attempt.checkPoint !== value.checkPoint;
  const hasText = Boolean(value.understanding.trim() || value.checkPoint.trim());
  const current = !dirty && attempt?.status !== 'stale' ? attempt : null;
  const confirmed = typeof current?.selfCheckResult?.correct === 'boolean';
  const answerSubmitted = Boolean(current && (current.selfCheckSubmissionId || selfSubmission.current?.attemptId === current.id));
  const updateAttempt = (next: LearningAttempt) => {
    setAttempt(next); setFingerprint(next.contentFingerprint);
    setError(next.error || '');
    if (next.id !== attempt?.id) setAnswer(next.selfCheckAnswer || '');
    else if (next.selfCheckAnswer !== undefined) setAnswer(next.selfCheckAnswer);
    setHistory(previous => [next, ...previous.filter(item => item.id !== next.id && item.contentFingerprint === next.contentFingerprint)].slice(0, 10));
  };
  const action = async (kind: Exclude<BusyAction, ''>, work: (signal: AbortSignal) => Promise<LearningAttempt>) => {
    if (busyRef.current || !controller.current || controller.current.signal.aborted) return;
    const active = controller.current;
    interaction.current = true; busyRef.current = true; setBusy(kind); setError('');
    try {
      const next = await work(active.signal);
      if (!active.signal.aborted) { updateAttempt(next); if (kind === 'check' && next.status === 'checked') onStudyUpdate?.(next.suggestedGoals || []); }
    }
    catch (problem) { if (!active.signal.aborted) setError(problem instanceof Error ? problem.message : '这次未完成，请稍后重试；你的文字仍保留着。'); }
    finally { if (!active.signal.aborted) { busyRef.current = false; setBusy(''); } }
  };
  const save = () => {
    const body = { sceneId, understanding: value.understanding, checkPoint: value.checkPoint, ...(attempt ? { parentAttemptId: attempt.id } : {}), ...(fingerprint ? { expectedContentFingerprint: fingerprint } : {}) };
    const serialized = JSON.stringify(body);
    // 响应未知时复用请求 ID，文字改变后才创建新版本。
    if (writeRequest.current?.body !== serialized) writeRequest.current = { body: serialized, id: newLocalId() };
    void action('save', signal => saveLearningAttempt(courseId, { ...body, requestId: writeRequest.current!.id }, signal));
  };
  const check = () => {
    if (!current) return;
    if (checkRequest.current?.attemptId !== current.id) checkRequest.current = { attemptId: current.id, id: newLocalId() };
    void action('check', signal => checkLearningAttempt(current.id, checkRequest.current!.id, signal));
  };
  const submit = () => {
    if (!current || !answer.trim() || confirmed) return;
    if (current.selfCheckSubmissionId && current.selfCheckAnswer === answer) selfSubmission.current = { attemptId: current.id, answer, id: current.selfCheckSubmissionId };
    if (selfSubmission.current?.attemptId !== current.id || selfSubmission.current.answer !== answer) selfSubmission.current = { attemptId: current.id, answer, id: newLocalId() };
    void action('submit', signal => submitSelfCheck(current.id, answer, selfSubmission.current!.id, signal));
  };
  const chooseHistory = (item: LearningAttempt) => {
    interaction.current = true; setAttempt(item); setValue({ understanding: item.understanding, checkPoint: item.checkPoint });
    setAnswer(item.selfCheckAnswer || ''); setError('');
  };
  const nextHint = Math.max(current?.hintLevel || 0, ...(current?.hints || []).map(hint => hint.level), 0) + 1;
  const question = current?.selfCheck;
  const choices = question?.options?.length ? question.options : question?.type === 'judge' ? [{ label: '正确', value: '对' }, { label: '错误', value: '错' }] : [];
  return <section className="cr-reflection" aria-label="先想一想再核对">
    <button type="button" className="cr-reflection-heading" onClick={() => setOpen(previous => !previous)} aria-expanded={open}><span aria-hidden="true">✦</span><span><strong>先用自己的话说一说</strong><small>想一句、核对一个点，也可以稍后再做</small></span><span aria-hidden="true">{open ? '−' : '＋'}</span></button>
    {open && <div className="cr-reflection-body">
      <p className="cr-reflection-intro">试着说出你理解的内容。小陶会和你一起回看资料，看看哪些地方已经说清楚。</p>
      <label>我现在的理解<textarea maxLength={1200} rows={3} value={value.understanding} disabled={Boolean(busy)} placeholder="我觉得这一页想告诉我……" onChange={event => { interaction.current = true; setValue(previous => ({ ...previous, understanding: event.target.value })); }} /></label>
      <label>我想再核对的一个点<textarea maxLength={800} rows={2} value={value.checkPoint} disabled={Boolean(busy)} placeholder="哪一步还不确定？准备回看哪段资料？" onChange={event => { interaction.current = true; setValue(previous => ({ ...previous, checkPoint: event.target.value })); }} /></label>
      <div className="cr-reflection-footer"><small>{!dirty ? '这次思考已保存在网站，换设备也能继续。' : attempt ? '你改了思路，保存后会保留为新的一次思考。' : '草稿留在当前浏览器，点击保存后同步到网站。'}</small><div className="cr-reflection-actions"><button type="button" className="cr-button secondary" disabled={Boolean(busy) || !hasText || (!dirty && attempt?.status !== 'stale') || loadingHistory} onClick={save}>{busy === 'save' ? '正在保存…' : attempt ? '保存新的思考' : '保存这次思考'}</button><button type="button" className="cr-button" disabled={Boolean(busy) || !current || current.status === 'checked'} onClick={check}>{busy === 'check' ? '小陶正在对照资料…' : '请小陶对照资料'}</button></div></div>
      {error && <p className="cr-error" role="alert">{error}</p>}
      {attempt?.status === 'stale' && <p role="status" className="cr-reflection-note">这一页资料更新了，请重新保存你的思考后再核对。</p>}
      {current && (current.analysis?.length || current.summary || current.status === 'unconfirmed') ? <section className="cr-reflection-check" aria-label="小陶的资料核对">
        <h4>对照资料，一起核对</h4>{current.summary && <p>{current.summary}</p>}
        {current.status === 'unconfirmed' && <p className="cr-reflection-note" role="status">这次还没有得到确定的核对结果。你的思考已保存，可以稍后再试。</p>}
        <div className="cr-reflection-findings">{current.analysis?.map((finding, index) => <article key={`${finding.goal}-${index}`} className={`cr-reflection-finding ${finding.status}`}><div><span>{ANALYSIS_LABELS[finding.status]}</span><strong>{finding.goal}</strong></div><p>{finding.feedback}</p>{finding.quote && finding.anchorId && <details><summary>回看对应资料</summary><blockquote>{finding.quote}</blockquote></details>}</article>)}</div>
      </section> : null}
      {current?.status === 'checked' && <section className="cr-reflection-hints" aria-label="逐步想一想"><div className="cr-reflection-section-heading"><h4>需要一点提示吗？</h4>{nextHint <= 4 && <button type="button" className="cr-link" disabled={Boolean(busy)} onClick={() => void action('hint', signal => getLearningHint(current.id, nextHint, signal))}>{busy === 'hint' ? '正在准备提示…' : HINT_LABELS[nextHint - 1]}</button>}</div>{current.hints?.map(hint => <article key={hint.level}><strong>{HINT_LABELS[hint.level - 1] || '提示'}</strong><p>{hint.text}</p>{hint.quote && hint.anchorId && <details><summary>回看提示的资料</summary><blockquote>{hint.quote}</blockquote></details>}</article>)}{nextHint > 4 && <p className="cr-reflection-note">提示已经看完，可以回到上面，用自己的话补充一次。</p>}</section>}
      {current?.status === 'checked' && <section className="cr-reflection-self-check" aria-label="换一种问法自检">
        <div className="cr-reflection-section-heading"><h4>换一种问法，自己试一试</h4>{!question && <button type="button" className="cr-button secondary" disabled={Boolean(busy)} onClick={() => void action('prepare', signal => prepareSelfCheck(current.id, signal))}>{busy === 'prepare' ? '正在准备问题…' : '试一道自检题'}</button>}</div>
        {question && <><fieldset disabled={Boolean(busy) || confirmed || answerSubmitted}><legend>{question.question}</legend>{choices.length ? <div className="cr-reflection-options">{choices.map(option => {
          const selected = question.type === 'multiple' ? answer.split('').includes(option.value) : answer === option.value;
          return <label key={option.value}><input type={question.type === 'multiple' ? 'checkbox' : 'radio'} name={`reflection-self-${current.id}`} checked={selected} onChange={() => setAnswer(previous => question.type === 'multiple' ? (selected ? previous.split('').filter(value => value !== option.value) : [...previous.split('').filter(Boolean), option.value]).sort().join('') : option.value)} /><span>{option.label}</span></label>;
        })}</div> : <label>我的自检答案<textarea rows={question.type === 'fill' ? 2 : 3} maxLength={4000} value={answer} onChange={event => setAnswer(event.target.value)} placeholder="先自己想一想，再写下答案。" /></label>}</fieldset>
          {!confirmed && <button type="button" className="cr-button secondary" disabled={Boolean(busy) || !answer.trim()} onClick={submit}>{busy === 'submit' ? '正在核对答案…' : current.selfCheckResult ? '再核对这次答案' : '核对自检答案'}</button>}
          {current.selfCheckResult && <div className={`cr-reflection-self-result ${current.selfCheckResult.correct === true ? 'covered' : current.selfCheckResult.correct === false ? 'conflict' : 'uncertain'}`} role="status"><strong>{current.selfCheckResult.correct === true ? '这道自检题答对了。' : current.selfCheckResult.correct === false ? '还有一个地方值得再想想。' : '这道题暂时没有确定的评阅结果。'}</strong>{current.selfCheckResult.correctAnswer && <p>参考答案：{current.selfCheckResult.correctAnswer}</p>}<p>{current.selfCheckResult.explanation}</p></div>}
          <small className="cr-reflection-note">这次使用了 {Math.min(4, nextHint - 1)} 级提示。自检与前面的自述分别记录。</small>
          {answerSubmitted && !confirmed && <p className="cr-reflection-note">这次答案已提交，重试会沿用同一份答案。</p>}
          {confirmed && <p className="cr-reflection-note">想再试一次，可以补充上面的理解，保存新的思考。</p>}
        </>}
      </section>}
      {current?.suggestedGoals?.length ? <p className="cr-reflection-followup">接下来可以重点看看：{current.suggestedGoals.join('、')}</p> : null}
      {history.length > 0 && <details className="cr-reflection-history"><summary>网站保存的思考（{history.length}）</summary>{history.map(item => <button type="button" key={item.id} disabled={Boolean(busy)} aria-pressed={attempt?.id === item.id} onClick={() => chooseHistory(item)}><span>{item.understanding || item.checkPoint}</span><small>{new Date(item.createdAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</small></button>)}</details>}
      {historyError && <p className="cr-reflection-note">网站记录暂时未读到；本机草稿还在，可以继续思考。</p>}
      <div className="cr-reflection-bottom"><button type="button" className="cr-link" disabled={!hasText} onClick={() => onAskTeacher(reflectionQuestion(value, sceneTitle))}>带着这段思考继续问小陶 →</button><button type="button" className="cr-link" onClick={() => setOpen(false)}>稍后再想，继续学习</button></div>
    </div>}
  </section>;
}
