import { useCallback, useEffect, useRef, useState } from 'react';
import { audioUrl, classroomRequest, synthesizeSpeech, transcribeAudio, voiceHealth } from './api';
import { NarrationQueue, type PlaybackStatus } from './audioQueue';
import { canRecordClassroomAudio, startClassroomRecording, type ClassroomRecording } from './recording';
import { newLocalId } from './navigation';
import { registerVoiceContext, type VoiceReply } from './voiceContext';
import { useAudioFocus } from './useAudioFocus';
import {
  GROWTH_CATEGORY_ICONS, GROWTH_GRADE_LABELS, GROWTH_STORAGE_PREFIX, growthRequestHistory,
  growthSuggestionBatch, restoreGrowthMessages, type GrowthCategoryId, type GrowthMessage,
  type GrowthReply, type GrowthTopicsResponse,
} from './growth-topics';
import './growth-companion.css';

function readGrade(): number {
  try { const value = Number(localStorage.getItem('educationagent.growth.grade')); return Number.isInteger(value) && value >= 1 && value <= 12 ? value : 5; }
  catch { return 5; }
}
function readMessages(grade: number): GrowthMessage[] {
  try { return restoreGrowthMessages(localStorage.getItem(GROWTH_STORAGE_PREFIX + grade)); } catch { return []; }
}

/** 一处成长入口，聊天只保存在当前浏览器；问答、引用和语音仍通过已有 Java 边界。 */
export default function GrowthCompanion() {
  const audioFocus = useAudioFocus();
  const [grade, setGrade] = useState(readGrade);
  const [category, setCategory] = useState<GrowthCategoryId>('study-stress');
  const [topics, setTopics] = useState<GrowthTopicsResponse | null>(null);
  const [topicsLoading, setTopicsLoading] = useState(true);
  const [topicsError, setTopicsError] = useState('');
  const [topicsRevision, setTopicsRevision] = useState(0);
  const [messages, setMessages] = useState<GrowthMessage[]>(() => readMessages(readGrade()));
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [storageError, setStorageError] = useState('');
  const [suggestionPage, setSuggestionPage] = useState(0);
  const [voice, setVoice] = useState({ ttsAvailable: false, asrAvailable: false });
  const [voiceError, setVoiceError] = useState('');
  const [playback, setPlayback] = useState<PlaybackStatus>('idle');
  const [readingId, setReadingId] = useState('');
  const [recordingState, setRecordingState] = useState<'idle' | 'starting' | 'recording' | 'transcribing'>('idle');
  const [duration, setDuration] = useState(0);
  const mounted = useRef(true);
  const epoch = useRef(0);
  const chatRequest = useRef<AbortController | null>(null);
  const asrRequest = useRef<AbortController | null>(null);
  const recorder = useRef<ClassroomRecording | null>(null);
  const recordingBusy = useRef(false);
  const sending = useRef(false);
  const queue = useRef<NarrationQueue | null>(null);
  const messageEnd = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const recordingRun = useRef(0);
  const microphoneRequest = useRef<AbortController | null>(null);
  const voiceAsk = useRef<(question: string, signal: AbortSignal) => Promise<VoiceReply>>(async () => { throw new Error('成长问答尚未准备好'); });

  if (!queue.current) queue.current = new NarrationQueue({
    synthesize: synthesizeSpeech, resolveUrl: audioUrl, onCaption: () => {}, onAction: () => {},
    onStatus: value => { if (mounted.current) { setPlayback(value); if (value === 'idle' || value === 'done' || value === 'error') setReadingId(''); } },
    onError: value => { if (mounted.current) setVoiceError(value); },
  });

  const cancelActivity = useCallback(() => {
    epoch.current++; recordingRun.current++;
    chatRequest.current?.abort(); asrRequest.current?.abort();
    recorder.current?.cancel(); recorder.current = null;
    microphoneRequest.current?.abort();
    recordingBusy.current = false; sending.current = false;
    queue.current?.setMicrophoneBusy(false); queue.current?.stop();
    setBusy(false); setRecordingState('idle'); setReadingId('');
  }, []);

  useEffect(() => {
    mounted.current = true;
    void voiceHealth().then(value => { if (mounted.current) setVoice(value); }).catch(() => {});
    return () => {
      mounted.current = false; epoch.current++; recordingRun.current++;
      chatRequest.current?.abort(); asrRequest.current?.abort(); recorder.current?.cancel();
      microphoneRequest.current?.abort();
      queue.current?.destroy();
    };
  }, []);
  useEffect(() => registerVoiceContext({ id: `growth:${grade}:${category}`, kind: 'growth', label: '小禾姐姐 · 成长课堂', grade, ask: (question, signal) => voiceAsk.current(question, signal) }), [grade, category]);
  useEffect(() => {
    const controller = new AbortController(); setTopicsLoading(true); setTopicsError('');
    void classroomRequest<GrowthTopicsResponse>(`/guide/topics?grade=${grade}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setTopics(result); })
      .catch(problem => { if (!controller.signal.aborted) setTopicsError(problem instanceof Error ? problem.message : '话题暂未加载，可以直接输入问题。'); })
      .finally(() => { if (!controller.signal.aborted) setTopicsLoading(false); });
    return () => controller.abort();
  }, [grade, topicsRevision]);
  useEffect(() => {
    try { localStorage.setItem(GROWTH_STORAGE_PREFIX + grade, JSON.stringify(messages.slice(-40))); setStorageError(''); }
    catch { setStorageError('浏览器暂时无法保存记录。当前对话仍可继续，离开后可能无法恢复。'); }
  }, [grade, messages]);
  useEffect(() => { messageEnd.current?.scrollIntoView({ block: 'nearest' }); }, [messages.length, busy]);

  const changeGrade = (value: number) => {
    cancelActivity(); setGrade(value); setMessages(readMessages(value)); setInput(''); setError(''); setVoiceError(''); setTopics(null); setSuggestionPage(0);
    try { localStorage.setItem('educationagent.growth.grade', String(value)); } catch { /* 非必要偏好不阻断对话 */ }
  };
  const selectCategory = (value: GrowthCategoryId) => { setCategory(value); setSuggestionPage(0); };
  const clearConversation = () => {
    cancelActivity(); setMessages([]); setInput(''); setError(''); setVoiceError(''); setSuggestionPage(0);
    try { localStorage.removeItem(GROWTH_STORAGE_PREFIX + grade); } catch { /* 清除内存状态仍然有效 */ }
  };
  const send = async (questionOverride?: string, externalSignal?: AbortSignal): Promise<GrowthReply | undefined> => {
    const question = (questionOverride ?? input).trim();
    if (!question || sending.current || recordingBusy.current) return;
    if (question.length > 2000) { setError('这段话超过 2000 字，可以先分成两段慢慢说。'); return; }
    sending.current = true; setBusy(true); setError(''); queue.current?.stop();
    const run = epoch.current;
    const controller = new AbortController(); chatRequest.current = controller;
    const relayAbort = () => controller.abort(); externalSignal?.addEventListener('abort', relayAbort, { once: true });
    if (externalSignal?.aborted) controller.abort();
    const entry: GrowthMessage = { id: newLocalId(), role: 'user', content: question, category };
    const payload = { question, grade, category, history: growthRequestHistory(messages) };
    setMessages(previous => [...previous, entry].slice(-40)); setInput('');
    try {
      const reply = await classroomRequest<GrowthReply>('/guide/chat', { method: 'POST', body: JSON.stringify(payload), signal: controller.signal });
      if (controller.signal.aborted || !mounted.current || run !== epoch.current) return;
      if (typeof reply.text !== 'string' || !reply.text.trim()) throw new Error('这次回复没有完整送达，请再试一次。');
      setMessages(previous => [...previous, { id: reply.id || newLocalId(), role: 'assistant' as const,
        content: reply.text, category: reply.category, sources: reply.sources || [], suggestions: reply.suggestions || [], crisis: reply.crisis }].slice(-40));
      setSuggestionPage(0);
      return reply;
    } catch (problem) {
      if (!controller.signal.aborted && mounted.current && run === epoch.current) {
        setError(problem instanceof Error ? problem.message : '这次没有连上，请稍后重试。');
        setInput(question); setMessages(previous => previous.filter(item => item.id !== entry.id));
      }
      if (externalSignal) throw problem;
    } finally {
      externalSignal?.removeEventListener('abort', relayAbort);
      if (mounted.current && run === epoch.current) { sending.current = false; setBusy(false); }
    }
  };
  voiceAsk.current = async (question, signal) => {
    const reply = await send(question, signal);
    if (!reply) throw new Error('成长课堂正在处理上一条问题，请稍后再说。');
    return { text: reply.text, role: 'peer' };
  };

  const releaseMicrophone = () => {
    recordingBusy.current = false; queue.current?.setMicrophoneBusy(false);
    if (mounted.current) setRecordingState('idle');
  };
  const recognize = async (blob: Blob, run: number) => {
    if (!mounted.current || run !== recordingRun.current) return;
    setRecordingState('transcribing'); setVoiceError('');
    const controller = new AbortController(); asrRequest.current = controller;
    try {
      const result = await transcribeAudio(blob, controller.signal);
      if (!controller.signal.aborted && mounted.current && run === recordingRun.current) { setInput(result.text || ''); inputRef.current?.focus(); }
    } catch (problem) {
      if (!controller.signal.aborted && mounted.current && run === recordingRun.current) setVoiceError(problem instanceof Error ? problem.message : '录音没有识别成功，可以重录或输入文字。');
    } finally { if (mounted.current && run === recordingRun.current) releaseMicrophone(); }
  };
  const toggleRecording = async () => {
    if (recordingState === 'recording') {
      const active = recorder.current; recorder.current = null;
      const run = recordingRun.current;
      try { if (active) await recognize(await active.stop(), run); else releaseMicrophone(); }
      catch (problem) { if (mounted.current && run === recordingRun.current) { releaseMicrophone(); setVoiceError(problem instanceof Error ? problem.message : '没有录到声音，请重试。'); } }
      return;
    }
    if (recordingBusy.current || sending.current) return;
    const run = ++recordingRun.current;
    const micController = new AbortController(); microphoneRequest.current = micController;
    recordingBusy.current = true; queue.current?.setMicrophoneBusy(true); setRecordingState('starting'); setDuration(0); setVoiceError('');
    try {
      const recording = await startClassroomRecording({
        signal: micController.signal,
        onDuration: value => { if (mounted.current && run === recordingRun.current) setDuration(Math.floor(value)); },
        onAutoStop: blob => { if (run === recordingRun.current) { recorder.current = null; void recognize(blob, run); } },
        onError: problem => { if (mounted.current && run === recordingRun.current) { recorder.current = null; releaseMicrophone(); setVoiceError(problem.message); } },
      });
      if (!mounted.current || run !== recordingRun.current) { recording.cancel(); return; }
      recorder.current = recording; setRecordingState('recording');
    } catch (problem) {
      if (mounted.current && run === recordingRun.current) { releaseMicrophone(); setVoiceError(problem instanceof Error ? problem.message : '无法打开麦克风。'); }
    }
  };
  const cancelRecording = () => {
    recordingRun.current++; microphoneRequest.current?.abort(); recorder.current?.cancel(); recorder.current = null; asrRequest.current?.abort(); releaseMicrophone();
  };
  const readAnswer = (message: GrowthMessage) => {
    if (recordingBusy.current || sending.current || !voice.ttsAvailable) return;
    if (readingId === message.id && (playback === 'playing' || playback === 'loading')) { queue.current?.pause(); return; }
    if (readingId === message.id && playback === 'paused') { queue.current?.resume(); return; }
    setReadingId(message.id); setVoiceError(''); void queue.current?.readResponse(message.content, 'peer');
  };
  const currentTopic = topics?.categories.find(item => item.id === category);
  const lastReply = [...messages].reverse().find(item => item.role === 'assistant' && item.category === category);
  const suggestions = growthSuggestionBatch(lastReply?.suggestions || [], currentTopic?.questions || [], suggestionPage);
  const ownMicBusy = recordingState !== 'idle';
  const micBusy = ownMicBusy || audioFocus.microphoneBlocked;

  return <section className="growth-companion" aria-label="成长课堂">
    <header className="gc-header"><div><span className="gc-eyebrow">成长课堂 · 说一说，也是一小步</span><h1>有些心事，可以慢慢说。</h1><p>学习之外，情绪、关系和成长中的疑问，也值得被认真听见。</p></div><label className="gc-grade">我在读<select value={grade} onChange={event => changeGrade(Number(event.target.value))}>{GROWTH_GRADE_LABELS.map((label, index) => <option key={label} value={index + 1}>{label}</option>)}</select></label></header>
    <div className="gc-layout">
      <aside className="gc-topics"><div className="gc-topics-title"><h2>从你关心的事开始</h2><span>不需要一次说完</span></div>
        {topicsLoading && <p className="gc-note" role="status">正在准备适合这个学段的话题…</p>}
        {topicsError && <div className="gc-error" role="alert">{topicsError}<button onClick={() => setTopicsRevision(value => value + 1)}>重新加载话题</button></div>}
        <nav className="gc-category-list" aria-label="成长话题分类">{topics?.categories.map(topic => <button key={topic.id} disabled={busy || micBusy} aria-pressed={category === topic.id} className={category === topic.id ? 'active' : ''} onClick={() => selectCategory(topic.id)}><span className="gc-topic-icon" aria-hidden="true">{GROWTH_CATEGORY_ICONS[topic.id]}</span><span><strong>{topic.label}</strong><small>{topic.description}</small></span><span aria-hidden="true">›</span></button>)}</nav>
        <div className="gc-prompt-box"><div className="gc-prompt-title"><h3>{lastReply ? '接着聊一聊' : '猜你想问'}</h3><button disabled={suggestions.length < 2 || busy || micBusy} onClick={() => setSuggestionPage(value => value + 1)}>换一批 ↻</button></div><p>{currentTopic?.label || '写下任何想聊的成长问题'} · 点击问题就能开始</p>
          <div className="gc-prompts">{suggestions.map(question => <button key={question} disabled={busy || micBusy} onClick={() => void send(question)}>{question}<span aria-hidden="true">↗</span></button>)}</div>
        </div><div className="gc-real-support"><span aria-hidden="true">☀</span><p>需要现实中的帮助时，老师、家人或其他值得信任的大人也可以和你一起想办法。</p></div>
      </aside>
      <div className="gc-chat"><div className="gc-chat-header"><div className="gc-persona"><span className="gc-persona-avatar" aria-hidden="true">禾</span><div><h2>小禾姐姐 <span>AI</span></h2><p>耐心听你说，陪你整理想法</p></div></div><button className="gc-clear" disabled={busy || micBusy} onClick={clearConversation}>清除本年级对话</button></div>
        <div className="gc-messages" aria-live="polite">
          {!messages.length && <div className="gc-welcome"><span className="gc-welcome-mark" aria-hidden="true">✦</span><h3>你好，我是小禾姐姐。</h3><p>{topics?.persona.intro || '我是一个 AI 成长陪伴助手。你可以用自己的节奏说说最近的感受，我会陪你把问题一点点理清楚。'}</p><div className="gc-welcome-pills"><span>不急着下结论</span><span>一起找一小步</span><span>也向现实中的人求助</span></div></div>}
          {messages.map(message => <article key={message.id} className={`gc-message ${message.role === 'user' ? 'gc-user' : 'gc-assistant'} ${message.crisis ? 'gc-support-message' : ''}`}><div className="gc-message-label">{message.role === 'user' ? '我' : '小禾姐姐 · AI'}{message.crisis && <span>先照顾眼前的安全</span>}</div><p className="gc-message-text">{message.content}</p>
            {message.role === 'assistant' && <><div className="gc-message-tools"><button disabled={!voice.ttsAvailable || busy || micBusy} onClick={() => readAnswer(message)}>{readingId === message.id && playback === 'paused' ? '▶ 继续听' : readingId === message.id && (playback === 'playing' || playback === 'loading') ? 'Ⅱ 暂停朗读' : '▷ 听一听'}</button>{readingId === message.id && <button onClick={() => queue.current?.stop()}>停止</button>}<span>{message.sources?.length ? `参考 ${message.sources.length} 条资料片段` : message.crisis ? '需要时请联系身边可信的大人' : '未引用知识库资料'}</span></div>
              {!!message.sources?.length && <details className="gc-sources"><summary>看看参考资料</summary>{message.sources.map((source, index) => <div key={`${source.file}-${index}`}><strong>{source.file}</strong>{source.content && <p>{source.content}</p>}</div>)}<small>这些是检索到的参考片段，并非针对个人情况的评估结论。</small></details>}</>}
          </article>)}
          {busy && <div className="gc-thinking" role="status"><span>· · ·</span>小禾姐姐正在认真想一想…</div>}<div ref={messageEnd} />
        </div>
        <div className="gc-compose"><div className="gc-current-topic">正在聊：{currentTopic?.label || '成长中的疑问'}<span>{GROWTH_GRADE_LABELS[grade - 1]}</span></div>
          {error && <div className="gc-error" role="alert">{error}<button onClick={() => void send()} disabled={busy || !input.trim() || micBusy}>重试发送</button></div>}
          {voiceError && <div className="gc-error" role="alert">{voiceError}<button onClick={() => setVoiceError('')}>收起</button></div>}
          <label className="cr-visually-hidden" htmlFor="growth-question">想对小禾姐姐说的话</label><textarea ref={inputRef} id="growth-question" rows={3} maxLength={2000} value={input} disabled={busy || micBusy} onChange={event => setInput(event.target.value)} placeholder={recordingState === 'transcribing' ? '正在把录音转成文字，确认后再发送…' : '今天发生了什么？或者，有什么一直想问却没开口的事？'} />
          <div className="gc-compose-actions"><div><button className={recordingState === 'recording' ? 'gc-recording' : ''} disabled={busy || recordingState === 'starting' || recordingState === 'transcribing' || (audioFocus.microphoneBlocked && !ownMicBusy) || !voice.asrAvailable || (!micBusy && !canRecordClassroomAudio())} onClick={() => void toggleRecording()}>{recordingState === 'recording' ? `■ 完成录音 ${duration}s` : recordingState === 'starting' ? '打开麦克风…' : recordingState === 'transcribing' ? '正在转写…' : '● 录音说一说'}</button>{ownMicBusy && <button onClick={cancelRecording}>取消录音</button>}{voice.asrAvailable && !micBusy && <label className="gc-wav-upload">上传 WAV<input type="file" accept=".wav,audio/wav" disabled={busy} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (!file || sending.current || recordingBusy.current) return; const run = ++recordingRun.current; recordingBusy.current = true; queue.current?.setMicrophoneBusy(true); void recognize(file, run); }} /></label>}</div><button className="gc-send" disabled={busy || micBusy || !input.trim()} onClick={() => void send()}>{busy ? '正在发送…' : '发送 ↑'}</button></div>
          <p className="gc-input-note">{micBusy ? '最长 60 秒。转成文字后，由你确认再发送。' : !voice.asrAvailable ? '现在可以输入文字聊天；语音识别尚不可用。' : !canRecordClassroomAudio() ? '当前浏览器需要 HTTPS 麦克风权限，也可以输入文字或上传 WAV。' : '录音会先转成文字，不会自动发送。'} <span>{input.length}/2000</span></p>
        </div>
      </div>
    </div><p className="gc-privacy">对话记录只保存在当前浏览器，按年级分开；发送时会将近期对话交给 AI 理解，不写入公共课程或儿童档案。共用设备时，可以清除本年级对话。</p>{storageError && <p className="gc-error" role="status">{storageError}</p>}
  </section>;
}
