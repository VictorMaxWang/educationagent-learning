import type { Speaker, TeachingAction } from './types';

export interface VoiceReply {
  text: string;
  role: Speaker;
  actions?: TeachingAction[];
  executeAction?(action: TeachingAction, signal: AbortSignal): Promise<void>;
}
export interface VoiceContext {
  id: string;
  label: string;
  grade: number;
  kind: 'classroom' | 'growth';
  ask(question: string, signal: AbortSignal): Promise<VoiceReply>;
}

let active: VoiceContext | null = null;
const listeners = new Set<(context: VoiceContext | null) => void>();
export const currentVoiceContext = () => active;
export function subscribeVoiceContext(listener: (context: VoiceContext | null) => void) {
  listeners.add(listener); return () => { listeners.delete(listener); };
}
/** 页面只注册自己的提问入口，语音球不另造一份课程或成长对话业务。 */
export function registerVoiceContext(context: VoiceContext): () => void {
  active = context; for (const listener of listeners) listener(active);
  return () => {
    if (active !== context) return;
    active = null; for (const listener of listeners) listener(active);
  };
}
