import { Component, useEffect, useRef, useState, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { SlideCanvas, type SlideEffects } from '@educationagent/renderer';
import '@educationagent/renderer/styles.css';
import './player.css';
import { validateEnvelope, type PlayerData, type PortableQuestion, type PortableScene } from './contract';
import { answerText, canonicalAnswer, QUESTION_TYPE_LABELS } from './quiz';
import InteractiveScene, { type InteractiveSceneHandle } from '../InteractiveScene';
import { applyWhiteboardAction } from '../whiteboardModel';
import type { WhiteboardState } from '../types';
import type { Slide } from '@educationagent/dsl';
import katex from 'katex';
import { playOfflineActions } from './playback';

/** 单文件只播放已导出的资源；这里不导入 API、账号或在线 Agent。 */
class PageBoundary extends Component<{ children: ReactNode }, { error: boolean }> {
  state = { error: false };
  static getDerivedStateFromError() { return { error: true }; }
  render() { return this.state.error ? <p role="alert">这一页的画布暂时无法显示，请阅读下方讲稿或继续下一页。</p> : this.props.children; }
}

function OfflineQuiz({ scene }: { scene: PortableScene }) {
  const [answers, setAnswers] = useState<Record<string, string[]>>({});
  const [checked, setChecked] = useState(false);
  const questions = scene.content.questions || [];
  const choose = (question: PortableQuestion, value: string) => {
    setChecked(false);
    setAnswers(previous => {
      const current = previous[question.id] || [];
      return { ...previous, [question.id]: question.type === 'multiple' ? current.includes(value) ? current.filter(item => item !== value) : [...current, value] : [value] };
    });
  };
  return <div className="ep-quiz">{questions.map((question, index) => {
    const reference = question.correctAnswer ?? question.answer;
    const correct = canonicalAnswer(reference, question.type);
    const actual = canonicalAnswer(answers[question.id], question.type);
    const choices = question.type === 'judge' ? [{ value: '对', label: '正确' }, { value: '错', label: '错误' }] : question.options || [];
    return <fieldset key={question.id || index}><legend>{index + 1}. {question.question}</legend><p className="ep-muted">{QUESTION_TYPE_LABELS[question.type]} · {question.type === 'multiple' ? '选择所有符合的选项' : question.type === 'short_answer' ? '结合参考答案和要点自查' : question.type === 'fill' ? '填写一个答案' : '选择一个答案'}</p>
      {question.type === 'short_answer' ? <textarea aria-label={`第 ${index + 1} 题答案`} rows={4} maxLength={4000} value={answers[question.id]?.[0] || ''} onChange={event => choose(question, event.target.value)} />
        : question.type === 'fill' ? <input aria-label={`第 ${index + 1} 题答案`} type="text" maxLength={2000} value={answers[question.id]?.[0] || ''} onChange={event => choose(question, event.target.value)} />
        : choices.map(option => <label key={option.value}><input type={question.type === 'multiple' ? 'checkbox' : 'radio'} name={`question-${question.id}`} checked={(answers[question.id] || []).includes(option.value)} onChange={() => choose(question, option.value)} /><span><strong>{option.value}.</strong> {option.label}</span></label>)}
      {checked && <div className={question.type !== 'short_answer' && correct && actual === correct ? 'ep-correct' : 'ep-feedback'}>{question.type === 'short_answer' ? '请对照参考答案和评分要点自查；离线未进行 AI 判分。' : !correct ? '本题未附标准答案，请对照讲稿复习。' : actual === correct ? '答对了。' : '再想一想。'}<p>参考答案：{answerText(reference) || '未附'}</p><p>{question.explanation || question.analysis || ''}</p>{!!question.gradingPoints?.length && <ul>{question.gradingPoints.map((point, i) => <li key={i}>{point}</li>)}</ul>}</div>}
    </fieldset>;
  })}<button onClick={() => setChecked(true)}>检查这一页</button><p className="ep-muted">离线练习仅在此页面核对答案，不会上传或写入在线成绩。</p></div>;
}

function displayCanvas(canvas: Slide): Slide {
  return { ...canvas, elements: canvas.elements.map(element => element.type === 'latex' && !element.html && !element.path ? { ...element, html: katex.renderToString(element.latex, { throwOnError: false, trust: false, strict: 'ignore', displayMode: true }) } : element) };
}

function Player({ data }: { data: PlayerData }) {
  const course = data.envelope.course;
  const [index, setIndex] = useState(0);
  const [caption, setCaption] = useState('');
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);
  const [effects, setEffects] = useState<SlideEffects>({});
  const [message, setMessage] = useState('');
  const [cursor, setCursor] = useState(0);
  const [demoBoard, setDemoBoard] = useState<WhiteboardState>();
  const [playWarnings, setPlayWarnings] = useState<string[]>([]);
  const [frameSerial, setFrameSerial] = useState(0);
  const activeAudio = useRef<HTMLAudioElement | null>(null);
  const job = useRef<AbortController | null>(null);
  const actionCursor = useRef(0);
  const currentBoard = useRef<WhiteboardState | undefined>(undefined);
  const interactive = useRef<InteractiveSceneHandle>(null);
  const scene = course.scenes[index];
  const stop = () => { job.current?.abort(); job.current = null; activeAudio.current?.pause(); activeAudio.current = null; setPlaying(false); };
  useEffect(() => () => { job.current?.abort(); activeAudio.current?.pause(); }, []);
  const navigate = (next: number) => {
    stop(); setIndex(Math.max(0, Math.min(course.scenes.length - 1, next))); setCaption(''); setMessage(''); setEffects({});
    actionCursor.current = 0; setCursor(0); currentBoard.current = undefined; setDemoBoard(undefined); setPlayWarnings([]); setFrameSerial(value => value + 1);
  };
  const play = async () => {
    if (playing) { stop(); return; }
    const controller = new AbortController(); job.current = controller;
    const actions = scene.actions || [];
    if (actionCursor.current >= actions.length) {
      actionCursor.current = 0; setCursor(0); currentBoard.current = undefined; setDemoBoard(undefined); setEffects({});
    }
    setPlaying(true); setMessage('');
    try {
      const result = await playOfflineActions(actions, actionCursor.current, {
        caption: setCaption,
        position: next => { actionCursor.current = next; setCursor(next); },
        hasAudio: i => Boolean(data.narration[scene.id]?.[String(i)]?.length),
        warning: text => setPlayWarnings(previous => [...new Set([...previous, text])]),
        visual: action => {
          if (action.type === 'spotlight' && action.elementId) setEffects(previous => ({ ...previous, spotlight: { elementId: action.elementId! } }));
          if (action.type === 'laser' && action.elementId) setEffects(previous => ({ ...previous, laser: { elementId: action.elementId! } }));
        },
        control: async (action, signal) => {
          if (action.type.startsWith('wb_')) {
            // 动画演示使用独立临时白板；导入的人工板书保留在下方教材区，不被从头播放覆盖。
            const next = applyWhiteboardAction(currentBoard.current, action, scene.content.canvas?.theme);
            currentBoard.current = next; setDemoBoard(next);
          } else {
            if (scene.type !== 'interactive' || !interactive.current) throw new Error('当前页面没有可执行的互动区域');
            const outcome = await interactive.current.execute(action, signal);
            if (!outcome.success) throw new Error(outcome.error || '互动页面没有确认操作');
          }
        },
        speech: async (i, signal) => {
          for (const segment of data.narration[scene.id]?.[String(i)] || []) {
            if (signal.aborted) throw new DOMException('播放已停止', 'AbortError');
            const audio = new Audio(segment.src); activeAudio.current = audio;
            audio.playbackRate = rate; setCaption(segment.text);
            await new Promise<void>((resolve, reject) => {
              const finish = (error?: Error) => {
                audio.onended = null; audio.onerror = null; signal.removeEventListener('abort', aborted);
                if (error) reject(error); else resolve();
              };
              const aborted = () => { audio.pause(); finish(new DOMException('播放已停止', 'AbortError')); };
              signal.addEventListener('abort', aborted, { once: true });
              audio.onended = () => finish(); audio.onerror = () => finish(new Error('配音文件无法播放，请阅读讲稿或更换浏览器。'));
              void audio.play().catch(error => finish(error instanceof Error ? error : new Error('配音播放失败')));
            });
          }
        },
      }, controller.signal);
      if (!controller.signal.aborted) {
        setMessage(result.reason === 'discussion' ? '先想一想，完成后点击“继续演示”接着学习。' : result.reason === 'text' ? result.nextAction >= actions.length ? '本段没有配音，已到本页讲稿结尾，可以阅读字幕或学习下一页。' : '本段没有配音，阅读字幕后点击“继续演示”。' : '这一页已讲完，可以练习或学习下一页。');
      }
    } catch (error) {
      if (!controller.signal.aborted) setMessage(error instanceof Error ? error.message : '演示未完成，请重试或阅读讲稿。');
    } finally {
      if (job.current === controller) { job.current = null; activeAudio.current = null; setPlaying(false); }
    }
  };
  const whiteboards = [...(scene.whiteboards || [])];
  const savedBoard = course.runtime?.[scene.id]?.whiteboard?.canvas;
  if (savedBoard) whiteboards.push(savedBoard);
  return <div className="ep-shell"><header className="ep-header"><div><span className="ep-kicker">晓陶学伴 · 离线课堂</span><h1>{course.title}</h1><p>{{ academic: '学科学习', wellbeing: '身心成长', exploration: '实践探索' }[course.direction || 'academic']}{!course.direction || course.direction === 'academic' ? ` · ${course.subject}` : ''} · {course.grade} 年级 · 已有课程内容</p></div><span className="ep-badge">无需联网播放</span></header>
    <div className="ep-layout"><nav className="ep-directory" aria-label="课程目录">{course.scenes.map((page, i) => <button key={page.id} aria-current={i === index ? 'step' : undefined} onClick={() => navigate(i)}><span>{String(i + 1).padStart(2, '0')}</span>{page.title}</button>)}</nav>
      <main><div className="ep-title"><span>第 {index + 1} / {course.scenes.length} 页</span><h2>{scene.title}</h2></div><PageBoundary key={scene.id}><section className="ep-stage">{scene.type === 'slide' && scene.content.canvas && <SlideCanvas slide={displayCanvas(scene.content.canvas)} effects={effects} />}{scene.type === 'quiz' && <OfflineQuiz key={scene.id} scene={scene} />}{scene.type === 'interactive' && <InteractiveScene key={`${scene.id}-${frameSerial}`} ref={interactive} scene={scene} selectionMode={false} onSelect={() => {}} onError={setMessage} />}</section></PageBoundary>
        <div className="ep-caption" aria-live="polite">{caption || '可以阅读课件、动手练习，或查看完整讲稿。'}</div>
        <div className="ep-controls"><button onClick={() => void play()} disabled={!scene.actions?.length}>{!scene.actions?.length ? '本页无讲解动作' : playing ? '停止讲解' : cursor >= scene.actions.length ? '重新演示' : cursor > 0 ? '继续演示' : '开始演示讲解'}</button><label>语速 <select value={rate} onChange={event => { const value = Number(event.target.value); setRate(value); if (activeAudio.current) activeAudio.current.playbackRate = value; }}><option value={0.8}>0.8×</option><option value={1}>1×</option><option value={1.2}>1.2×</option></select></label><span /><button disabled={index === 0} onClick={() => navigate(index - 1)}>上一页</button><button disabled={index === course.scenes.length - 1} onClick={() => navigate(index + 1)}>下一页</button></div>
        {message && <p role="status" className="ep-feedback">{message}</p>}<details className="ep-transcript" open><summary>本页完整讲稿</summary>{(scene.actions || []).filter(action => action.type === 'speech' || action.type === 'discussion').map((action, i) => <p key={i}>{String(action.text || action.content || action.prompt || action.topic || '')}</p>)}</details>
        {playWarnings.length > 0 && <ul className="ep-feedback">{playWarnings.map(warning => <li key={warning}>{warning}</li>)}</ul>}
        {demoBoard?.isOpen && <section className="ep-transcript"><strong>当前演示板书</strong><div className="ep-board"><SlideCanvas slide={displayCanvas(demoBoard.canvas)} /></div></section>}
        {whiteboards.map((board, i) => <details className="ep-transcript" key={board.id || i}><summary>已保存课堂板书 {i + 1}</summary><div className="ep-board"><SlideCanvas slide={displayCanvas(board)} /></div></details>)}
      </main></div><footer><p>这是已导出的课程副本，不包含在线 AI 问答、录音识别或成绩同步。新问题请回到原课堂。</p>{data.warnings.length > 0 && <details><summary>导出提示（{data.warnings.length}）</summary><ul>{data.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}</footer></div>;
}

try {
  const element = document.getElementById('educationagent-course-data');
  if (!element?.textContent) throw new Error('课程数据缺失');
  const data = JSON.parse(element.textContent) as PlayerData;
  validateEnvelope(data.envelope);
  createRoot(document.getElementById('educationagent-player')!).render(<Player data={data} />);
} catch (error) {
  const root = document.getElementById('educationagent-player');
  if (root) root.textContent = error instanceof Error ? error.message : '课程文件无法读取';
}
