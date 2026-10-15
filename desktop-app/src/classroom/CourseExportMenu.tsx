import { useEffect, useRef, useState } from 'react';
import type { Course } from './types';
import { downloadExport, exportCourse, videoExportCapability, type ExportOptions, type ExportProgress } from './export';
import './export/menu.css';

export default function CourseExportMenu({ course, disabled = false }: { course: Course; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const [includeNarration, setIncludeNarration] = useState(false);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<ExportProgress | null>(null);
  const [error, setError] = useState('');
  const [warnings, setWarnings] = useState<string[]>([]);
  const [saved, setSaved] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const run = async (format: ExportOptions['format']) => {
    if (busy) return;
    const job = new AbortController(); controller.current = job;
    setBusy(true); setError(''); setWarnings([]); setSaved(''); setProgress(null);
    try {
      const result = await exportCourse(course, { format, includeNarration, signal: job.signal, onProgress: setProgress });
      if (job.signal.aborted) return;
      downloadExport(result); setWarnings(result.warnings); setSaved(result.fileName);
    } catch (caught) {
      if (!job.signal.aborted) setError(caught instanceof Error ? caught.message : '导出失败，请稍后重试');
    } finally { if (controller.current === job) { controller.current = null; setBusy(false); } }
  };
  return <div className="cr-export"><button className="cr-button secondary" onClick={() => setOpen(value => !value)} disabled={disabled || course.scenes.length === 0} aria-expanded={open}>导出课程</button>{open && <div className="cr-export-panel">
    <strong>保存一份可以带走的课堂</strong><p>HTML 可直接打开；ZIP 解压后播放，也能导回新课程；PPTX 保留可编辑文字、图表和讲稿备注。</p>
    <label className="cr-export-option"><input type="checkbox" checked={includeNarration} disabled={busy} onChange={event => setIncludeNarration(event.target.checked)} />HTML / ZIP 附加老师配音（可能调用语音服务，耗时更长）</label>
    <div className="cr-export-actions"><button disabled={busy} onClick={() => void run('pptx')}>PowerPoint</button><button disabled={busy} onClick={() => void run('html')}>独立 HTML</button><button disabled={busy} onClick={() => void run('zip')}>课堂 ZIP</button></div>
    {busy && <div role="status"><p>{progress?.phase || '准备导出'}{progress ? ` · ${progress.completed}/${progress.total}` : ''}</p><button onClick={() => controller.current?.abort()}>取消本次导出</button></div>}
    {saved && <p className="cr-export-success" role="status">已准备下载：{saved}</p>}{error && <p className="cr-error" role="alert">{error}</p>}
    {warnings.length > 0 && <details open><summary>导出内容提示</summary><ul>{warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
    <small>{videoExportCapability().reason}</small>
  </div>}</div>;
}
