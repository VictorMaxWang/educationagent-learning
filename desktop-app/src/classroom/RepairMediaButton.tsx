import { useState } from 'react';
import { retrySceneMedia } from './api';
import type { Course, Scene } from './types';

/** 只补充当前修订上缺失的插图；服务端版本冲突时保留正在学习的课程。 */
export default function RepairMediaButton({ course, scene, onCourseChange }: { course: Course; scene: Scene; onCourseChange(course: Course): void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (!scene.mediaRetryAvailable) return null;
  return <div className="cr-media-repair"><button className="cr-button secondary" disabled={busy} onClick={async () => {
    setBusy(true); setError('');
    try { onCourseChange(await retrySceneMedia(course.id, scene.id, course.revision || 0)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '插图暂未补齐，可以稍后再试'); }
    finally { setBusy(false); }
  }}>{busy ? '正在补充插图…' : '补充本页插图'}</button>{error && <small role="status">{error}</small>}</div>;
}
