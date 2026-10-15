import { Component, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { SlideCanvas, type SlideEffects } from '@educationagent/renderer';
import '@educationagent/renderer/styles.css';
import { audioUrl, synthesizeSpeech, voiceHealth } from './api';
import { NarrationQueue, type PlaybackStatus } from './audioQueue';
import { clampIndex } from './navigation';
import { TYPE_LABELS, type Course, type Scene, type Speaker } from './types';
import QuizScene from './QuizScene';
import ClassroomConversation from './ClassroomConversation';

class SceneBoundary extends Component<{ children: ReactNode }, { error: boolean }> {
  state = { error: false };
  static getDerivedStateFromError() { return { error: true }; }
  render() { return this.state.error ? <div className="cr-empty">这一页暂时无法展示。你仍可以查看讲解文字、提问或学习下一页。</div> : this.props.children; }
}
/** 互动脚本在独立沙箱中运行，不授予同源、弹窗、表单、网络或页面导航能力。 */
export function sandboxedHtml(html: string): string {
  return `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'"><meta name="referrer" content="no-referrer">${html}`;
}
function InteractiveScene({ scene }: { scene: Scene }) {
  const container = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const observer = new ResizeObserver(entries => setWidth(entries[0].contentRect.width));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return <div className="cr-interactive-slot" ref={container}><iframe title={scene.title} sandbox="allow-scripts" referrerPolicy="no-referrer" srcDoc={sandboxedHtml(scene.content.html || '')} style={{ width: 1280, height: 720, transform: `scale(${width / 1280})`, transformOrigin: 'top left' }} /></div>;
}

export default function CoursePlayer({ course, onProgress }: {
  course: Course; onProgress(courseId: string, sceneIndex: number, actionIndex: number): void;
}) {
  const [index, setIndex] = useState(() => clampIndex(course.progress?.sceneIndex || 0, course.scenes.length));
  const [actionIndex, setActionIndex] = useState(course.progress?.actionIndex || 0);
  const [playback, setPlayback] = useState<PlaybackStatus>('idle');
  const [caption, setCaption] = useState('');
  const [captionRole, setCaptionRole] = useState<Speaker>('teacher');
  const [error, setError] = useState('');
  const [effects, setEffects] = useState<SlideEffects>({});
  const [discussion, setDiscussion] = useState('');
  const [rate, setRate] = useState(1);
  const [voice, setVoice] = useState({ ttsAvailable: false, asrAvailable: false });
  const [recordingBusy, setRecordingBusy] = useState(false);
  const recordingBusyRef = useRef(false);
  const [mobilePanel, setMobilePanel] = useState<'lesson' | 'conversation'>('lesson');
  const progress = useRef({ index, courseId: course.id });
  const queue = useRef<NarrationQueue | null>(null);
  const readingResponse = useRef(false);
  const scene = course.scenes[clampIndex(index, course.scenes.length)];
  progress.current = { index, courseId: course.id };
  const persist = useCallback((sceneIndex: number, nextAction: number) => {
    // 父视图在切换复习/成长时仍存在，由它保存位置，避免组件卸载取消最后一次写入。
    onProgress(course.id, sceneIndex, nextAction);
  }, [course.id, onProgress]);
  if (!queue.current) queue.current = new NarrationQueue({
    synthesize: synthesizeSpeech, resolveUrl: audioUrl,
    onStatus: value => {
      if (value === 'done' && readingResponse.current) { readingResponse.current = false; setPlayback('idle'); }
      else setPlayback(value);
    }, onError: setError,
    onCaption: (text, role) => { setCaption(text); setCaptionRole(role); },
    onDiscussion: (text, nextAction) => { setDiscussion(text); setActionIndex(nextAction); persist(progress.current.index, nextAction); },
    onAction: (nextAction, action) => {
      setActionIndex(nextAction);
      persist(progress.current.index, nextAction);
      if (action.type === 'spotlight' && action.elementId) setEffects(previous => ({ ...previous, spotlight: { elementId: action.elementId! } }));
      if (action.type === 'laser' && action.elementId) setEffects(previous => ({ ...previous, laser: { elementId: action.elementId!, color: typeof action.color === 'string' ? action.color : undefined } }));
    },
  });
  useEffect(() => {
    let active = true;
    void voiceHealth().then(value => { if (active) setVoice(value); }).catch(() => {});
    return () => { active = false; queue.current?.destroy(); };
  }, []);
  const stop = useCallback(() => queue.current?.stop(), []);
  const onRecordingBusyChange = useCallback((busy: boolean) => {
    recordingBusyRef.current = busy;
    queue.current?.setMicrophoneBusy(busy);
    setRecordingBusy(busy);
    if (busy) readingResponse.current = false;
  }, []);
  const go = (next: number) => {
    queue.current?.stop();
    const target = clampIndex(next, course.scenes.length);
    setIndex(target); setActionIndex(0); setCaption(''); setDiscussion(''); setEffects({}); setError(''); setMobilePanel('lesson');
    persist(target, 0);
  };
  const togglePlay = () => {
    if (!scene || recordingBusyRef.current) return;
    if (playback === 'paused') { queue.current?.resume(); return; }
    if (playback === 'playing' || playback === 'loading') { queue.current?.pause(); return; }
    setDiscussion(''); readingResponse.current = false;
    void queue.current?.play(scene.actions || [], playback === 'done' ? 0 : actionIndex);
  };
  const speech = (scene?.actions || []).filter(action => action.type === 'speech' || action.type === 'discussion');
  if (!scene) return <div className="cr-empty">课堂还在准备中，第一步完成后就会出现在这里。</div>;
  return <div className="cr-player">
    <div className="cr-mobile-panels"><button aria-pressed={mobilePanel === 'lesson'} onClick={() => setMobilePanel('lesson')}>学习内容</button><button aria-pressed={mobilePanel === 'conversation'} onClick={() => setMobilePanel('conversation')}>老师与学伴</button></div>
    <div className={`cr-lesson-column ${mobilePanel === 'lesson' ? 'mobile-active' : ''}`}>
      <div className="cr-step-heading"><div><span className="cr-eyebrow">第 {index + 1} / {course.outlines.length || course.scenes.length} 步 · {TYPE_LABELS[scene.type]}</span><h2>{scene.title}</h2></div><span className="cr-pill">慢慢来，也能学会</span></div>
      <SceneBoundary key={scene.id}><div className={`cr-stage cr-stage-${scene.type}`}>
        {scene.type === 'slide' && scene.content.canvas && <SlideCanvas slide={scene.content.canvas} effects={effects} />}
        {scene.type === 'quiz' && <QuizScene key={scene.id} courseId={course.id} scene={scene} />}
        {scene.type === 'interactive' && <InteractiveScene scene={scene} />}
      </div></SceneBoundary>
      <div className="cr-caption" aria-live="polite"><span>{captionRole === 'peer' ? '学伴' : '老师'}</span><p>{caption || String(speech[0]?.text || speech[0]?.prompt || '可以先阅读这一页，也可以点击开始听课。')}</p></div>
      <div className="cr-player-controls"><div className="cr-inline"><button className="cr-button" onClick={togglePlay} disabled={recordingBusy || !voice.ttsAvailable || !speech.length}>{recordingBusy ? '请先完成录音' : playback === 'playing' ? 'Ⅱ 暂停讲解' : playback === 'loading' ? 'Ⅱ 暂停等待' : playback === 'paused' ? '▶ 继续听课' : playback === 'done' ? '↻ 再听一遍' : '▶ 开始听课'}</button><label className="cr-speed">语速<select value={rate} onChange={event => { const value = Number(event.target.value); setRate(value); queue.current?.setRate(value); }}><option value={0.8}>0.8×</option><option value={1}>1.0×</option><option value={1.2}>1.2×</option></select></label></div><div className="cr-inline"><button className="cr-button secondary" disabled={index === 0} onClick={() => go(index - 1)}>← 上一步</button><button className="cr-button secondary" disabled={index >= course.scenes.length - 1} onClick={() => go(index + 1)}>下一步 →</button></div></div>
      {recordingBusy && <p className="cr-muted" role="status">麦克风正在使用，讲解和回答朗读已暂停。</p>}
      {error && <p className="cr-error" role="alert">{error}</p>}
      {!voice.ttsAvailable && <p className="cr-muted">语音讲解暂不可用，你可以阅读完整讲解文字并继续练习。</p>}
      {playback === 'done' && <p className="cr-muted" role="status">这一页讲完了。可以提问、完成练习，再选择下一步。</p>}
      {discussion && <div className="cr-discussion-card"><strong>停下来，一起想一想</strong><p>{discussion}</p><button className="cr-link" onClick={() => setMobilePanel('conversation')}>去问问学伴 →</button><span>讨论后再点“开始听课”继续讲解。</span></div>}
      <details className="cr-transcript"><summary>查看本页完整讲解</summary>{speech.map((action, i) => <p key={i}>{String(action.text || action.prompt || action.topic || '')}</p>)}</details>
      <nav className="cr-timeline" aria-label="课堂目录">{course.outlines.map((outline, i) => { const available = course.scenes.some(item => item.id === outline.id); return <button key={outline.id} className={i === index ? 'active' : ''} disabled={!available} onClick={() => go(course.scenes.findIndex(item => item.id === outline.id))}><span>{String(i + 1).padStart(2, '0')}</span><div><strong>{outline.title}</strong><small>{available ? TYPE_LABELS[outline.type] : '准备中'}</small></div></button>; })}</nav>
    </div>
    <div className={`cr-conversation-column ${mobilePanel === 'conversation' ? 'mobile-active' : ''}`}><ClassroomConversation key={course.id} course={course} sceneId={scene.id} onInterrupt={stop} onRecordingBusyChange={onRecordingBusyChange} asrAvailable={voice.asrAvailable} discussionPrompt={discussion} onReadResponse={voice.ttsAvailable ? (text, role) => { if (recordingBusyRef.current) return; readingResponse.current = true; void queue.current?.readResponse(text, role); } : undefined} /></div>
  </div>;
}
