import { useEffect, useRef, useState } from 'react';
import type { Course } from './types';
import { importCourseFile } from './export/importCourse';
import './export/menu.css';

export default function CourseImportButton({ onImported, disabled = false }: { onImported: (course: Course) => void; disabled?: boolean }) {
  const input = useRef<HTMLInputElement>(null);
  const request = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => () => request.current?.abort(), []);
  const read = async (file?: File) => {
    if (!file || busy) return;
    setBusy(true); setError('');
    const controller = new AbortController(); request.current = controller;
    try {
      const course = await importCourseFile(file, controller.signal);
      if (!controller.signal.aborted) onImported(course);
    } catch (caught) { if (!controller.signal.aborted) setError(caught instanceof Error ? caught.message : '课程导入失败'); }
    finally { if (request.current === controller) { request.current = null; setBusy(false); } if (input.current) input.current.value = ''; }
  };
  return <div className="cr-import"><input ref={input} type="file" accept=".json,.zip,application/json,application/zip" hidden onChange={event => void read(event.target.files?.[0])} /><button className="cr-button secondary" disabled={busy || disabled} onClick={() => input.current?.click()}>{busy ? '正在导入…' : '导入课程'}</button>{error && <p className="cr-error" role="alert">{error}</p>}</div>;
}
