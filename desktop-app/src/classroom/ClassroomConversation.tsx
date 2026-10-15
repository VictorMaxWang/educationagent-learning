import { useCallback, useEffect, useRef, useState } from 'react';
import { askClassroom, transcribeAudio } from './api';
import { canRecordClassroomAudio, startClassroomRecording } from './recording';
import type { ClassroomMessage, Course, Speaker } from './types';
import { newLocalId } from './navigation';

export default function ClassroomConversation({ course, sceneId, onInterrupt, asrAvailable, discussionPrompt, onReadResponse, onRecordingBusyChange }: {
  course: Course; sceneId: string; onInterrupt(): void; asrAvailable: boolean; discussionPrompt?: string;
  onReadResponse?(text: string, role: Speaker): void;
  onRecordingBusyChange(busy: boolean): void;
}) {
  const [speaker, setSpeaker] = useState<Speaker>('teacher');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<ClassroomMessage[]>(course.messages || []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [recording, setRecording] = useState(false);
  const [recordingStarting, setRecordingStarting] = useState(false);
  const [duration, setDuration] = useState(0);
  const [transcribing, setTranscribing] = useState(false);
  const record = useRef<Awaited<ReturnType<typeof startClassroomRecording>> | null>(null);
  const request = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const captureBusy = useRef(false);
  const bottom = useRef<HTMLDivElement | null>(null);
  const setCaptureBusy = useCallback((value: boolean) => {
    captureBusy.current = value;
    onRecordingBusyChange(value);
  }, [onRecordingBusyChange]);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current++;
      record.current?.cancel();
      request.current?.abort();
    };
  }, []);
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'nearest' }); }, [messages.length]);
  useEffect(() => {
    setRecording(false); setRecordingStarting(false); setTranscribing(false); setBusy(false);
    return () => {
      generation.current++;
      record.current?.cancel(); record.current = null;
      request.current?.abort();
      setCaptureBusy(false);
    };
  }, [sceneId, setCaptureBusy]);

  const recognize = async (blob: Blob) => {
    if (!mounted.current) return;
    setRecording(false); setTranscribing(true); setError('');
    const controller = new AbortController();
    request.current = controller;
    try {
      const result = await transcribeAudio(blob, controller.signal);
      if (!controller.signal.aborted && mounted.current) setInput(result.text || '');
    } catch (error) {
      if (!controller.signal.aborted && mounted.current) setError(error instanceof Error ? error.message : '没有识别成功，请用文字输入。');
    } finally { if (mounted.current) setTranscribing(false); }
  };
  const toggleRecording = async () => {
    if (recording) {
      const recorder = record.current; record.current = null;
      const run = generation.current;
      try {
        const blob = await recorder?.stop();
        if (!mounted.current || run !== generation.current) return;
        // stop 已释放麦克风；识别上传期间允许恢复听课，不会再把声音录进去。
        setCaptureBusy(false); setRecording(false);
        if (blob) await recognize(blob);
      } catch (error) {
        if (mounted.current && run === generation.current) {
          setCaptureBusy(false); setRecording(false);
          setError(error instanceof Error ? error.message : '没有录到声音，请重新试一次。');
        }
      }
      return;
    }
    if (captureBusy.current || recordingStarting || transcribing || busy) return;
    // 在权限弹窗出现前同步加锁，不能等 React 的录音状态异步提交后才禁止听课。
    setCaptureBusy(true);
    onInterrupt(); setError(''); setRecordingStarting(true);
    const run = ++generation.current;
    try {
      const recorder = await startClassroomRecording({
        onDuration: seconds => { if (mounted.current && run === generation.current) setDuration(Math.floor(seconds)); },
        onAutoStop: blob => {
          if (!mounted.current || run !== generation.current) return;
          record.current = null; setCaptureBusy(false); void recognize(blob);
        },
        onError: error => {
          if (mounted.current && run === generation.current) {
            record.current = null; setCaptureBusy(false); setRecording(false); setError(error.message);
          }
        },
      });
      if (!mounted.current || run !== generation.current) { recorder.cancel(); return; }
      record.current = recorder; setRecording(true); setDuration(0);
    } catch (error) {
      if (mounted.current && run === generation.current) {
        setCaptureBusy(false); setError(error instanceof Error ? error.message : '无法访问麦克风');
      }
    } finally { if (mounted.current && run === generation.current) setRecordingStarting(false); }
  };
  const send = async (textOverride?: string, speakerOverride?: Speaker) => {
    if (!(textOverride || input).trim() || busy || captureBusy.current) return;
    onInterrupt(); setBusy(true); setError('');
    const text = (textOverride || input).trim();
    const controller = new AbortController(); request.current = controller;
    const question: ClassroomMessage = { id: newLocalId(), speaker: 'user', text, sceneId };
    setMessages(previous => [...previous, question]); setInput('');
    try {
      const response = await askClassroom(course.id, sceneId, text, speakerOverride || speaker, controller.signal);
      if (!controller.signal.aborted && mounted.current) setMessages(previous => [...previous, response]);
    } catch (error) {
      if (!controller.signal.aborted && mounted.current) {
        setError(error instanceof Error ? error.message : '回答暂时没有送达，请再试一次。');
        setInput(text);
        setMessages(previous => previous.filter(message => message.id !== question.id));
      }
    } finally { if (mounted.current) setBusy(false); }
  };
  return <aside className="cr-conversation" aria-label="课堂问答">
    <div className="cr-conversation-heading"><span className="cr-avatar">{speaker === 'teacher' ? '师' : '伴'}</span><div><h3>{speaker === 'teacher' ? '老师在这里' : '小禾 · AI 学伴'}</h3><p>{speaker === 'teacher' ? '哪里没听懂，都可以问' : '一起尝试，互相启发'}</p></div></div>
    <div className="cr-segmented"><button aria-pressed={speaker === 'teacher'} onClick={() => setSpeaker('teacher')}>问老师</button><button aria-pressed={speaker === 'peer'} onClick={() => setSpeaker('peer')}>和学伴讨论</button></div>
    {discussionPrompt && <button className="cr-discussion-invite" disabled={busy || recording || recordingStarting || transcribing} onClick={() => { setSpeaker('peer'); void send(discussionPrompt, 'peer'); }}>邀请学伴一起想一想 →</button>}
    <div className="cr-message-list" aria-live="polite">
      {!messages.length && <div className="cr-conversation-empty"><span>✦</span><p>不用急着给出正确答案。<br />把你的想法说出来，我们一步步来。</p><button onClick={() => setInput('能用一个生活中的例子，再讲讲这一页吗？')}>用生活中的例子讲一讲</button><button onClick={() => setInput('先给我一点提示，让我自己试一试。')}>先给我一点提示</button></div>}
      {messages.map((message, index) => <div key={message.id || index} className={`cr-message ${message.speaker === 'user' ? 'is-user' : ''}`}><strong>{message.speaker === 'user' ? '我' : message.speaker === 'peer' ? '小禾 · AI 学伴' : '老师'}</strong><p>{message.text}</p>{message.speaker !== 'user' && onReadResponse && <button className="cr-link" disabled={recording || recordingStarting || transcribing || busy} onClick={() => onReadResponse(message.text, message.speaker as Speaker)}>听一听这段回答</button>}</div>)}
      {busy && <p className="cr-muted" role="status">正在想一个适合你的讲法…</p>}<div ref={bottom} />
    </div>
    {error && <p className="cr-error" role="alert">{error}</p>}
    <div className="cr-compose"><label htmlFor="classroom-question" className="cr-visually-hidden">向老师或学伴提问</label><textarea id="classroom-question" rows={3} maxLength={2000} value={input} placeholder={transcribing ? '正在识别录音…' : '写下问题，或说说你的想法…'} disabled={busy || recording || recordingStarting || transcribing} onChange={event => setInput(event.target.value)} />
      <div className="cr-compose-actions"><button className={`cr-button secondary ${recording ? 'recording' : ''}`} disabled={busy || transcribing || recordingStarting || !asrAvailable || (!recording && !canRecordClassroomAudio())} onClick={() => void toggleRecording()}>{recording ? `■ 完成录音 ${duration}s` : recordingStarting ? '打开麦克风…' : '● 录音提问'}</button><button className="cr-button" disabled={!input.trim() || busy || recording || recordingStarting || transcribing} onClick={() => void send()}>发送 ↑</button></div>
      <p className="cr-recording-note">{recording ? '最长录音 60 秒，转成文字后由你确认发送。' : !asrAvailable ? '语音识别暂不可用，仍可输入文字提问。' : !canRecordClassroomAudio() ? '麦克风需要 HTTPS 或本机浏览器。可输入文字或上传 WAV 录音。' : '录音先转成文字，你确认后才会发送。'}</p>
      {asrAvailable && !recording && <label className="cr-upload-voice">上传 WAV 录音<input type="file" accept=".wav,audio/wav" disabled={busy || recordingStarting || transcribing} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) { onInterrupt(); void recognize(file); } }} /></label>}
    </div>
  </aside>;
}
