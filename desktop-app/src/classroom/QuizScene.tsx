import { useEffect, useRef, useState } from 'react';
import { getQuizRevisions, submitAnswers, type QuizRevision } from './api';
import { courseHash, newLocalId } from './navigation';
import type { Question, QuizResult, Scene, ThinkingItem } from './types';

const QUESTION_LABELS = { single: '单选', multiple: '多选', judge: '判断', fill: '填空', short_answer: '简答' };

export function answerForChoices(previous: string, choice: string, multiple: boolean): string {
  if (!multiple) return choice;
  const values = previous.split('').filter(Boolean);
  return (values.includes(choice) ? values.filter(value => value !== choice) : [...values, choice]).sort().join('');
}

export default function QuizScene({ courseId, scene }: { courseId: string; scene: Scene }) {
  // 同一 scene 的版本或公开题面更新都要重置尝试；旧草稿、回执和在途响应不能附着到新题。
  const identity = JSON.stringify([courseId, scene.id, scene.questionVersion, (scene.content.questions || []).map(question => ({ id: question.id, no: question.no, type: question.type, question: question.question, options: question.options, hasAnswer: question.hasAnswer }))]);
  return <QuizAttempt key={identity} courseId={courseId} scene={scene} />;
}

function QuizAttempt({ courseId, scene }: { courseId: string; scene: Scene }) {
  const questions: Question[] = scene.content.questions || [];
  // 规则检查或模型盲解发现问题、重生成仍未解决的题只保留题干，不计分、不判分。
  const thinking: ThinkingItem[] = scene.content.thinking || [];
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [result, setResult] = useState<QuizResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [needsReevaluation, setNeedsReevaluation] = useState(false);
  const [needsReload, setNeedsReload] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const [revision, setRevision] = useState<QuizRevision | null>(null);
  const [revisionStatus, setRevisionStatus] = useState<'checking' | 'ready' | 'failed'>('checking');
  const [revisionRetry, setRevisionRetry] = useState(0);
  const mounted = useRef(true);
  const submitting = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setRevision(null); setRevisionStatus('checking');
    // 旧题保留作答历史，但发现题面有误后不能继续按旧标答判分；服务端再次检查以覆盖并发修订。
    void getQuizRevisions(courseId, controller.signal).then(response => {
      if (controller.signal.aborted) return;
      if (response.courseId !== courseId || !Array.isArray(response.revisions)) throw new Error('练习版本信息无效');
      const replacement = response.revisions.find(item => item.sceneId === scene.id) || null;
      if (replacement && (!/^[A-Za-z0-9_-]{1,80}$/.test(replacement.newCourseId) || !/^[A-Za-z0-9_-]{1,80}$/.test(replacement.newSceneId)
        || replacement.newCourseId === courseId && replacement.newSceneId === scene.id)) throw new Error('当前练习入口无效');
      setRevision(replacement); setRevisionStatus('ready');
    }).catch(() => { if (!controller.signal.aborted) setRevisionStatus('failed'); });
    return () => controller.abort();
  }, [courseId, scene.id, revisionRetry]);
  // 网络结果未知时保留 ID；明确封存回执则等待用户主动重新评阅，不能自动换号重发。
  const submission = useRef<{ id: string; fingerprint: string; sealed: boolean } | null>(null);
  const submit = async (reevaluate = false) => {
    if (revisionStatus !== 'ready' || revision || needsReload || submitting.current) return;
    if (submission.current?.sealed && !reevaluate) return;
    const payload = questions.map(question => ({ no: question.no, answer: answers[question.no] || '' }));
    const fingerprint = JSON.stringify(payload);
    if (!submission.current || submission.current.fingerprint !== fingerprint || submission.current.sealed && reevaluate)
      submission.current = { id: newLocalId(), fingerprint, sealed: false };
    const request = submission.current;
    // 换号只发生在上面这一次明确操作；若新请求断线，下次恢复仍使用这个尚未封存的编号。
    submitting.current = true; setBusy(true); setError(''); setResult(null); setNeedsReevaluation(false); setRecovering(false);
    try {
      const next = await submitAnswers(courseId, scene.id, request.id, payload, scene.questionVersion);
      const pending = next.status === 'pending' || (next.pendingCount ?? 0) > 0 || next.results.some(item => item.correct === null);
      if (mounted.current) { request.sealed = pending; setResult(next); setNeedsReevaluation(pending); }
    } catch (error) {
      if (mounted.current) {
        const failure = error as { status?: number; code?: string };
        if (failure?.status === 409 && failure.code === 'QUESTION_VERSION_CHANGED') {
          // 服务端拒绝的是过期题面；保留可见答案与请求身份，等待用户重新载入当前练习。
          setNeedsReload(true);
          setError('练习内容已更新，请重新载入后作答。');
        } else if (failure?.status === 409 && failure.code === 'EVALUATION_UNKNOWN') {
          request.sealed = true; setNeedsReevaluation(true);
          setError('本次评阅结果未确认，请重新评阅');
        } else {
          setRecovering(failure?.status !== 409);
          setError(failure?.status === 409 ? '内容已更新，请刷新后继续。' : error instanceof Error ? error.message : '提交失败，请稍后重试');
        }
      }
    }
    finally { submitting.current = false; if (mounted.current) setBusy(false); }
  };
  const pendingCount = result ? result.pendingCount ?? result.results.filter(item => item.correct === null).length : 0;
  const confirmedCount = result ? result.confirmedCount ?? result.results.filter(item => item.correct !== null).length : 0;
  const completed = Boolean(result && pendingCount === 0 && !needsReevaluation);
  return <section className="cr-quiz" aria-label="随堂练习">
    <p className="cr-eyebrow">试着独立想一想</p><h2>{scene.title}</h2>
    {revision && <div className="cr-feedback" role="status"><strong>内容已更新</strong><p>已保存的作答记录仍然保留，请打开当前练习继续。</p><a className="cr-button" href={courseHash(revision.newCourseId, revision.newSceneId)}>打开当前练习</a></div>}
    {revisionStatus === 'failed' && <p className="cr-error" role="alert">暂时无法检查练习版本，请重试。<button onClick={() => setRevisionRetry(value => value + 1)}>重新检查版本</button></p>}
    {questions.map(question => {
      const feedback = result?.results.find(item => item.no === question.no);
      const choices = question.type === 'judge' ? [{ value: '对', label: '正确' }, { value: '错', label: '错误' }] : question.options || [];
      return <fieldset key={question.id || question.no} className="cr-question" disabled={busy || completed || needsReload || Boolean(revision) || revisionStatus !== 'ready'}><legend>{question.no}. {question.question}<span className="cr-question-type">{QUESTION_LABELS[question.type]}</span></legend>
        {question.type === 'fill' ? <input className="cr-text-answer" aria-label={`第 ${question.no} 题答案`} maxLength={2000} placeholder="填写你的答案" value={answers[question.no] || ''} onChange={event => setAnswers(previous => ({ ...previous, [question.no]: event.target.value }))} />
          : question.type === 'short_answer' ? <textarea className="cr-text-answer" aria-label={`第 ${question.no} 题答案`} rows={4} maxLength={4000} placeholder="说说你的思路，可以分步说明" value={answers[question.no] || ''} onChange={event => setAnswers(previous => ({ ...previous, [question.no]: event.target.value }))} />
          : <div className="cr-options">{choices.map(option => <label key={option.value} className={`cr-option ${answers[question.no]?.includes(option.value) ? 'selected' : ''}`}><input type={question.type === 'multiple' ? 'checkbox' : 'radio'} name={`question-${scene.id}-${question.no}`} checked={Boolean(answers[question.no]?.includes(option.value))} onChange={() => setAnswers(previous => ({ ...previous, [question.no]: answerForChoices(previous[question.no] || '', option.value, question.type === 'multiple') }))} /><strong>{option.value}</strong><span>{option.label}</span></label>)}</div>}
        {question.type === 'short_answer' && !feedback && <p className="cr-muted">小陶会结合参考答案和要点评阅你的思路。</p>}
        {feedback && <div className={`cr-feedback ${feedback.correct === true ? 'correct' : 'retry'}`}><strong>{feedback.correct === true ? '答对了，继续保持。' : feedback.correct === false ? '一起把这道题弄明白。' : '本题暂未得到确定评阅，请稍后重试。'}</strong><p>参考答案：{feedback.correctAnswer || '请参照下方要点'}</p><p>{feedback.explanation}</p></div>}
      </fieldset>;
    })}
    {thinking.length > 0 && <section className="cr-thinking" aria-label="思考题">
      <p className="cr-eyebrow">这些题不计分</p>
      {thinking.map((item, index) => <div key={item.id} className="cr-thinking-item">
        <p><strong>{index + 1}.</strong> {item.question}</p>
        <p className="cr-muted">先自己说说思路，不用提交，也不计入成绩。</p>
      </div>)}
    </section>}
    {error && <p className="cr-error" role="alert">{error}</p>}
    {questions.length === 0 ? <p className="cr-muted">本页暂无可提交的计分练习。</p>
      : completed && result ? <div className="cr-feedback correct" role="status">已确认 {confirmedCount} 道题，答对 {result.score} 道。需要再练的题会出现在「复习」里。</div>
      : needsReload ? <button className="cr-button" onClick={() => window.location.reload()}>重新载入练习</button>
      : needsReevaluation ? <div className="cr-feedback" role="status">{result && pendingCount > 0 && <>已确认 {confirmedCount} 道，还有 {pendingCount} 道待确认。待确认结果不计为答错。</>}<button className="cr-button" disabled={busy || Boolean(revision) || revisionStatus !== 'ready' || questions.some(question => !answers[question.no]?.trim())} onClick={() => void submit(true)}>重新评阅</button></div>
      : !revision && <button className="cr-button" disabled={busy || revisionStatus !== 'ready' || questions.some(question => !answers[question.no]?.trim())} onClick={() => void submit()}>{busy ? '正在检查答案…' : revisionStatus === 'checking' ? '正在检查练习版本…' : recovering ? '恢复提交这组练习' : '提交这组练习'}</button>}
  </section>;
}
