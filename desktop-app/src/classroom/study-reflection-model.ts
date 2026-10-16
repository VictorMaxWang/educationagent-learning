import type { LearningAttempt, LearningHistory } from './study-types';
export interface StudyReflection { understanding: string; checkPoint: string }
export const EMPTY_REFLECTION: StudyReflection = { understanding: '', checkPoint: '' };
export function reflectionKey(courseId: string, sceneId: string, revision: number): string {
  return `educationagent.reflection.v1:${courseId}:${sceneId}:${revision}`;
}
export function restoreReflection(raw: string | null): StudyReflection {
  try {
    const value: unknown = JSON.parse(raw || 'null');
    if (!value || typeof value !== 'object') return EMPTY_REFLECTION;
    const record = value as Record<string, unknown>;
    return { understanding: typeof record.understanding === 'string' ? record.understanding.slice(0, 1200) : '', checkPoint: typeof record.checkPoint === 'string' ? record.checkPoint.slice(0, 800) : '' };
  } catch { return EMPTY_REFLECTION; }
}
export function reflectionQuestion(value: StudyReflection, title: string): string {
  return `关于“${title.slice(0, 160)}”，请先帮我核对理解，再给一点提示。\n我的理解：${value.understanding.trim().slice(0, 1000) || '我还没有想清楚'}\n我想核对：${value.checkPoint.trim().slice(0, 500) || '请帮我指出一个需要核对的地方'}\n请区分已有资料支持的内容和需要我继续确认的内容，不要直接替我完成整道题。`;
}
export function currentLearningHistory(history: LearningHistory, courseId: string, sceneId: string): LearningAttempt[] {
  return history.attempts.filter(item => item.courseId === courseId && item.sceneId === sceneId && item.contentFingerprint === history.contentFingerprint && item.status !== 'stale').slice(0, 10);
}
