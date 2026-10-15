import { useEffect, useState } from 'react';
import './study-reflection.css';

export interface StudyReflection { understanding: string; checkPoint: string }
const EMPTY: StudyReflection = { understanding: '', checkPoint: '' };
export function reflectionKey(courseId: string, sceneId: string, revision: number): string {
  return `educationagent.reflection.v1:${courseId}:${sceneId}:${revision}`;
}
export function restoreReflection(raw: string | null): StudyReflection {
  try {
    const value: unknown = JSON.parse(raw || 'null');
    if (!value || typeof value !== 'object') return EMPTY;
    const record = value as Record<string, unknown>;
    return { understanding: typeof record.understanding === 'string' ? record.understanding.slice(0, 1200) : '', checkPoint: typeof record.checkPoint === 'string' ? record.checkPoint.slice(0, 800) : '' };
  } catch { return EMPTY; }
}
export function reflectionQuestion(value: StudyReflection, title: string): string {
  return `关于“${title.slice(0, 160)}”，请先帮我核对理解，再给一点提示。\n我的理解：${value.understanding.trim().slice(0, 1000) || '我还没有想清楚'}\n我想核对：${value.checkPoint.trim().slice(0, 500) || '请帮我指出一个需要核对的地方'}\n请区分已有资料支持的内容和需要我继续确认的内容，不要直接替我完成整道题。`;
}

/** M003 §5.3 的学习过程设计借鉴：先自行重构，再核对；不把观察关联解释成学习效果保证。 */
export default function StudyReflectionCard({ courseId, sceneId, revision, sceneTitle, onAskTeacher }: {
  courseId: string; sceneId: string; revision: number; sceneTitle: string; onAskTeacher(text: string): void;
}) {
  const key = reflectionKey(courseId, sceneId, revision);
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState<StudyReflection>(EMPTY);
  const [loadedKey, setLoadedKey] = useState('');
  useEffect(() => {
    // 内容修订后重新开始这份自述，避免把旧课件上的判断当作新内容的学习证据。
    try { setValue(restoreReflection(localStorage.getItem(key))); } catch { setValue(EMPTY); }
    setLoadedKey(key);
  }, [key]);
  useEffect(() => {
    if (loadedKey !== key) return;
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 存储不可用时仍可完成本次思考。 */ }
  }, [key, loadedKey, value]);
  return <section className="cr-reflection" aria-label="先想一想再核对">
    <button className="cr-reflection-heading" onClick={() => setOpen(previous => !previous)} aria-expanded={open}><span aria-hidden="true">✦</span><span><strong>先用自己的话说一说</strong><small>想一句、核对一个点，也可以稍后再做</small></span><span aria-hidden="true">{open ? '−' : '＋'}</span></button>
    {open && <div className="cr-reflection-body"><label>我现在的理解<textarea maxLength={1200} rows={3} value={value.understanding} placeholder="例如：体积是把同样大小的小立方体一层层数出来。" onChange={event => setValue(previous => ({ ...previous, understanding: event.target.value }))} /></label><label>我想再核对的一个点<textarea maxLength={800} rows={2} value={value.checkPoint} placeholder="哪一步还不确定？准备回看哪一页或哪段资料？" onChange={event => setValue(previous => ({ ...previous, checkPoint: event.target.value }))} /></label><div className="cr-reflection-footer"><small>仅保存在当前浏览器；点击后先填写提问框，由你确认发送。</small><button className="cr-button secondary" disabled={!value.understanding.trim() && !value.checkPoint.trim()} onClick={() => onAskTeacher(reflectionQuestion(value, sceneTitle))}>请老师帮我核对</button></div></div>}
  </section>;
}
