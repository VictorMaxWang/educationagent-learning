import { API_BASE_URL } from '../api/baseUrl';
import type { ClassroomMessage, Course, Domain, ElementReference, Outline, QuizResult, Scene, Speaker, TeachingAction, WhiteboardState } from './types';

/** 课堂只走当前电脑后端，同一 Wi-Fi 下的手机不携带模型密钥。 */
export async function classroomRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}/api/classroom${path}`, {
    ...options,
    headers: options.body instanceof FormData ? options.headers : { 'Content-Type': 'application/json', ...options.headers },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    throw new Error(data.error || data.message || `请求失败（${response.status}）`);
  }
  return data as T;
}
const post = <T,>(path: string, body?: unknown, signal?: AbortSignal) => classroomRequest<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), signal });
export const listCourses = (signal?: AbortSignal) => classroomRequest<Course[]>('/courses', { signal });
export const getCourse = (id: string, signal?: AbortSignal) => classroomRequest<Course>(`/courses/${encodeURIComponent(id)}`, { signal });
/** 返回课堂先等位置写入完成，再读取课程和问答，避免用旧响应重建播放器。 */
export async function resumeCourse(id: string, pendingProgress: Promise<unknown>, signal?: AbortSignal): Promise<Course> {
  await pendingProgress;
  return getCourse(id, signal);
}
export const createCourse = (data: { topic: string; grade: number; subject: string; domain: Domain; sourceText?: string; sourceName?: string }) => post<Course>('/courses', data);
export const saveOutline = (id: string, outlines: Outline[]) => classroomRequest<Course>(`/courses/${encodeURIComponent(id)}/outline`, { method: 'PUT', body: JSON.stringify({ outlines }) });
export const generateCourse = (id: string) => post<Course>(`/courses/${encodeURIComponent(id)}/generate`);
export const cancelCourse = (id: string) => post<Course>(`/courses/${encodeURIComponent(id)}/cancel`);
export const saveProgress = (id: string, sceneIndex: number, actionIndex: number) => classroomRequest<unknown>(`/courses/${encodeURIComponent(id)}/progress`, { method: 'PUT', body: JSON.stringify({ sceneIndex, actionIndex }) });
export const submitAnswers = (id: string, sceneId: string, submissionId: string, answers: { no: number; answer: string }[]) => post<QuizResult>(`/courses/${encodeURIComponent(id)}/answer`, { sceneId, submissionId, answers });
export interface ClassroomQuestionOptions { elementReference?: ElementReference; interactiveState?: Record<string, unknown>; intent?: 'question' | 'edit' }
export const askClassroom = (id: string, sceneId: string, text: string, speaker: Speaker, signal?: AbortSignal, extra?: ClassroomQuestionOptions) => post<ClassroomMessage>(`/courses/${encodeURIComponent(id)}/chat`, { sceneId, text, speaker, ...extra }, signal);
/** 只保存指定页面的编辑结果，不能回写整份课程覆盖练习成绩与对话。 */
export const saveScene = (id: string, sceneId: string, expectedRevision: number, content: Scene['content'], actions?: TeachingAction[]) => classroomRequest<Course>(`/courses/${encodeURIComponent(id)}/scenes/${encodeURIComponent(sceneId)}`, { method: 'PUT', body: JSON.stringify({ expectedRevision, content, actions }) });
export interface RuntimeUpdate { expectedRevision?: number; operationId: string; sceneId: string; whiteboard?: WhiteboardState; interactiveState?: Record<string, unknown>; acknowledgedActionIds?: string[]; actionResults?: { actionId: string; success: boolean; error?: string; at?: string | number }[] }
export const saveRuntime = (id: string, data: RuntimeUpdate) => classroomRequest<Course>(`/courses/${encodeURIComponent(id)}/runtime`, { method: 'PUT', body: JSON.stringify(data) });
export const portableCourse = (id: string) => classroomRequest<{ format: 'educationagent.classroom'; version: 1; course: Course }>(`/courses/${encodeURIComponent(id)}/portable`);
export const importCourse = (data: unknown) => post<Course>('/courses/import', data);
export async function uploadMaterial(file: File) {
  const form = new FormData();
  form.append('file', file);
  return classroomRequest<{ text: string; fileName: string; truncated?: boolean }>('/materials', { method: 'POST', body: form });
}
export const voiceHealth = () => classroomRequest<{ ttsAvailable: boolean; asrAvailable: boolean }>('/voice/health');
/** 按句切分长讲稿，保留全部字符与顺序，遵守语音端点每次 2000 字上限。 */
export function splitNarrationText(text: string, limit = 2000): string[] {
  const parts: string[] = [];
  let remaining = text;
  while (remaining.length > limit) {
    const candidate = remaining.slice(0, limit);
    const breaks = [...candidate.matchAll(/[。！？；\n.!?;]/g)];
    let end = breaks.length && breaks[breaks.length - 1].index! > limit / 3 ? breaks[breaks.length - 1].index! + 1 : limit;
    // 不把 UTF-16 代理对分在两个请求里。
    const last = remaining.charCodeAt(end - 1);
    if (last >= 0xd800 && last <= 0xdbff) end--;
    parts.push(remaining.slice(0, end)); remaining = remaining.slice(end);
  }
  if (remaining) parts.push(remaining);
  return parts;
}
export async function synthesizeSpeech(text: string, role: Speaker, signal?: AbortSignal) {
  const segments: { text: string; url: string }[] = [];
  for (const part of splitNarrationText(text)) {
    const response = await post<{ segments: { text: string; url: string }[] }>('/voice/tts', { text: part, role }, signal);
    segments.push(...response.segments);
  }
  return { segments };
}
export async function transcribeAudio(file: Blob, signal?: AbortSignal) {
  const form = new FormData();
  form.append('file', file, 'question.wav');
  return classroomRequest<{ text: string }>('/voice/asr', { method: 'POST', body: form, signal });
}
export const wechatStatus = () => classroomRequest<{ bound: boolean; baseUrl: string }>('/wechat/status');
export const pairWechat = () => post<{ code: string; expiresInSeconds: number }>('/wechat/pairing', { baseUrl: window.location.origin });
export function audioUrl(path: string) {
  return /^https?:\/\//.test(path) ? path : `${API_BASE_URL}${path}`;
}
