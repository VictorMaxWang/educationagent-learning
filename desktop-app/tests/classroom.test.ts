import { describe, expect, it, vi } from 'vitest';
import { NarrationQueue, type NarrationPorts } from '../src/classroom/audioQueue';
import { clampIndex, courseHash, courseIdFromHash } from '../src/classroom/navigation';
import { resumeCourse, splitNarrationText } from '../src/classroom/api';
import { answerForChoices } from '../src/classroom/QuizScene';

class FakeAudio extends EventTarget {
  src = '';
  playbackRate = 1;
  calls: string[] = [];
  pause = vi.fn();
  load = vi.fn();
  play = vi.fn(async () => { this.calls.push(this.src); });
  getAttribute(name: string) { return name === 'src' ? this.src || null : null; }
  removeAttribute() { this.src = ''; }
  end() { this.dispatchEvent(new Event('ended')); }
}
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function fixture(synthesize: NarrationPorts['synthesize']) {
  const audio = new FakeAudio();
  const ports = {
    synthesize,
    resolveUrl: (value: string) => value,
    createAudio: vi.fn(() => audio as unknown as HTMLAudioElement),
    onStatus: vi.fn(), onCaption: vi.fn(), onAction: vi.fn(), onError: vi.fn(), onDiscussion: vi.fn(),
  };
  return { audio, ports, queue: new NarrationQueue(ports) };
}

describe('课堂深链与答案', () => {
  it('深链可回到具体课程，错误编码与其他工具不会误识别', () => {
    expect(courseIdFromHash(courseHash('一节课 / 1'))).toBe('一节课 / 1');
    expect(courseIdFromHash('#/classroom/%bad')).toBeNull();
    expect(courseIdFromHash('#/settings')).toBeNull();
    expect(clampIndex(9, 2)).toBe(1);
    expect(clampIndex(NaN, 0)).toBe(0);
  });
  it('多选规范化为排序字母，重复点击取消，单选替换', () => {
    expect(answerForChoices('C', 'A', true)).toBe('AC');
    expect(answerForChoices('AC', 'A', true)).toBe('C');
    expect(answerForChoices('A', 'B', false)).toBe('B');
  });
  it('6000字讲稿完整切分并避免拆开表情的代理对', () => {
    const text = `${'甲'.repeat(1999)}🌾。${'乙'.repeat(2500)}！${'丙'.repeat(1500)}`;
    const parts = splitNarrationText(text);
    expect(parts.join('')).toBe(text);
    expect(parts.every(part => part.length <= 2000)).toBe(true);
    expect(parts.every(part => !/[\ud800-\udbff]$/.test(part))).toBe(true);
  });
});

describe('旁白队列', () => {
  it('先录音或等待授权，再点击听课/朗读回答，都不能发起声音', async () => {
    const synthesize = vi.fn(async () => ({ segments: [{ text: '讲解', url: '/lesson.mp3' }] }));
    const { queue, audio, ports } = fixture(synthesize);
    queue.setMicrophoneBusy(true);
    await queue.play([{ type: 'speech', text: '讲解' }]);
    await queue.readResponse('同伴回答', 'peer');
    queue.resume();
    expect(ports.createAudio).not.toHaveBeenCalled();
    expect(synthesize).not.toHaveBeenCalled();
    expect(audio.calls).toHaveLength(0);
    // 释放后也不会自动播放，需学生再次点击；语音识别上传期间麦克风已经关闭。
    queue.setMicrophoneBusy(false);
    expect(audio.calls).toHaveLength(0);
    const done = queue.play([{ type: 'speech', text: '讲解' }]);
    await flush(); expect(audio.src).toBe('/lesson.mp3');
    audio.end(); await done;
  });
  it('录音加锁会取消已在合成的旁白，迟到结果不得进入麦克风', async () => {
    let complete!: (value: { segments: { text: string; url: string }[] }) => void;
    const { queue, audio } = fixture(() => new Promise(resolve => { complete = resolve; }));
    const pending = queue.play([{ type: 'speech', text: '旧讲解' }]);
    await flush(); queue.setMicrophoneBusy(true);
    complete({ segments: [{ text: '旧讲解', url: '/late.mp3' }] });
    await pending;
    expect(audio.calls).not.toContain('/late.mp3');
  });
  it('同一audio按顺序播放各片段，只在真实结束后进入下一段', async () => {
    const { queue, audio, ports } = fixture(async () => ({ segments: [{ text: '第一句', url: '/one.mp3' }, { text: '第二句', url: '/two.mp3' }] }));
    const done = queue.play([{ type: 'speech', text: '完整讲解' }]);
    await flush();
    expect(audio.src).toBe('/one.mp3');
    expect(audio.calls).not.toContain('/two.mp3');
    audio.end(); await flush(); expect(audio.src).toBe('/two.mp3');
    audio.end(); await done;
    expect(ports.createAudio).toHaveBeenCalledTimes(1);
    expect(ports.onStatus).toHaveBeenLastCalledWith('done');
  });
  it('换课取消后，迟到的合成结果不能发声', async () => {
    let resolve!: (value: { segments: { text: string; url: string }[] }) => void;
    const { queue, audio, ports } = fixture(() => new Promise(done => { resolve = done; }));
    const done = queue.play([{ type: 'speech', text: '旧课堂' }]);
    await flush(); queue.stop();
    resolve({ segments: [{ text: '旧课堂', url: '/stale.mp3' }] });
    await done;
    expect(audio.calls).not.toContain('/stale.mp3');
    expect(ports.onStatus).toHaveBeenLastCalledWith('idle');
  });
  it('合成等待中暂停，结果返回也不会自动发声', async () => {
    let resolve!: (value: { segments: { text: string; url: string }[] }) => void;
    const { queue, audio } = fixture(() => new Promise(done => { resolve = done; }));
    const done = queue.play([{ type: 'speech', text: '等等我' }]);
    await flush(); queue.pause();
    resolve({ segments: [{ text: '等等我', url: '/wait.mp3' }] });
    await flush(); expect(audio.calls).not.toContain('/wait.mp3');
    queue.resume(); await flush(); expect(audio.src).toBe('/wait.mp3');
    audio.end(); await done;
  });
  it('讨论问题是暂停点，不伪装成学伴回答，也不跳过学生', async () => {
    const synthesize = vi.fn(async () => ({ segments: [] }));
    const { queue, ports } = fixture(synthesize);
    await queue.play([{ type: 'discussion', topic: '你的想法是什么？' }, { type: 'speech', text: '下一段' }]);
    expect(synthesize).not.toHaveBeenCalled();
    expect(ports.onCaption).toHaveBeenCalledWith('你的想法是什么？', 'teacher');
    expect(ports.onDiscussion).toHaveBeenCalledWith('你的想法是什么？', 1);
    expect(ports.onStatus).toHaveBeenLastCalledWith('discussion');
  });
  it('一段已结束而下一段仍在合成时，恢复不会重播旧音频', async () => {
    let resolveNext!: (value: { segments: { text: string; url: string }[] }) => void;
    const synthesize = vi.fn()
      .mockResolvedValueOnce({ segments: [{ text: '第一段', url: '/finished.mp3' }] })
      .mockImplementationOnce(() => new Promise(resolve => { resolveNext = resolve; }));
    const { queue, audio } = fixture(synthesize);
    const done = queue.play([{ type: 'speech', text: '第一段' }, { type: 'speech', text: '第二段' }]);
    await flush(); audio.end(); await flush();
    queue.pause(); queue.resume(); await flush();
    expect(audio.calls.filter(url => url === '/finished.mp3')).toHaveLength(1);
    resolveNext({ segments: [{ text: '第二段', url: '/next.mp3' }] });
    await flush(); audio.end(); await done;
  });
  it('真实学伴回答使用原audio且不覆盖课程动作进度', async () => {
    const synthesize = vi.fn(async () => ({ segments: [{ text: '我的想法', url: '/peer.mp3' }] }));
    const { queue, audio, ports } = fixture(synthesize);
    const done = queue.readResponse('我的想法', 'peer'); await flush();
    expect(synthesize).toHaveBeenCalledWith('我的想法', 'peer', expect.any(AbortSignal));
    expect(ports.onAction).not.toHaveBeenCalled();
    audio.end(); await done;
    expect(ports.createAudio).toHaveBeenCalledTimes(1);
  });
});

describe('从复习/成长返回学习', () => {
  it('等待最近位置落库后重取课程，同时恢复第三页与持久化问答', async () => {
    let saved!: () => void;
    const pendingSave = new Promise<void>(resolve => { saved = resolve; });
    const course = {
      id: 'lesson', progress: { sceneIndex: 2, actionIndex: 3 },
      messages: [{ id: 'question', speaker: 'user', text: '我想再听一个例子' }, { id: 'reply', speaker: 'teacher', text: '我们用菜地的面积想一想' }],
    };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(course), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    try {
      const resumed = resumeCourse('lesson', pendingSave);
      await flush(); expect(fetchMock).not.toHaveBeenCalled();
      saved();
      const result = await resumed;
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(clampIndex(result.progress.sceneIndex, 4)).toBe(2);
      expect(result.progress.actionIndex).toBe(3);
      expect(result.messages.map(message => message.speaker)).toEqual(['user', 'teacher']);
      expect(result.messages[1].text).toContain('菜地的面积');
    } finally { vi.unstubAllGlobals(); }
  });
  it('位置未保存成功时不以旧服务器位置覆盖当前课堂', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
    try {
      await expect(resumeCourse('lesson', Promise.reject(new Error('网络不可用')))).rejects.toThrow('网络不可用');
      expect(fetchMock).not.toHaveBeenCalled();
    } finally { vi.unstubAllGlobals(); }
  });
});
