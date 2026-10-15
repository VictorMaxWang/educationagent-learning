export interface AudioLease {
  readonly owner: string;
  isCurrent(): boolean;
  release(): void;
}
export interface AudioFocusSnapshot {
  playbackOwner: string | null;
  microphoneOwner: string | null;
  microphoneBlocked: boolean;
}
type Focus = { owner: string; token: number; cancel(): void };

/**
 * 全应用只有一份声音焦点。录音优先，播放可替换旧播放，但不抢占另一处正在录制的麦克风。
 * 释放按 token 校验，旧请求的 finally 不会误释放后来页面取得的新焦点。
 */
export class AudioCoordinator {
  private sequence = 0;
  private playback: Focus | null = null;
  private microphone: Focus | null = null;
  private guards = new Set<string>();
  private listeners = new Set<(snapshot: AudioFocusSnapshot) => void>();
  private managed = new WeakSet<object>();
  snapshot(): AudioFocusSnapshot {
    return { playbackOwner: this.playback?.owner || null, microphoneOwner: this.microphone?.owner || null, microphoneBlocked: Boolean(this.microphone || this.guards.size) };
  }
  subscribe(listener: (snapshot: AudioFocusSnapshot) => void): () => void {
    this.listeners.add(listener); return () => { this.listeners.delete(listener); };
  }
  private notify() { const current = this.snapshot(); for (const listener of this.listeners) listener(current); }
  markManagedMedia(element: object) { this.managed.add(element); }
  isManagedMedia(element: object) { return this.managed.has(element); }
  private release(kind: 'playback' | 'microphone', token: number) {
    if (this[kind]?.token !== token) return;
    this[kind] = null; this.notify();
  }
  private lease(kind: 'playback' | 'microphone', focus: Focus): AudioLease {
    return { owner: focus.owner, isCurrent: () => this[kind]?.token === focus.token, release: () => this.release(kind, focus.token) };
  }
  acquirePlayback(owner: string, cancel: () => void): AudioLease | null {
    if (this.microphone || this.guards.size) return null;
    const previous = this.playback;
    const next = { owner, token: ++this.sequence, cancel };
    this.playback = next;
    previous?.cancel(); this.notify();
    return this.lease('playback', next);
  }
  acquireMicrophone(owner: string, cancel: () => void): AudioLease | null {
    if (this.microphone) return null;
    const next = { owner, token: ++this.sequence, cancel };
    this.microphone = next;
    const previous = this.playback; this.playback = null;
    previous?.cancel(); this.notify();
    return this.lease('microphone', next);
  }
  setMicrophoneGuard(owner: string, busy: boolean) {
    if (busy) this.guards.add(owner); else this.guards.delete(owner);
    if (busy && this.playback) { const previous = this.playback; this.playback = null; previous.cancel(); }
    this.notify();
  }
  stopAll() {
    const playback = this.playback, microphone = this.microphone;
    this.playback = null; this.microphone = null; this.guards.clear();
    playback?.cancel(); microphone?.cancel(); this.notify();
  }
}

export const audioCoordinator = new AudioCoordinator();
let ownerSequence = 0;
export function audioOwner(prefix: string) { return `${prefix}-${++ownerSequence}`; }

/** 让旧版音乐/听力的原生媒体元素也参与互斥，不修改它们的播放偏好或音乐列表。 */
export function installMediaFocusBridge(doc: Document): () => void {
  const leases = new Map<HTMLMediaElement, AudioLease>();
  const release = (event: Event) => {
    const media = event.target;
    if (!(media instanceof HTMLMediaElement)) return;
    leases.get(media)?.release(); leases.delete(media);
  };
  const onPlay = (event: Event) => {
    const media = event.target;
    if (!(media instanceof HTMLMediaElement) || audioCoordinator.isManagedMedia(media)) return;
    if (leases.get(media)?.isCurrent()) return;
    const lease = audioCoordinator.acquirePlayback(audioOwner('media'), () => media.pause());
    if (!lease) { media.pause(); return; }
    leases.set(media, lease);
  };
  const unsubscribe = audioCoordinator.subscribe(snapshot => {
    if (snapshot.microphoneBlocked || snapshot.playbackOwner) {
      // 旧口语页使用浏览器朗读，新的录音/课堂播放开始时也应停止它。
      doc.defaultView?.speechSynthesis?.cancel();
    }
  });
  doc.addEventListener('play', onPlay, true);
  doc.addEventListener('pause', release, true);
  doc.addEventListener('ended', release, true);
  return () => {
    unsubscribe(); doc.removeEventListener('play', onPlay, true); doc.removeEventListener('pause', release, true); doc.removeEventListener('ended', release, true);
    for (const lease of leases.values()) lease.release(); leases.clear();
  };
}
