import type { Course, Question, Scene } from '../types';
import { portableSupplementaryResources } from './supplementary';

/** 离线练习明确携带标准答案；在线课堂的普通 Course 响应不包含这些字段。 */
export interface PortableQuestion extends Question {
  answer?: string | number | boolean | string[];
  correctAnswer?: string | number | boolean | string[];
  explanation?: string;
  analysis?: string;
  gradingPoints?: string[];
}
export type PortableScene = Omit<Scene, 'content'> & {
  content: { type: Scene['type']; canvas?: import('@educationagent/dsl').Slide; questions?: PortableQuestion[]; html?: string; [key: string]: unknown };
  whiteboards?: import('@educationagent/dsl').Slide[];
};
export type PortableCourse = Omit<Course, 'scenes' | 'runtime'> & {
  scenes: PortableScene[];
  revision?: number;
  runtime?: Record<string, { whiteboard?: { id: string; canvas: import('@educationagent/dsl').Slide; isOpen: boolean } }>;
};
export interface PortableEnvelope {
  format: 'educationagent.classroom';
  version: 1;
  course: PortableCourse;
}
export interface ExportProgress { phase: string; completed: number; total: number }
export interface ExportOptions {
  format: 'pptx' | 'html' | 'zip';
  includeNarration: boolean;
  signal?: AbortSignal;
  onProgress?: (progress: ExportProgress) => void;
}
export interface ExportResult { blob: Blob; fileName: string; warnings: string[] }
export interface NarrationSegment { text: string; asset: string }
export interface ExportSnapshot {
  envelope: PortableEnvelope;
  /** ZIP 中的相对路径及真实字节；绝不把服务器 URL 伪装成离线资源。 */
  assets: Map<string, Blob>;
  narration: Record<string, Record<string, NarrationSegment[]>>;
  warnings: string[];
}
export interface PlayerData {
  envelope: PortableEnvelope;
  narration: Record<string, Record<string, { text: string; src: string }[]>>;
  warnings: string[];
}
export const EXPORT_MAX_BYTES = 80 * 1024 * 1024;
export const EXPORT_ASSET_MAX_BYTES = 12 * 1024 * 1024;
export const PORTABLE_MAX_JSON_BYTES = 20 * 1024 * 1024;

export function checkAbort(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('导出已取消', 'AbortError');
}
export function safeFilename(title: string): string {
  return (title || '学习课堂').replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').replace(/[ .]+$/g, '').slice(0, 80) || '学习课堂';
}
/** 对导入及后端快照做边界检查，后端会继续重新验证完整业务模型。 */
export function validateEnvelope(value: unknown): PortableEnvelope {
  if (!value || typeof value !== 'object') throw new Error('课程文件必须是 JSON 对象');
  const envelope = value as Record<string, unknown>;
  if (envelope.format !== 'educationagent.classroom' || envelope.version !== 1) throw new Error('不支持的课程文件格式或版本');
  const course = envelope.course as PortableCourse | undefined;
  if (!course || typeof course.title !== 'string' || !Number.isInteger(course.grade) || course.grade < 1 || course.grade > 12 || !Array.isArray(course.scenes) || course.scenes.length === 0 || course.scenes.length > 64) throw new Error('课程标题、年级或页面列表无效');
  if (course.scenes.some(scene => !scene || !['slide', 'quiz', 'interactive'].includes(scene.type) || typeof scene.id !== 'string' || !scene.content)) throw new Error('课程包含无效页面');
  if (course.supplementaryResources !== undefined) course.supplementaryResources = portableSupplementaryResources(course.supplementaryResources, new Set(course.scenes.map(scene => scene.id)));
  return envelope as unknown as PortableEnvelope;
}
