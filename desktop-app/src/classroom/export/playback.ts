import type { TeachingAction } from '../types';
import { checkAbort } from './contract';

export interface OfflinePlaybackPorts {
  caption(text: string): void;
  position(nextAction: number): void;
  hasAudio(index: number): boolean;
  speech(index: number, signal: AbortSignal): Promise<void>;
  control(action: TeachingAction, signal: AbortSignal): Promise<void>;
  visual(action: TeachingAction): void;
  warning(message: string): void;
}
export type OfflinePlaybackStop = { nextAction: number; reason: 'done' | 'discussion' | 'text' };

/** 游标属于已有讲授动作：讨论或无配音的文字停顿后从下一项继续，不重新从第一页讲稿开头循环。 */
export async function playOfflineActions(actions: TeachingAction[], start: number, ports: OfflinePlaybackPorts, signal: AbortSignal): Promise<OfflinePlaybackStop> {
  for (let index = Math.max(0, start); index < actions.length; index++) {
    checkAbort(signal);
    ports.position(index);
    const action = actions[index];
    if (action.type === 'discussion') {
      ports.caption(String(action.prompt || action.topic || action.text || action.content || '停下来，自己想一想。'));
      ports.position(index + 1);
      return { nextAction: index + 1, reason: 'discussion' };
    }
    if (action.type === 'speech') {
      ports.caption(String(action.text || action.content || ''));
      if (!ports.hasAudio(index)) {
        ports.position(index + 1);
        return { nextAction: index + 1, reason: 'text' };
      }
      await ports.speech(index, signal);
    } else if (action.type.startsWith('wb_') || action.type.startsWith('widget_')) {
      await ports.control(action, signal);
    } else if (action.type === 'spotlight' || action.type === 'laser') {
      ports.visual(action);
    } else {
      ports.warning(`这份离线课件暂不支持动作 ${action.type}，请参考课件正文或回到在线课堂。`);
    }
    checkAbort(signal);
    ports.position(index + 1);
  }
  return { nextAction: actions.length, reason: 'done' };
}
