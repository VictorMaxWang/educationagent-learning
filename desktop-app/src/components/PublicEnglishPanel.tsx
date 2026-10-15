import { useCallback, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { API_BASE_URL } from '../api/baseUrl';
import { audioCoordinator, audioOwner, type AudioLease } from '../classroom/AudioCoordinator';
import { canRecordClassroomAudio, startClassroomRecording, type ClassroomRecording } from '../classroom/recording';
import { useAudioFocus } from '../classroom/useAudioFocus';
import './PublicEnglishPanel.css';

type Mode = 'dialogue' | 'grammar' | 'pronunciation';
type Job = 'presets' | 'dialogue' | 'grammar' | 'evaluate';
type Preset = { text: string; type: string };
type DialogueLine = { role: string; text: string; cn: string };
type Dialogue = { title: string; lines: DialogueLine[] };
type GrammarQuestion = { question: string; options: string[]; answerIndex: number; explanation: string };
type Evaluation = { score: number; feedback: string; scores?: { accuracy?: number; fluency?: number; integrity?: number }; perWord?: { word?: string; accuracy?: number }[] };
type ApiResult = { code?: number; msg?: string; error?: string; [key: string]: unknown };
const ENDPOINTS = {
  presets: '/api/maimemo/speech-preset', dialogue: '/api/maimemo/speech-dialogue',
  grammar: '/api/maimemo/grammar-quiz', evaluate: '/api/maimemo/speech-evaluate',
} as const;
const SCENARIOS = ['向老师请教问题', '向新同学介绍家乡', '在图书馆借书', '和朋友讨论周末计划'];
const TOPICS = ['一般现在时', '一般过去时', '现在进行时', '主谓一致', '冠词与代词', '疑问句'];
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const textOf = (value: unknown, limit = 12000) => typeof value === 'string' ? value.slice(0, limit) : '';

/** 公开入口仅使用无共享词库读写的端点，不复用旧页面的词库、查词或同步流程。 */
async function englishRequest(job: Job, payload: Record<string, unknown> | undefined, signal: AbortSignal): Promise<ApiResult> {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  signal.addEventListener('abort', abort, { once: true });
  if (signal.aborted) abort();
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, job === 'presets' ? 20000 : 90000);
  try {
    const response = await fetch(`${API_BASE_URL}${ENDPOINTS[job]}`, {
      method: payload ? 'POST' : 'GET', headers: payload ? { 'Content-Type': 'application/json' } : undefined,
      body: payload ? JSON.stringify(payload) : undefined, signal: controller.signal,
    });
    const body: unknown = await response.json().catch(() => null);
    if (!response.ok || !record(body) || body.code !== 200) throw new Error(record(body) ? textOf(body.msg || body.error || body.message, 500) || `请求失败（${response.status}）` : `服务暂未返回有效结果（${response.status}）`);
    return body as ApiResult;
  } catch (error) {
    if (timedOut) throw new Error('等待时间较长，已停止本次等待。可以稍后重试。');
    throw error;
  } finally { clearTimeout(timer); signal.removeEventListener('abort', abort); }
}

/** 按 RIFF chunk 解析 PCM，避免把 WAV 头或扩展 chunk 当作语音发给评测接口。 */
async function wavForEvaluation(blob: Blob): Promise<{ audioBase64: string; duration: number }> {
  const buffer = await blob.arrayBuffer();
  const bytes = new Uint8Array(buffer); const view = new DataView(buffer);
  const tag = (offset: number) => String.fromCharCode(...bytes.subarray(offset, offset + 4));
  if (bytes.length < 44 || tag(0) !== 'RIFF' || tag(8) !== 'WAVE') throw new Error('录音格式无效，请重新录音');
  let validFormat = false; let pcm: Uint8Array | undefined;
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const length = view.getUint32(offset + 4, true); const start = offset + 8;
    if (start + length > bytes.length) throw new Error('录音文件不完整，请重新录音');
    if (tag(offset) === 'fmt ' && length >= 16) validFormat = view.getUint16(start, true) === 1 && view.getUint16(start + 2, true) === 1 && view.getUint32(start + 4, true) === 16000 && view.getUint16(start + 14, true) === 16;
    if (tag(offset) === 'data') pcm = bytes.subarray(start, start + length);
    offset = start + length + length % 2;
  }
  if (!validFormat || !pcm || pcm.length % 2 || pcm.length < 9600) throw new Error('请至少录制一小段完整朗读，再停止录音');
  let binary = '';
  for (let offset = 0; offset < pcm.length; offset += 8192) binary += String.fromCharCode(...pcm.subarray(offset, offset + 8192));
  return { audioBase64: btoa(binary), duration: pcm.length / 32000 };
}

function ReadableMarkdown({ children }: { children: string }) {
  return <ReactMarkdown skipHtml components={{ img: ({ alt }) => <span>{alt || ''}</span>, a: ({ children: label }) => <span>{label}</span> }}>{children}</ReactMarkdown>;
}

export default function PublicEnglishPanel({ onClose }: { onClose?(): void } = {}) {
  const [mode, setMode] = useState<Mode>('dialogue');
  const [busy, setBusy] = useState<Job | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [presets, setPresets] = useState<Preset[]>([]);
  const [scenario, setScenario] = useState(SCENARIOS[0]);
  const [length, setLength] = useState<'short' | 'standard' | 'long'>('short');
  const [dialogue, setDialogue] = useState<Dialogue | null>(null);
  const [chosenLine, setChosenLine] = useState<number | null>(null);
  const [topic, setTopic] = useState(TOPICS[0]);
  const [rules, setRules] = useState('');
  const [questions, setQuestions] = useState<GrammarQuestion[]>([]);
  const [answers, setAnswers] = useState<Record<number, number>>({});
  const [questionIndex, setQuestionIndex] = useState(0);
  const [targetText, setTargetText] = useState('Nice to meet you.');
  const [micPhase, setMicPhase] = useState<'idle' | 'requesting' | 'recording' | 'processing'>('idle');
  const [elapsed, setElapsed] = useState(0);
  const [clip, setClip] = useState<{ audioBase64: string; duration: number; text: string; url: string } | null>(null);
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const mounted = useRef(true);
  const task = useRef<AbortController | null>(null);
  const retry = useRef<(() => void) | null>(null);
  const recorder = useRef<ClassroomRecording | null>(null);
  const micRequest = useRef<AbortController | null>(null);
  const micRun = useRef(0);
  const processedRun = useRef(-1);
  const clipUrl = useRef('');
  const audio = useRef<HTMLAudioElement | null>(null);
  const playback = useRef<AudioLease | null>(null);
  const playbackOwner = useRef(audioOwner('public-english'));
  const focus = useAudioFocus();
  const microphoneBusy = micPhase !== 'idle';

  const cancelRecording = useCallback(() => {
    micRun.current++; micRequest.current?.abort(); micRequest.current = null;
    recorder.current?.cancel(); recorder.current = null;
    if (mounted.current) setMicPhase('idle');
  }, []);
  const clearClip = useCallback(() => {
    audio.current?.pause(); playback.current?.release(); playback.current = null;
    if (clipUrl.current) URL.revokeObjectURL(clipUrl.current); clipUrl.current = '';
    setClip(null); setEvaluation(null);
  }, []);
  const perform: (job: Job, payload: Record<string, unknown> | undefined, apply: (value: ApiResult) => void) => Promise<void> = useCallback(async (job: Job, payload: Record<string, unknown> | undefined, apply: (value: ApiResult) => void) => {
    task.current?.abort(); const controller = new AbortController(); task.current = controller;
    setBusy(job); setError(''); setNotice('');
    retry.current = () => { void perform(job, payload, apply); };
    try {
      const result = await englishRequest(job, payload, controller.signal);
      if (mounted.current && task.current === controller && !controller.signal.aborted) { apply(result); retry.current = null; }
    } catch (cause) {
      if (mounted.current && task.current === controller && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '请求失败，请稍后重试');
    } finally { if (mounted.current && task.current === controller) { task.current = null; setBusy(null); } }
  }, []);
  const loadPresets = useCallback(() => void perform('presets', undefined, result => {
    const items = Array.isArray(result.items) ? result.items.filter(record).map(item => ({ text: textOf(item.text, 1500), type: textOf(item.type, 20) })).filter(item => item.text) : [];
    if (!items.length) throw new Error('练习文本暂未加载，可以稍后重试或输入自己的句子');
    setPresets(items);
  }), [perform]);

  useEffect(() => {
    mounted.current = true; loadPresets();
    return () => {
      mounted.current = false; task.current?.abort(); cancelRecording(); audio.current?.pause(); playback.current?.release();
      if (clipUrl.current) URL.revokeObjectURL(clipUrl.current);
    };
  }, [loadPresets, cancelRecording]);

  const cancelRequest = () => { task.current?.abort(); task.current = null; setBusy(null); setNotice('已取消等待，可以重新开始。'); };
  const switchMode = (next: Mode) => {
    task.current?.abort(); task.current = null; setBusy(null); cancelRecording(); audio.current?.pause();
    setMode(next); setError(''); setNotice(''); retry.current = null;
  };
  const chooseText = (text: string, lineIndex?: number) => {
    cancelRecording(); clearClip(); setTargetText(text); setChosenLine(lineIndex ?? null); setError(''); setNotice('');
  };
  const generateDialogue = () => {
    if (!scenario.trim()) return;
    cancelRecording(); clearClip();
    void perform('dialogue', { scenario: scenario.trim(), length }, result => {
      const lines = Array.isArray(result.lines) ? result.lines.filter(record).map(line => ({ role: textOf(line.role, 20).toUpperCase(), text: textOf(line.text, 1500), cn: textOf(line.cn, 1500) })).filter(line => line.text) : [];
      if (!lines.length) throw new Error('这次没有生成可练习的对话，请再试一次');
      setDialogue({ title: textOf(result.title, 200) || scenario, lines });
      const first = lines.findIndex(line => line.role === 'B'); setChosenLine(first >= 0 ? first : null);
      if (first >= 0) setTargetText(lines[first].text);
    });
  };
  const generateGrammar = () => {
    if (!topic.trim()) return;
    void perform('grammar', { topic: topic.trim() }, result => {
      const next = Array.isArray(result.questions) ? result.questions.filter(record).flatMap(item => {
        // 不删除个别坏选项后继续判分，否则 answerIndex 可能指向另一选项。
        const options = Array.isArray(item.options) && item.options.every(option => typeof option === 'string') ? item.options as string[] : [];
        const answerIndex = item.answerIndex;
        if (!textOf(item.question) || options.length < 2 || !Number.isInteger(answerIndex) || Number(answerIndex) < 0 || Number(answerIndex) >= options.length) return [];
        return [{ question: textOf(item.question), options, answerIndex: Number(answerIndex), explanation: textOf(item.explanation) }];
      }) : [];
      if (!next.length) throw new Error('题目数据暂不完整，请重新生成');
      setRules(textOf(result.rules)); setQuestions(next); setAnswers({}); setQuestionIndex(0);
    });
  };

  const finishRecording = useCallback(async (blob: Blob, run: number, text: string) => {
    if (!mounted.current || run !== micRun.current || processedRun.current === run) return;
    processedRun.current = run; recorder.current = null; micRequest.current = null; setMicPhase('processing');
    try {
      const result = await wavForEvaluation(blob);
      if (!mounted.current || run !== micRun.current) return;
      if (clipUrl.current) URL.revokeObjectURL(clipUrl.current);
      const url = URL.createObjectURL(blob); clipUrl.current = url; setClip({ ...result, text, url });
      setNotice('录音完成。你可以先试听，再点击“提交发音评测”。');
    } catch (cause) { if (mounted.current && run === micRun.current) setError(cause instanceof Error ? cause.message : '录音处理失败'); }
    finally { if (mounted.current && run === micRun.current) setMicPhase('idle'); }
  }, []);
  const beginRecording = async () => {
    if (!targetText.trim() || microphoneBusy || busy || focus.microphoneBlocked) return;
    cancelRecording(); clearClip(); setError(''); setNotice(''); setElapsed(0); setMicPhase('requesting');
    const run = micRun.current; const text = targetText.trim(); const controller = new AbortController(); micRequest.current = controller;
    try {
      const recording = await startClassroomRecording({
        maxDurationSeconds: 30, signal: controller.signal,
        onDuration: value => { if (mounted.current && run === micRun.current) setElapsed(value); },
        onAutoStop: blob => { void finishRecording(blob, run, text); },
        onError: cause => { if (mounted.current && run === micRun.current) { cancelRecording(); setError(cause.message); } },
      });
      if (!mounted.current || run !== micRun.current || controller.signal.aborted) { recording.cancel(); return; }
      recorder.current = recording; setMicPhase('recording');
    } catch (cause) {
      if (mounted.current && run === micRun.current) { cancelRecording(); setError(cause instanceof Error ? cause.message : '麦克风暂时不可用'); }
    }
  };
  const stopRecording = async () => {
    const recording = recorder.current; const run = micRun.current;
    if (!recording) return;
    recorder.current = null;
    try { await finishRecording(await recording.stop(), run, targetText.trim()); }
    catch (cause) { if (mounted.current && run === micRun.current) { cancelRecording(); setError(cause instanceof Error ? cause.message : '录音停止失败'); } }
  };
  const evaluate = () => {
    if (!clip) return;
    audio.current?.pause(); setEvaluation(null);
    void perform('evaluate', { text: clip.text, audioBase64: clip.audioBase64 }, result => {
      if (result.rejected === true) { setEvaluation(null); setNotice(textOf(result.msg, 500) || '这次录音未通过评测，请调整后重新录音。'); return; }
      if (typeof result.score !== 'number' || !Number.isFinite(result.score)) throw new Error('评测没有返回有效分数，请重试');
      setEvaluation({ score: result.score, feedback: textOf(result.feedback), scores: record(result.scores) ? result.scores : undefined, perWord: Array.isArray(result.perWord) ? result.perWord.filter(record) : undefined });
    });
  };
  const attachAudio = useCallback((node: HTMLAudioElement | null) => {
    if (!node) { audio.current?.pause(); playback.current?.release(); playback.current = null; }
    audio.current = node; if (node) audioCoordinator.markManagedMedia(node);
  }, []);
  const stopPreview = () => { playback.current?.release(); playback.current = null; };
  const preview = () => {
    if (playback.current?.isCurrent()) return;
    const lease = audioCoordinator.acquirePlayback(playbackOwner.current, () => audio.current?.pause());
    if (!lease) { audio.current?.pause(); return; } playback.current = lease;
  };
  const question = questions[questionIndex];
  const selectedAnswer = answers[questionIndex];
  const completed = questions.length > 0 && Object.keys(answers).length === questions.length;
  const score = questions.filter((item, index) => answers[index] === item.answerIndex).length;

  const pronunciation = <section className="public-english-card public-english-pronunciation" aria-label="跟读评测">
    <div className="public-english-card-heading"><div><span className="public-english-kicker">READ ALOUD</span><h3>大胆读出来</h3></div><span className="public-english-badge">每次最多 30 秒</span></div>
    <label className="public-english-field">准备朗读的英文<textarea value={targetText} maxLength={1500} spellCheck={false} disabled={microphoneBusy || busy === 'evaluate'} onChange={event => chooseText(event.target.value)} /></label>
    <div className="public-english-actions">
      {micPhase === 'recording' ? <button className="public-english-primary" onClick={() => void stopRecording()}>停止录音 · {Math.floor(elapsed)} 秒</button> : <button className="public-english-primary" onClick={() => void beginRecording()} disabled={microphoneBusy || !!busy || !targetText.trim() || focus.microphoneBlocked || !canRecordClassroomAudio()}>{micPhase === 'requesting' ? '等待麦克风授权…' : micPhase === 'processing' ? '正在整理录音…' : '开始录音'}</button>}
      {microphoneBusy && <button onClick={cancelRecording}>取消录音</button>}
      {clip && !microphoneBusy && <><button onClick={evaluate} disabled={!!busy}>提交发音评测</button><button onClick={clearClip} disabled={!!busy}>重新录制</button></>}
    </div>
    {!canRecordClassroomAudio() && <p className="public-english-muted">当前页面不能使用麦克风。请通过 HTTPS 或本机打开；情景对话和语法练习仍可使用。</p>}
    {focus.microphoneBlocked && !microphoneBusy && <p className="public-english-muted">另一处正在使用麦克风，完成后可以开始跟读。</p>}
    {clip && <div className="public-english-preview"><span>本次录音 · {clip.duration.toFixed(1)} 秒</span><audio ref={attachAudio} controls preload="metadata" src={clip.url} onPlay={preview} onPause={stopPreview} onEnded={stopPreview} /></div>}
    {evaluation && <div className="public-english-evaluation" aria-live="polite"><div className="public-english-score"><strong>{Math.round(evaluation.score)}</strong><span>/ 100 · 发音评测</span></div>
      {evaluation.scores && <div className="public-english-dimensions">{([['accuracy', '准确度'], ['fluency', '流利度'], ['integrity', '完整度']] as const).map(([key, label]) => typeof evaluation.scores?.[key] === 'number' && <span key={key}>{label} <b>{evaluation.scores[key]!.toFixed(1)}</b> / 5</span>)}</div>}
      <ReadableMarkdown>{evaluation.feedback}</ReadableMarkdown>
    </div>}
  </section>;

  return <section className="public-english-panel" aria-label="英语练习">
    <header className="public-english-header"><div><span className="public-english-kicker">A LITTLE PRACTICE, EVERY DAY</span><h2>让英语，离生活近一点</h2><p>练一段对话，弄懂一个语法点，慢慢找到开口的信心。</p></div>{onClose && <button aria-label="关闭英语练习" onClick={onClose}>×</button>}</header>
    <nav className="public-english-tabs" aria-label="英语练习类型">{([['dialogue', '情景对话'], ['grammar', '语法练习'], ['pronunciation', '跟读评测']] as const).map(([value, label]) => <button key={value} aria-pressed={mode === value} className={mode === value ? 'active' : ''} onClick={() => switchMode(value)}>{label}</button>)}</nav>
    {busy && <div className="public-english-status" role="status"><span>{busy === 'presets' ? '正在准备练习文本…' : busy === 'dialogue' ? '正在编写情景对话…' : busy === 'grammar' ? '正在准备讲解与练习…' : '正在分析这次朗读…'}</span><button onClick={cancelRequest}>取消等待</button></div>}
    {error && <div className="public-english-error" role="alert"><span>{error}</span>{retry.current && <button onClick={() => retry.current?.()} disabled={!!busy}>重试</button>}</div>}
    {notice && <p className="public-english-notice" role="status">{notice}</p>}

    {mode === 'dialogue' && <div className="public-english-grid">
      <section className="public-english-card"><span className="public-english-kicker">LET'S TALK</span><h3>选一个想练习的场景</h3>
        <div className="public-english-chips">{SCENARIOS.map(item => <button key={item} className={scenario === item ? 'active' : ''} disabled={!!busy || microphoneBusy} onClick={() => setScenario(item)}>{item}</button>)}</div>
        <label className="public-english-field">也可以写自己的场景<input value={scenario} maxLength={160} disabled={!!busy || microphoneBusy} onChange={event => setScenario(event.target.value)} /></label>
        <div className="public-english-actions"><label className="public-english-length">篇幅<select value={length} disabled={!!busy || microphoneBusy} onChange={event => setLength(event.target.value as typeof length)}><option value="short">短对话</option><option value="standard">标准</option><option value="long">完整情景</option></select></label><button className="public-english-primary" disabled={!!busy || microphoneBusy || !scenario.trim()} onClick={generateDialogue}>生成对话</button></div>
        {dialogue ? <div className="public-english-dialogue"><h3>{dialogue.title}</h3><p className="public-english-muted">你来扮演 B。选择一句英文，可以在右侧跟读。</p><ol>{dialogue.lines.map((line, index) => <li key={index} className={`${line.role === 'B' ? 'is-student' : ''} ${chosenLine === index ? 'is-selected' : ''}`}><span className="public-english-role">{line.role === 'B' ? '你 · B' : '搭档 · A'}</span><p lang="en">{line.text}</p>{line.cn && <small>{line.cn}</small>}<button disabled={!!busy || microphoneBusy} onClick={() => chooseText(line.text, index)}>{chosenLine === index ? '正在练习这句' : '练习这句'}</button></li>)}</ol></div> : <p className="public-english-empty">先生成一段 A / B 双人对话，再挑一句开始练习。</p>}
      </section>{pronunciation}
    </div>}

    {mode === 'grammar' && <><section className="public-english-card"><span className="public-english-kicker">ONE IDEA AT A TIME</span><h3>今天弄懂哪个语法点？</h3><div className="public-english-chips">{TOPICS.map(item => <button key={item} disabled={!!busy} className={topic === item ? 'active' : ''} onClick={() => setTopic(item)}>{item}</button>)}</div><form className="public-english-topic-form" onSubmit={event => { event.preventDefault(); generateGrammar(); }}><input aria-label="语法点" value={topic} maxLength={120} disabled={!!busy} onChange={event => setTopic(event.target.value)} /><button className="public-english-primary" disabled={!!busy || !topic.trim()}>开始练习</button></form></section>
      {!!questions.length && <div className="public-english-grid"><section className="public-english-card public-english-rules"><h3>先理解，再练习</h3><ReadableMarkdown>{rules}</ReadableMarkdown></section><section className="public-english-card"><div className="public-english-card-heading"><h3>试一试</h3><span className="public-english-badge">第 {questionIndex + 1} / {questions.length} 题</span></div>{question && <><p className="public-english-question" lang="en">{question.question}</p><div className="public-english-options">{question.options.map((option, index) => <button key={index} disabled={selectedAnswer !== undefined || !!busy} className={selectedAnswer === undefined ? '' : index === question.answerIndex ? 'is-correct' : selectedAnswer === index ? 'is-incorrect' : ''} onClick={() => setAnswers(previous => previous[questionIndex] !== undefined ? previous : { ...previous, [questionIndex]: index })}><span>{String.fromCharCode(65 + index)}</span>{option}</button>)}</div>{selectedAnswer !== undefined && <div className="public-english-feedback"><strong>{selectedAnswer === question.answerIndex ? '回答正确' : `正确答案：${String.fromCharCode(65 + question.answerIndex)}`}</strong><p>{question.explanation}</p></div>}<div className="public-english-actions"><button disabled={questionIndex === 0} onClick={() => setQuestionIndex(index => index - 1)}>上一题</button><button disabled={questionIndex >= questions.length - 1 || selectedAnswer === undefined} onClick={() => setQuestionIndex(index => index + 1)}>下一题</button></div></>}{completed && <div className="public-english-summary" role="status"><strong>这组练习完成了 · 答对 {score} / {questions.length}</strong><button disabled={!!busy} onClick={generateGrammar}>再练一组</button></div>}</section></div>}
    </>}

    {mode === 'pronunciation' && <div className="public-english-grid"><section className="public-english-card"><span className="public-english-kicker">SMALL SENTENCES, REAL PROGRESS</span><h3>从一句熟悉的话开始</h3>{presets.length ? <div className="public-english-presets">{presets.map((item, index) => <button key={index} className={targetText === item.text ? 'active' : ''} disabled={!!busy || microphoneBusy} onClick={() => chooseText(item.text)}><small>{item.type}</small><span lang="en">{item.text}</span></button>)}</div> : <div className="public-english-empty"><p>可以直接输入想练习的英文，也可以重新加载预设文本。</p><button onClick={loadPresets} disabled={!!busy}>加载练习文本</button></div>}</section>{pronunciation}</div>}
    <p className="public-english-footer">本页保留当前打开期间的练习。关闭后可重新开始。</p>
  </section>;
}
