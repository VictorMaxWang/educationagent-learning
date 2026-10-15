import { afterEach, describe, expect, it, vi } from 'vitest';
import { AudioCoordinator, audioCoordinator } from '../src/classroom/AudioCoordinator';
import { NarrationQueue } from '../src/classroom/audioQueue';
import { currentVoiceContext, registerVoiceContext } from '../src/classroom/voiceContext';
import { sceneContentRevision } from '../src/classroom/reflectionIdentity';
import { boundedVoiceHistory } from '../src/components/VoiceOrb';

afterEach(() => audioCoordinator.stopAll());
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
class AudioStub extends EventTarget {
  src = ''; playbackRate = 1; calls: string[] = [];
  pause = vi.fn(); load = vi.fn();
  play = vi.fn(async () => { this.calls.push(this.src); });
  getAttribute() { return this.src || null; }
  removeAttribute() { this.src = ''; }
}
function makeQueue(url: string) {
  const media = new AudioStub(), status = vi.fn();
  const synthesize = vi.fn(async () => ({ segments: [{ text: url, url }] }));
  const queue = new NarrationQueue({ createAudio: () => media as unknown as HTMLAudioElement, synthesize, resolveUrl: value => value, onAction: () => {}, onCaption: () => {}, onError: () => {}, onStatus: status });
  return { media, status, synthesize, queue };
}

describe('全局声音互斥', () => {
  it('新播放替换旧播放；旧token的finally不能释放新播放', () => {
    const coordinator = new AudioCoordinator();
    const stopFirst = vi.fn(), stopSecond = vi.fn();
    const first = coordinator.acquirePlayback('lesson', stopFirst)!;
    const second = coordinator.acquirePlayback('orb', stopSecond)!;
    expect(stopFirst).toHaveBeenCalledOnce(); expect(first.isCurrent()).toBe(false);
    first.release(); expect(second.isCurrent()).toBe(true); expect(coordinator.snapshot().playbackOwner).toBe('orb');
    second.release(); expect(coordinator.snapshot().playbackOwner).toBeNull();
  });
  it('麦克风优先停止声音，但第二处录音不会抢掉第一处的内容', () => {
    const coordinator = new AudioCoordinator(), stopPlayback = vi.fn(), cancelMic = vi.fn();
    coordinator.acquirePlayback('music', stopPlayback);
    const mic = coordinator.acquireMicrophone('classroom', cancelMic)!;
    expect(stopPlayback).toHaveBeenCalledOnce();
    expect(coordinator.acquirePlayback('orb', () => {})).toBeNull();
    expect(coordinator.acquireMicrophone('orb', () => {})).toBeNull(); expect(cancelMic).not.toHaveBeenCalled();
    mic.release(); expect(coordinator.acquirePlayback('orb', () => {})).not.toBeNull();
  });
  it('权限等待保护跨队列生效，取消后不自动重播', async () => {
    const lesson = makeQueue('/lesson.mp3'), orb = makeQueue('/orb.mp3');
    lesson.queue.setMicrophoneBusy(true);
    await orb.queue.readResponse('不会在录音时播放', 'peer');
    expect(orb.synthesize).not.toHaveBeenCalled();
    lesson.queue.setMicrophoneBusy(false); expect(orb.media.calls).toHaveLength(0);
    lesson.queue.destroy(); orb.queue.destroy();
  });
  it('课堂朗读与语音球真实队列不重叠，新队列取得焦点会终止旧队列', async () => {
    const lesson = makeQueue('/lesson.mp3'), orb = makeQueue('/orb.mp3');
    const first = lesson.queue.readResponse('课堂讲解', 'teacher'); await flush();
    expect(lesson.media.src).toBe('/lesson.mp3');
    const second = orb.queue.readResponse('语音球回答', 'peer'); await flush(); await first;
    expect(lesson.media.src).toBe(''); expect(lesson.status).toHaveBeenLastCalledWith('idle');
    expect(orb.media.src).toBe('/orb.mp3');
    orb.media.dispatchEvent(new Event('ended')); await second;
    lesson.queue.destroy(); orb.queue.destroy();
  });
  it('切页停止所有焦点；迟到的释放不能清除后来申请的录音', () => {
    const coordinator = new AudioCoordinator(), cancelled = vi.fn();
    const old = coordinator.acquireMicrophone('old', cancelled)!;
    coordinator.stopAll(); expect(cancelled).toHaveBeenCalledOnce();
    const current = coordinator.acquireMicrophone('new', () => {})!;
    old.release(); expect(current.isCurrent()).toBe(true);
  });
});

describe('语音上下文与学习内容身份', () => {
  it('旧页面注销不覆盖新页面，问题交给真实注册的当前处理器', async () => {
    const first = vi.fn(async () => ({ text: '课堂回答', role: 'teacher' as const }));
    const second = vi.fn(async () => ({ text: '成长回答', role: 'peer' as const }));
    const offFirst = registerVoiceContext({ id: 'course', kind: 'classroom', label: '数学', grade: 5, ask: first });
    const offSecond = registerVoiceContext({ id: 'growth', kind: 'growth', label: '成长', grade: 5, ask: second });
    offFirst();
    expect((await currentVoiceContext()!.ask('我想聊聊', new AbortController().signal)).text).toBe('成长回答');
    expect(first).not.toHaveBeenCalled(); expect(second).toHaveBeenCalledOnce();
    offSecond(); expect(currentVoiceContext()).toBeNull();
  });
  it('内容指纹不受字段顺序与运行版本变化影响，实际课件改变才分开反思记录', () => {
    const first = { type: 'slide', canvas: { id: 'a', elements: [{ id: 't', content: '一半' }] } };
    const reordered = { canvas: { elements: [{ content: '一半', id: 't' }], id: 'a' }, type: 'slide' };
    expect(sceneContentRevision(first)).toBe(sceneContentRevision(reordered));
    expect(sceneContentRevision({ ...first, canvas: { ...first.canvas, elements: [{ id: 't', content: '四分之一' }] } })).not.toBe(sceneContentRevision(first));
    expect(Number.isSafeInteger(sceneContentRevision(first))).toBe(true);
  });
  it('语音球仅带有界近期历史，不把整段长期记录无限提交', () => {
    const history = Array.from({ length: 30 }, (_, i) => ({ role: i % 2 ? 'assistant' as const : 'user' as const, content: `${i}:` + '字'.repeat(2500) }));
    const result = boundedVoiceHistory(history);
    expect(result.length).toBeLessThanOrEqual(12); expect(result.reduce((sum, item) => sum + item.content.length, 0)).toBeLessThanOrEqual(12000);
    expect(result.at(-1)!.content.startsWith('29:')).toBe(true);
  });
});
