import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { audioUrl, classroomRequest, synthesizeSpeech, transcribeAudio, voiceHealth } from '../classroom/api';
import { audioCoordinator } from '../classroom/AudioCoordinator';
import { NarrationQueue, type PlaybackStatus } from '../classroom/audioQueue';
import { canRecordClassroomAudio, startClassroomRecording, type ClassroomRecording } from '../classroom/recording';
import { currentVoiceContext, subscribeVoiceContext, type VoiceContext, type VoiceReply } from '../classroom/voiceContext';
import { GRADE_LABELS } from '../classroom/types';
import './VoiceOrb.css';

type Phase = 'idle' | 'starting' | 'recording' | 'transcribing' | 'thinking';
interface Capabilities { ttsAvailable: boolean; asrAvailable: boolean; voiceName?: string }
interface HistoryItem { role: 'user' | 'assistant'; content: string }
function initialGrade() {
  try { const grade = Number(localStorage.getItem('educationagent.growth.grade')); return grade >= 1 && grade <= 12 && Number.isInteger(grade) ? grade : 5; } catch { return 5; }
}
export function boundedVoiceHistory(history: HistoryItem[]): HistoryItem[] {
  const selected: HistoryItem[] = []; let length = 0;
  for (const item of history.slice(-12).reverse()) {
    const content = item.content.slice(0, 2000);
    if (length + content.length > 12000) break;
    length += content.length; selected.unshift({ role: item.role, content });
  }
  return selected;
}

/** 全局入口只组织录音、确认和播放；问题仍交给当前页面真实问答服务。 */
export default function VoiceOrb({ suspended = false }: { suspended?: boolean }) {
  const [open, setOpen] = useState(false);
  const [context, setContext] = useState<VoiceContext | null>(currentVoiceContext);
  const [phase, setPhase] = useState<Phase>('idle');
  const [playback, setPlayback] = useState<PlaybackStatus>('idle');
  const [draft, setDraft] = useState('');
  const [answer, setAnswer] = useState('');
  const [question, setQuestion] = useState('');
  const [caption, setCaption] = useState('');
  const [error, setError] = useState('');
  const [duration, setDuration] = useState(0);
  const [grade, setGrade] = useState(initialGrade);
  const [autoRead, setAutoRead] = useState(true);
  const [capabilities, setCapabilities] = useState<Capabilities>({ ttsAvailable: false, asrAvailable: false });
  const [focus, setFocus] = useState(() => audioCoordinator.snapshot());
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const mounted = useRef(true), run = useRef(0), busy = useRef(false);
  const activeContext = useRef(context); activeContext.current = context;
  const audio = useRef<NarrationQueue | null>(null), reply = useRef<VoiceReply | null>(null);
  const recorder = useRef<ClassroomRecording | null>(null);
  const microphoneRequest = useRef<AbortController | null>(null), request = useRef<AbortController | null>(null);
  const requestDeadline = useRef<number | null>(null);
  const history = useRef<HistoryItem[]>([]);
  const textArea = useRef<HTMLTextAreaElement | null>(null), orbButton = useRef<HTMLButtonElement | null>(null);
  if (!audio.current) audio.current = new NarrationQueue({
    synthesize: synthesizeSpeech, resolveUrl: audioUrl,
    onStatus: value => { if (mounted.current) setPlayback(value); },
    onCaption: text => { if (mounted.current) setCaption(text); },
    onError: text => { if (mounted.current) setError(text); },
    onAction: async (_index, action, signal) => {
      if (action.type.startsWith('wb_') || action.type.startsWith('widget_') || action.type === 'spotlight' || action.type === 'laser') {
        if (!reply.current?.executeAction) throw new Error('当前页面不再支持这项课堂操作。');
        await reply.current.executeAction(action, signal);
      }
    },
  });
  const cancelActivity = useCallback(() => {
    run.current++; busy.current = false;
    microphoneRequest.current?.abort(); recorder.current?.cancel(); recorder.current = null;
    request.current?.abort(); audio.current?.setMicrophoneBusy(false); audio.current?.stop();
    request.current = null;
    if (requestDeadline.current !== null) window.clearTimeout(requestDeadline.current);
    requestDeadline.current = null;
    if (mounted.current) setPhase('idle');
  }, []);
  // 超时立即恢复输入；晚到响应仍须通过轮次和 AbortSignal 双重检查，不能污染下一轮。
  const startRequest = (epoch: number, milliseconds: number, message: string) => {
    const controller = new AbortController(); request.current = controller;
    requestDeadline.current = window.setTimeout(() => {
      requestDeadline.current = null;
      controller.abort();
      if (mounted.current && epoch === run.current && request.current === controller) {
        busy.current = false; setPhase('idle'); setError(message);
      }
    }, milliseconds);
    return controller;
  };
  const finishRequest = (controller: AbortController) => {
    if (request.current !== controller) return;
    if (requestDeadline.current !== null) window.clearTimeout(requestDeadline.current);
    requestDeadline.current = null; request.current = null;
  };
  useEffect(() => {
    mounted.current = true;
    void voiceHealth().then(value => { if (mounted.current) setCapabilities(value as Capabilities); }).catch(() => {});
    const offContext = subscribeVoiceContext(next => {
      if (next?.id !== activeContext.current?.id) { cancelActivity(); setAnswer(''); setQuestion(''); setCaption(''); reply.current = null; }
      activeContext.current = next; setContext(next);
    });
    const existing = currentVoiceContext(); activeContext.current = existing; setContext(existing);
    const offFocus = audioCoordinator.subscribe(setFocus);
    const viewport = window.visualViewport;
    const resize = () => setKeyboardOffset(viewport ? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop) : 0);
    viewport?.addEventListener('resize', resize); viewport?.addEventListener('scroll', resize);
    return () => {
      mounted.current = false; offContext(); offFocus(); cancelActivity(); audio.current?.destroy();
      viewport?.removeEventListener('resize', resize); viewport?.removeEventListener('scroll', resize);
    };
  }, [cancelActivity]);
  const close = () => { cancelActivity(); setOpen(false); orbButton.current?.focus(); };
  useEffect(() => { if (suspended) { cancelActivity(); setOpen(false); } }, [suspended, cancelActivity]);
  const recognize = async (blob: Blob, epoch: number) => {
    if (!mounted.current || epoch !== run.current) return;
    audio.current?.setMicrophoneBusy(false); setPhase('transcribing'); setError('');
    const controller = startRequest(epoch, 45000, '语音识别等待较久，已停止等待。可以重试录音或直接输入文字。');
    try {
      const result = await transcribeAudio(blob, controller.signal);
      if (mounted.current && epoch === run.current && !controller.signal.aborted) { setDraft((result.text || '').slice(0, 2000)); setPhase('idle'); textArea.current?.focus(); }
    } catch (problem) {
      if (mounted.current && epoch === run.current && !controller.signal.aborted) setError(problem instanceof Error ? problem.message : '没有识别到完整文字，请重新录音或输入。');
    } finally { finishRequest(controller); if (mounted.current && epoch === run.current) { busy.current = false; setPhase('idle'); } }
  };
  const toggleRecording = async () => {
    if (phase === 'recording') {
      const current = recorder.current, epoch = run.current; recorder.current = null;
      try { if (current) await recognize(await current.stop(), epoch); }
      catch (problem) { if (mounted.current && epoch === run.current) { audio.current?.setMicrophoneBusy(false); busy.current = false; setPhase('idle'); setError(problem instanceof Error ? problem.message : '没有录到声音，请重试。'); } }
      return;
    }
    if (busy.current || focus.microphoneBlocked) return;
    const epoch = ++run.current; busy.current = true;
    setError(''); setDuration(0); setPhase('starting'); audio.current?.setMicrophoneBusy(true);
    const controller = new AbortController(); microphoneRequest.current = controller;
    try {
      const recording = await startClassroomRecording({ signal: controller.signal,
        onDuration: seconds => { if (mounted.current && epoch === run.current) setDuration(Math.floor(seconds)); },
        onAutoStop: blob => { recorder.current = null; void recognize(blob, epoch); },
        onError: problem => { if (mounted.current && epoch === run.current) { busy.current = false; setPhase('idle'); audio.current?.setMicrophoneBusy(false); setError(problem.message); } },
      });
      if (!mounted.current || epoch !== run.current) { recording.cancel(); return; }
      recorder.current = recording; setPhase('recording');
    } catch (problem) {
      if (mounted.current && epoch === run.current) { busy.current = false; setPhase('idle'); audio.current?.setMicrophoneBusy(false); setError(problem instanceof Error ? problem.message : '无法打开麦克风。'); }
    }
  };
  const playReply = (response: VoiceReply) => {
    reply.current = response;
    const actions = (response.actions || []).filter(action => action.type !== 'speech' || action.text !== response.text);
    void audio.current?.playActions([{ type: 'speech', text: response.text }, ...actions], response.role);
  };
  const send = async () => {
    const text = draft.trim(); if (!text || busy.current || focus.microphoneBlocked) return;
    const epoch = ++run.current; const source = activeContext.current;
    busy.current = true; setPhase('thinking'); setError(''); setQuestion(text); setAnswer(''); setCaption(''); audio.current?.stop();
    const controller = startRequest(epoch, 90000, '这次回答等待较久，已停止等待。文字已保留，可以稍后重试。');
    try {
      let response: VoiceReply;
      let fallbackTurn: HistoryItem[] | undefined;
      if (source) response = await source.ask(text, controller.signal);
      else {
        const value = await classroomRequest<{ text: string }>('/guide/chat', { method: 'POST', body: JSON.stringify({ question: text, grade, category: 'needs', history: boundedVoiceHistory(history.current) }), signal: controller.signal });
        if (!value.text?.trim()) throw new Error('这次回答没有完整送达，请再试一次。');
        response = { text: value.text, role: 'peer' };
        fallbackTurn = [{ role: 'user', content: text }, { role: 'assistant', content: value.text }];
      }
      if (!mounted.current || epoch !== run.current || controller.signal.aborted) return;
      if (fallbackTurn) history.current = [...history.current, ...fallbackTurn].slice(-12);
      reply.current = response; setAnswer(response.text); setDraft(''); setPhase('idle');
      if (autoRead && capabilities.ttsAvailable) playReply(response);
      else if (response.actions?.length) void audio.current?.playActions(response.actions.filter(action => action.type !== 'speech' && action.type !== 'discussion'), response.role);
    } catch (problem) {
      if (mounted.current && epoch === run.current && !controller.signal.aborted) setError(problem instanceof Error ? problem.message : '暂时没有连上，请保留文字稍后重试。');
    } finally { finishRequest(controller); if (mounted.current && epoch === run.current) { busy.current = false; setPhase('idle'); } }
  };
  const recording = phase === 'recording' || phase === 'starting', waiting = phase === 'thinking' || phase === 'transcribing';
  const otherMicrophone = focus.microphoneBlocked && !recording, speaking = playback === 'playing' || playback === 'loading';
  const label = phase === 'recording' ? `正在听 · ${duration}s` : phase === 'starting' ? '打开麦克风' : phase === 'transcribing' ? '转成文字中' : phase === 'thinking' ? '正在想一想' : speaking ? '正在朗读' : '小禾语音';
  return <div className={`voice-orb ${open ? 'vo-open' : ''} ${recording ? 'vo-recording' : ''} ${speaking ? 'vo-speaking' : ''}`} hidden={suspended} style={{ '--vo-keyboard-offset': `${keyboardOffset}px` } as CSSProperties}>
    {open && <section className="vo-panel" role="dialog" aria-label="小禾语音助手" onKeyDown={event => { if (event.key === 'Escape') close(); }}>
      <header className="vo-header"><span className="vo-mini-mark" aria-hidden="true">禾</span><div><h2>小禾语音 <span>AI</span></h2><p title={context?.label}>{context ? context.kind === 'classroom' ? `正在这堂课：${context.label}` : '正在成长课堂，沿用当前话题' : '小禾姐姐在这里，慢慢说就好'}</p></div><button className="vo-close" aria-label="收起语音助手" onClick={close}>×</button></header>
      {!context && <label className="vo-grade">我在读<select value={grade} disabled={recording || waiting} onChange={event => { audio.current?.stop(); history.current = []; setAnswer(''); setQuestion(''); setGrade(Number(event.target.value)); }}>{GRADE_LABELS.map((name, index) => <option key={name} value={index + 1}>{name}</option>)}</select></label>}
      <div className="vo-body">{question && <p className="vo-question">{question}</p>}
        {answer ? <div className="vo-answer"><span>{context?.kind === 'classroom' ? '课堂老师 · AI' : '小禾姐姐 · AI'}</span><p>{answer}</p><div className="vo-read-actions"><button disabled={!capabilities.ttsAvailable || recording || otherMicrophone} onClick={() => { if (speaking) audio.current?.pause(); else if (playback === 'paused') audio.current?.resume(); else if (reply.current) playReply({ ...reply.current, actions: [] }); }}>{speaking ? '暂停朗读' : playback === 'paused' ? '继续朗读' : '听一听'}</button>{(speaking || playback === 'paused') && <button onClick={() => audio.current?.stop()}>停止声音</button>}</div></div> : <div className="vo-empty"><span aria-hidden="true">✦</span><p>{context?.kind === 'classroom' ? '这一页哪里没听懂？说出来，老师会接着当前内容回答。' : '一个问题，一点心事，先说一说，再一起想办法。'}</p></div>}
        {speaking && caption && <p className="vo-caption" aria-live="polite">{caption}</p>}{error && <p className="vo-error" role="alert">{error}</p>}
      </div>
      <div className="vo-compose"><label htmlFor="voice-orb-draft">{draft ? '确认文字后，再发送' : '可以说，也可以写'}</label><textarea ref={textArea} id="voice-orb-draft" rows={3} value={draft} maxLength={2000} disabled={recording || waiting} onChange={event => setDraft(event.target.value)} placeholder={phase === 'transcribing' ? '正在识别录音…' : '录音会先转成文字，不会自动发送。'} />
        <div className="vo-actions"><button className={`vo-record ${recording ? 'active' : ''}`} disabled={waiting || phase === 'starting' || otherMicrophone || !capabilities.asrAvailable || (!recording && !canRecordClassroomAudio())} onClick={() => void toggleRecording()}>{phase === 'recording' ? `■ 说好了 ${duration}s` : phase === 'starting' ? '打开麦克风…' : '● 说一说'}</button>{(recording || waiting) && <button className="vo-cancel" onClick={cancelActivity}>取消</button>}<button className="vo-send" disabled={!draft.trim() || recording || waiting || otherMicrophone} onClick={() => void send()}>{phase === 'thinking' ? '想一想…' : '确认发送 ↑'}</button></div>
        <div className="vo-options"><label><input type="checkbox" checked={autoRead} onChange={event => setAutoRead(event.target.checked)} />回答后朗读</label>{capabilities.voiceName && <span>{capabilities.voiceName}</span>}<label className="vo-upload">上传 WAV<input type="file" accept=".wav,audio/wav" disabled={recording || waiting || !capabilities.asrAvailable} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; const epoch = ++run.current; busy.current = true; audio.current?.stop(); void recognize(file, epoch); }} /></label></div>
        <p className="vo-note">{otherMicrophone ? '另一处正在使用语音输入，请先完成或取消。' : !capabilities.asrAvailable ? '语音识别暂不可用，仍可输入文字。' : !canRecordClassroomAudio() ? '录音需要 HTTPS 与麦克风权限，也可上传 WAV。' : '最多录音 60 秒；你确认文字后才会发给 AI。'}</p>
      </div>
    </section>}
    <button ref={orbButton} className="vo-orb-button" aria-label={open ? '收起小禾语音助手' : '打开小禾语音助手'} aria-expanded={open} onClick={() => open ? close() : setOpen(true)}><span className="vo-petal petal-one" /><span className="vo-petal petal-two" /><span className="vo-core" aria-hidden="true">禾</span><span className="vo-wave" aria-hidden="true"><i /><i /><i /></span></button><span className="vo-dock-label" aria-live="polite">{label}</span>
  </div>;
}
