import { useState } from 'react';
import type { Outline, SceneType } from './types';
import { TYPE_LABELS } from './types';
import { newLocalId } from './navigation';

export default function OutlineEditor({ initial, busy, onConfirm }: {
  initial: Outline[]; busy: boolean; onConfirm(outlines: Outline[]): Promise<void>;
}) {
  const [outlines, setOutlines] = useState<Outline[]>(initial);
  const [error, setError] = useState('');
  const update = (index: number, changes: Partial<Outline>) => setOutlines(previous => previous.map((outline, i) => i === index ? { ...outline, ...changes } : outline));
  const move = (index: number, direction: number) => setOutlines(previous => {
    const next = [...previous];
    [next[index], next[index + direction]] = [next[index + direction], next[index]];
    return next;
  });
  const confirm = async () => {
    if (outlines.some(outline => !outline.title.trim())) { setError('每一步都需要一个标题。'); return; }
    setError('');
    try { await onConfirm(outlines.map((outline, order) => ({ ...outline, title: outline.title.trim(), order }))); }
    catch (error) { setError(error instanceof Error ? error.message : '保存大纲失败'); }
  };
  return <section className="cr-outline-editor" aria-labelledby="outline-title">
    <div className="cr-section-heading"><div><p className="cr-eyebrow">先看看这次怎么学</p><h2 id="outline-title">调整你的学习大纲</h2><p>可以修改标题、安排顺序。确认后，老师会逐页准备讲解与练习。</p></div><span className="cr-pill">{outlines.length} 个学习步骤</span></div>
    <ol className="cr-outline-list">{outlines.map((outline, index) => <li key={outline.id} className="cr-outline-row">
      <span className="cr-step-number">{index + 1}</span><div className="cr-outline-fields">
        <label>步骤标题<input value={outline.title} maxLength={120} disabled={busy} onChange={event => update(index, { title: event.target.value })} /></label>
        <label>想讲清楚什么<textarea rows={2} value={outline.description || ''} maxLength={1200} disabled={busy} onChange={event => update(index, { description: event.target.value })} /></label>
      </div>
      <div className="cr-outline-tools"><label>学习形式<select value={outline.type} disabled={busy} onChange={event => update(index, { type: event.target.value as SceneType })}>{Object.entries(TYPE_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <div className="cr-inline"><button className="cr-icon" aria-label={`上移第${index + 1}步`} disabled={busy || index === 0} onClick={() => move(index, -1)}>↑</button><button className="cr-icon" aria-label={`下移第${index + 1}步`} disabled={busy || index === outlines.length - 1} onClick={() => move(index, 1)}>↓</button><button className="cr-link" disabled={busy || outlines.length <= 4} onClick={() => setOutlines(previous => previous.filter((_, i) => i !== index))}>移除</button></div>
      </div>
    </li>)}</ol>
    {error && <p className="cr-error" role="alert">{error}</p>}
    <div className="cr-actions"><button className="cr-button secondary" disabled={busy || outlines.length >= 8} onClick={() => setOutlines(previous => [...previous, { id: newLocalId(), title: '新的学习步骤', description: '', type: 'slide', order: previous.length }])}>＋ 添加一步</button><button className="cr-button" disabled={busy} onClick={() => void confirm()}>{busy ? '正在保存…' : '确认大纲，准备课堂 →'}</button></div>
  </section>;
}
