import type { Speaker, TeachingAction } from './types';

export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'discussion' | 'done' | 'error';
export interface NarrationPorts {
  synthesize(text: string, role: Speaker, signal: AbortSignal): Promise<{ segments: { text: string; url: string }[] }>;
  resolveUrl(url: string): string;
  createAudio?: () => HTMLAudioElement;
  onStatus(status: PlaybackStatus): void;
  onCaption(text: string, role: Speaker): void;
  onAction(index: number, action: TeachingAction): void;
  onDiscussion?(text: string, nextActionIndex: number): void;
  onError(message: string): void;
}

/**
 * 每个课堂复用一个 audio 元素，避免手机把第二段旁白当成新的自动播放。
 * run 序号和 AbortSignal 双重隔离旧请求：切换课程或插话后，迟到的 TTS 不会继续发声。
 */
export class NarrationQueue {
  private audio: HTMLAudioElement | null = null;
  private controller: AbortController | null = null;
  private run = 0;
  private paused = false;
  private resumeWaiters: (() => void)[] = [];
  private rate = 1;
  private segmentActive = false;
  private microphoneBusy = false;
  constructor(private readonly ports: NarrationPorts) {}

  /** 授权等待也占用录音锁；即使按钮尚未重绘，任何播放入口都不能向麦克风回灌旁白。 */
  setMicrophoneBusy(busy: boolean) {
    this.microphoneBusy = busy;
    if (busy) this.stop();
  }

  setRate(rate: number) {
    this.rate = rate;
    if (this.audio) this.audio.playbackRate = rate;
  }
  pause() {
    if (!this.controller) return;
    this.paused = true;
    this.audio?.pause();
    this.ports.onStatus('paused');
  }
  resume() {
    if (this.microphoneBusy || !this.controller || !this.paused) return;
    this.paused = false;
    this.resumeWaiters.splice(0).forEach(resolve => resolve());
    if (this.segmentActive && this.audio?.getAttribute('src')) {
      const run = this.run;
      this.audio.play().catch(error => { if (run === this.run) this.fail(error); });
    }
    this.ports.onStatus('playing');
  }
  stop(notify = true) {
    this.run++;
    this.controller?.abort();
    this.controller = null;
    this.paused = false;
    this.segmentActive = false;
    this.resumeWaiters.splice(0).forEach(resolve => resolve());
    if (this.audio) {
      this.audio.pause();
      this.audio.removeAttribute('src');
      this.audio.load();
    }
    if (notify) this.ports.onStatus('idle');
  }
  private fail(error: unknown) {
    this.stop(false);
    this.ports.onStatus('error');
    this.ports.onError(error instanceof Error && error.name === 'NotAllowedError'
      ? '浏览器暂停了声音，请再点一次“开始听课”。也可以直接阅读下方讲解。'
      : error instanceof Error ? error.message : '语音播放失败，可以继续阅读或重新播放。');
  }
  private async waitForResume(signal: AbortSignal) {
    if (this.paused && !signal.aborted) await new Promise<void>(resolve => this.resumeWaiters.push(resolve));
  }
  private playSegment(url: string, signal: AbortSignal): Promise<void> {
    const audio = this.audio!;
    const run = this.run;
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        if (run === this.run) this.segmentActive = false;
        audio.removeEventListener('ended', onEnded);
        audio.removeEventListener('error', onError);
        signal.removeEventListener('abort', onAbort);
      };
      const onEnded = () => { cleanup(); resolve(); };
      const onError = () => { cleanup(); reject(new Error('音频暂时无法播放，请重试。')); };
      const onAbort = () => { cleanup(); resolve(); };
      if (signal.aborted) { resolve(); return; }
      this.segmentActive = true;
      audio.addEventListener('ended', onEnded, { once: true });
      audio.addEventListener('error', onError, { once: true });
      signal.addEventListener('abort', onAbort, { once: true });
      audio.src = this.ports.resolveUrl(url);
      audio.playbackRate = this.rate;
      audio.play().catch(error => { cleanup(); reject(error); });
    });
  }
  async play(actions: TeachingAction[], startIndex = 0, voiceRole: Speaker = 'teacher', trackActions = true) {
    if (this.microphoneBusy) return;
    this.stop(false);
    const run = this.run;
    const controller = new AbortController();
    this.controller = controller;
    const { signal } = controller;
    if (!this.audio) this.audio = this.ports.createAudio?.() ?? new Audio();
    // 点击时先触达同一个媒体元素；后续分段切换不再新建 audio。
    this.audio.src = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=';
    void this.audio.play().catch(() => {});
    this.ports.onError('');
    try {
      for (let index = Math.max(0, startIndex); index < actions.length; index++) {
        await this.waitForResume(signal);
        if (run !== this.run || signal.aborted) return;
        const action = actions[index];
        if (trackActions) this.ports.onAction(index, action);
        if (action.type !== 'speech' && action.type !== 'discussion') continue;
        const text = String(action.text || action.prompt || action.topic || action.content || '').trim();
        if (!text) continue;
        if (action.type === 'discussion') {
          this.ports.onCaption(text, 'teacher');
          this.ports.onDiscussion?.(text, index + 1);
          this.controller = null;
          this.ports.onStatus('discussion');
          return;
        }
        const role = voiceRole;
        this.ports.onCaption(text, role);
        this.ports.onStatus('loading');
        const result = await this.ports.synthesize(text, role, signal);
        if (run !== this.run || signal.aborted) return;
        if (!result.segments.length) throw new Error('这段讲解尚未生成声音，可以阅读文字或重新播放。');
        for (const segment of result.segments) {
          await this.waitForResume(signal);
          if (run !== this.run || signal.aborted) return;
          this.ports.onCaption(segment.text || text, role);
          this.ports.onStatus('playing');
          await this.playSegment(segment.url, signal);
        }
      }
      if (run === this.run && !signal.aborted) {
        this.controller = null;
        this.ports.onStatus('done');
      }
    } catch (error) {
      if (run === this.run && !signal.aborted) this.fail(error);
    }
  }
  /** 真正的老师/学伴回答共用同一队列，朗读回答不覆盖课堂讲稿的进度。 */
  readResponse(text: string, role: Speaker) { return this.play([{ type: 'speech', text }], 0, role, false); }
  destroy() { this.stop(false); this.audio = null; this.microphoneBusy = false; }
}
