/**
 * 课堂按键录音：浏览器原生采样 → 单声道 16k/16bit WAV。
 * 不使用 MediaRecorder 默认 webm，因为当前一句话识别接口不接受 webm。
 */
import { audioCoordinator, audioOwner, type AudioLease } from './AudioCoordinator.ts';

export interface ClassroomRecordingOptions {
  maxDurationSeconds?: number;
  onDuration?: (seconds: number) => void;
  onAutoStop?: (blob: Blob) => void;
  onError?: (error: Error) => void;
  signal?: AbortSignal;
}

export interface ClassroomRecording {
  stop(): Promise<Blob>;
  cancel(): void;
}

export function canRecordClassroomAudio(): boolean {
  return typeof window !== "undefined" && window.isSecureContext
    && typeof navigator.mediaDevices?.getUserMedia === "function";
}

/** 纯转换函数，暴露给离线单元测试，不访问麦克风或网络。 */
export function encodeClassroomWav(samples: Float32Array, sourceRate: number, maxDurationSeconds = 60): Blob {
  if (!Number.isFinite(sourceRate) || sourceRate <= 0 || !Number.isFinite(maxDurationSeconds) || samples.length === 0) {
    throw new Error("没有录到声音，请重新录音");
  }
  const targetRate = 16000;
  const limit = Math.min(60, Math.max(1, maxDurationSeconds));
  const count = Math.min(Math.floor(samples.length * targetRate / sourceRate), Math.floor(limit * targetRate));
  if (count === 0) throw new Error("录音太短，请重新录音");
  const buffer = new ArrayBuffer(44 + count * 2);
  const view = new DataView(buffer);
  const writeTag = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i));
  };
  writeTag(0, "RIFF"); view.setUint32(4, buffer.byteLength - 8, true); writeTag(8, "WAVE");
  writeTag(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, targetRate, true);
  view.setUint32(28, targetRate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  writeTag(36, "data"); view.setUint32(40, count * 2, true);
  const ratio = sourceRate / targetRate;
  for (let i = 0; i < count; i++) {
    const start = i * ratio;
    let sample: number;
    if (ratio >= 1) {
      // 采样区间求平均，避免简单抽点让高频噪声直接折叠到普通话频段。
      const end = Math.min((i + 1) * ratio, samples.length);
      let weighted = 0;
      for (let n = Math.floor(start); n < Math.ceil(end); n++) {
        weighted += samples[n] * (Math.min(n + 1, end) - Math.max(n, start));
      }
      sample = weighted / (end - start);
    } else {
      const left = Math.floor(start);
      const right = Math.min(left + 1, samples.length - 1);
      sample = samples[left] + (samples[right] - samples[left]) * (start - left);
    }
    const clipped = Math.max(-1, Math.min(1, Number.isFinite(sample) ? sample : 0));
    view.setInt16(44 + i * 2, Math.round(clipped * (clipped < 0 ? 32768 : 32767)), true);
  }
  return new Blob([buffer], { type: "audio/wav" });
}

/**
 * 在用户点击事件内调用。HTTP 局域网页面没有麦克风权限，应保留文字/WAV 上传入口。
 * 页签切换、组件卸载时必须 cancel；若授权弹窗期间已切页，拿到返回值后也应立即 cancel。
 */
export async function startClassroomRecording(options: ClassroomRecordingOptions = {}): Promise<ClassroomRecording> {
  if (!canRecordClassroomAudio()) throw new Error("当前页面无法使用麦克风，请使用 HTTPS、桌面端，或上传 WAV/输入文字");
  const requestedLimit = options.maxDurationSeconds ?? 60;
  if (!Number.isFinite(requestedLimit)) throw new Error("录音时长设置无效");
  const limit = Math.min(60, Math.max(1, requestedLimit));
  let stream: MediaStream | null = null;
  let stoppedStream: MediaStream | null = null;
  let cancelled = false;
  let lease: AudioLease | null = null;
  let captureCleanup: (() => void) | undefined;
  const release = () => {
    options.signal?.removeEventListener('abort', abort);
    if (stream && stoppedStream !== stream) { stream.getTracks().forEach(track => track.stop()); stoppedStream = stream; }
    lease?.release(); lease = null;
  };
  const abort = () => { cancelled = true; captureCleanup?.(); release(); };
  lease = audioCoordinator.acquireMicrophone(audioOwner('microphone'), () => {
    abort(); options.onError?.(new Error('录音已停止，可以重新录音或继续输入文字。'));
  });
  if (!lease) throw new Error('另一处正在使用麦克风，请先完成或取消那段录音。');
  if (options.signal?.aborted) { abort(); throw new Error('录音已取消'); }
  options.signal?.addEventListener('abort', abort, { once: true });
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
    if (cancelled || !lease?.isCurrent()) { release(); throw new Error('录音已取消'); }
  } catch (error) { release(); throw error; }
  let context: AudioContext | null = null;
  let source: MediaStreamAudioSourceNode | null = null;
  let processor: ScriptProcessorNode | null = null;
  let mute: GainNode | null = null;
  let timer: ReturnType<typeof setInterval> | undefined;
  let ended = false;
  let result: Blob | undefined;
  let sampleCount = 0;
  let chunks: Float32Array[] = [];
  const cleanup = () => {
    if (timer !== undefined) clearInterval(timer);
    if (processor) processor.onaudioprocess = null;
    try { processor?.disconnect(); } catch { /* 已断开 */ }
    try { source?.disconnect(); } catch { /* 已断开 */ }
    try { mute?.disconnect(); } catch { /* 已断开 */ }
    release();
    if (context && context.state !== "closed") void context.close().catch(() => undefined);
  };
  captureCleanup = cleanup;
  const finish = (): Blob => {
    if (cancelled) throw new Error("录音已取消");
    if (result) return result;
    ended = true;
    cleanup();
    const samples = new Float32Array(sampleCount);
    let offset = 0;
    for (const chunk of chunks) { samples.set(chunk, offset); offset += chunk.length; }
    chunks = [];
    result = encodeClassroomWav(samples, context!.sampleRate, limit);
    return result;
  };
  const autoStop = () => {
    if (ended) return;
    try { const blob = finish(); options.onAutoStop?.(blob); }
    catch (error) { options.onError?.(error instanceof Error ? error : new Error("录音结束失败")); }
  };
  try {
    const AudioContextClass = window.AudioContext
      || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextClass) throw new Error("浏览器不支持录音，请上传 WAV 或输入文字");
    context = new AudioContextClass();
    await context.resume();
    // 移动浏览器可能延迟 resume；取消发生在该等待期间时，不再创建节点或计时器。
    if (cancelled || !lease?.isCurrent()) throw new Error("录音已取消");
    source = context.createMediaStreamSource(stream);
    // 与项目已有英语录音兼容；短按键录音只需单声道 PCM，无需额外编码依赖。
    processor = context.createScriptProcessor(4096, 1, 1);
    mute = context.createGain();
    mute.gain.value = 0;
    const sourceRate = context.sampleRate;
    processor.onaudioprocess = event => {
      if (ended) return;
      const data = event.inputBuffer.getChannelData(0);
      const remaining = Math.floor(sourceRate * limit) - sampleCount;
      if (remaining > 0) {
        const chunk = new Float32Array(data.subarray(0, remaining));
        chunks.push(chunk);
        sampleCount += chunk.length;
      }
      if (sampleCount >= sourceRate * limit) autoStop();
    };
    source.connect(processor); processor.connect(mute); mute.connect(context.destination);
    const startedAt = Date.now();
    timer = setInterval(() => {
      const duration = Math.min(limit, (Date.now() - startedAt) / 1000);
      options.onDuration?.(duration);
      if (duration >= limit) autoStop();
    }, 200);
    return {
      stop: async () => finish(),
      cancel: () => { cancelled = true; ended = true; chunks = []; cleanup(); },
    };
  } catch (error) {
    cleanup();
    throw error;
  }
}
