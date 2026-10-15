import { useRef, useState } from 'react';
import { submitAnswers } from './api';
import { newLocalId } from './navigation';
import type { Question, QuizResult, Scene } from './types';

export function answerForChoices(previous: string, choice: string, multiple: boolean): string {
  if (!multiple) return choice;
  const values = previous.split('').filter(Boolean);
  return (values.includes(choice) ? values.filter(value => value !== choice) : [...values, choice]).sort().join('');
}

export default function QuizScene({ courseId, scene }: { courseId: string; scene: Scene }) {
  const questions: Question[] = scene.content.questions || [];
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [result, setResult] = useState<QuizResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // 网络错误后重试同一份答卷沿用 ID，避免重复入账错题和学习记录。
  const submission = useRef<{ id: string; fingerprint: string } | null>(null);
  const submit = async () => {
    const payload = questions.map(question => ({ no: question.no, answer: answers[question.no] || '' }));
    const fingerprint = JSON.stringify(payload);
    if (!submission.current || submission.current.fingerprint !== fingerprint) submission.current = { id: newLocalId(), fingerprint };
    setBusy(true); setError('');
    try { setResult(await submitAnswers(courseId, scene.id, submission.current.id, payload)); }
    catch (error) { setError(error instanceof Error ? error.message : '提交失败，请稍后重试'); }
    finally { setBusy(false); }
  };
  return <section className="cr-quiz" aria-label="随堂练习">
    <p className="cr-eyebrow">试着独立想一想</p><h2>{scene.title}</h2>
    {questions.map(question => {
      const feedback = result?.results.find(item => item.no === question.no);
      return <fieldset key={question.id || question.no} className="cr-question" disabled={busy || Boolean(result)}><legend>{question.no}. {question.question}<span className="cr-question-type">{question.type === 'multiple' ? '多选' : '单选'}</span></legend>
        <div className="cr-options">{question.options.map(option => <label key={option.value} className={`cr-option ${answers[question.no]?.includes(option.value) ? 'selected' : ''}`}><input type={question.type === 'multiple' ? 'checkbox' : 'radio'} name={`question-${scene.id}-${question.no}`} checked={Boolean(answers[question.no]?.includes(option.value))} onChange={() => setAnswers(previous => ({ ...previous, [question.no]: answerForChoices(previous[question.no] || '', option.value, question.type === 'multiple') }))} /><strong>{option.value}</strong><span>{option.label}</span></label>)}</div>
        {feedback && <div className={`cr-feedback ${feedback.correct ? 'correct' : 'retry'}`}><strong>{feedback.correct ? '答对了，继续保持。' : '一起把这道题弄明白。'}</strong><p>参考答案：{feedback.correctAnswer}</p><p>{feedback.explanation}</p></div>}
      </fieldset>;
    })}
    {error && <p className="cr-error" role="alert">{error}</p>}
    {result ? <div className="cr-feedback correct" role="status">完成 {result.total} 道题，答对 {result.score} 道。需要再练的题会出现在「复习」里。</div> : <button className="cr-button" disabled={busy || !questions.length || questions.some(question => !answers[question.no])} onClick={() => void submit()}>{busy ? '正在检查答案…' : '提交这组练习'}</button>}
  </section>;
}
