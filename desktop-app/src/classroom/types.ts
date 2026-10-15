import type { Slide } from '@educationagent/dsl';

/** 引用仅携带身份；对象正文由后端从当前课程解析，避免旧页面或客户端快照覆盖课程。 */
export type ElementReference =
  | { kind: 'slide_element'; sceneId: string; elementId: string }
  | { kind: 'interactive_component'; sceneId: string; selector: string }
  | { kind: 'whiteboard_element'; sceneId: string; whiteboardId: string; elementId: string };
export interface WhiteboardState { id: string; canvas: Slide; isOpen: boolean }
export interface SceneRuntime {
  whiteboard?: WhiteboardState;
  interactiveState?: Record<string, unknown>;
  acknowledgedActionIds?: string[];
  actionResults?: { actionId: string; success: boolean; error?: string; at?: string | number }[];
}
export type CourseRuntime = Record<string, SceneRuntime>;

export type SceneType = 'slide' | 'quiz' | 'interactive';
export type Domain = 'general' | 'psychology' | 'lifesafety' | 'sexedu';
export type CourseStatus = 'outlining' | 'outline_ready' | 'generating' | 'ready' | 'error' | 'cancelled';
export type Speaker = 'teacher' | 'peer';
export interface Outline {
  id: string;
  title: string;
  type: SceneType;
  order: number;
  description?: string;
  [key: string]: unknown;
}
export interface Question {
  id: string;
  no: number;
  type: 'single' | 'multiple';
  question: string;
  options: { label: string; value: string }[];
  hasAnswer?: boolean;
}
export interface TeachingAction {
  id?: string;
  type: 'speech' | 'discussion' | 'spotlight' | 'laser'
    | 'widget_setState' | 'widget_highlight' | 'widget_annotation' | 'widget_reveal'
    | 'wb_open' | 'wb_close' | 'wb_draw_text' | 'wb_draw_shape' | 'wb_draw_latex'
    | 'wb_draw_table' | 'wb_draw_chart' | 'wb_draw_line' | 'wb_clear' | 'wb_delete'
    | 'wb_draw_code' | 'wb_edit_code';
  text?: string;
  content?: string;
  prompt?: string;
  elementId?: string;
  [key: string]: unknown;
}
export interface Scene {
  id: string;
  title: string;
  order: number;
  type: SceneType;
  quizId?: string;
  content: { type: SceneType; canvas?: Slide; questions?: Question[]; html?: string; widgetType?: string; [key: string]: unknown };
  actions: TeachingAction[];
}
export interface ClassroomMessage {
  id: string;
  speaker: Speaker | 'user';
  text: string;
  sceneId?: string;
  elementReference?: ElementReference;
  actions?: TeachingAction[];
  updatedScene?: Scene;
  revision?: number;
}
export interface Course {
  id: string;
  title: string;
  topic: string;
  grade: number;
  subject: string;
  domain: Domain;
  status: CourseStatus;
  outlines: Outline[];
  scenes: Scene[];
  progress: { sceneIndex: number; actionIndex: number };
  messages: ClassroomMessage[];
  revision?: number;
  runtime?: CourseRuntime;
  mediaEnabled?: boolean;
  error?: string;
  sourceName?: string;
  sources?: { file?: string; source?: string }[];
  retrievalNote?: string;
  createdAt?: string;
  updatedAt?: string;
}
export interface QuizResult {
  quizId: string;
  score: number;
  total: number;
  results: { no: number; correct: boolean; correctAnswer: string; userAnswer: string; explanation: string }[];
}
export const GRADE_LABELS = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三', '高一', '高二', '高三'];
export const TYPE_LABELS: Record<SceneType, string> = { slide: '讲解', quiz: '练一练', interactive: '动手探索' };
export const STATUS_LABELS: Record<CourseStatus, string> = {
  outlining: '正在准备大纲', outline_ready: '确认学习大纲', generating: '正在准备课堂', ready: '可以开始学习', error: '需要重试', cancelled: '已停止生成',
};
