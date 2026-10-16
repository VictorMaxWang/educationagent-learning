import { Component, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { SlideCanvas, type SlideEffects } from '@educationagent/renderer';
import type { PPTElement, Slide } from '@educationagent/dsl';
import '@educationagent/renderer/styles.css';
import { audioUrl, getCourse, saveRuntime, saveScene, synthesizeSpeech, voiceHealth, type RuntimeUpdate } from './api';
import { NarrationQueue, type PlaybackStatus } from './audioQueue';
import { clampIndex, newLocalId } from './navigation';
import { TYPE_LABELS, type ActionOutcome, type ActionResult, type ClassroomMessage, type Course, type ElementReference, type SceneRuntime, type Speaker, type TeachingAction, type WhiteboardState } from './types';
import QuizScene from './QuizScene';
import ClassroomConversation from './ClassroomConversation';
import InteractiveScene, { type InteractiveSceneHandle } from './InteractiveScene';
import SlideEditor from './SlideEditor';
import WhiteboardPanel from './WhiteboardPanel';
import { applyWhiteboardAction, createWhiteboard, elementSummary, selectedElementStillExists } from './whiteboardModel';
import { resolveSlideAssets } from './slideAssets';
import './classroom-editor.css';
import StudyReflectionCard from './StudyReflectionCard';
import RepairMediaButton from './RepairMediaButton';
import { sceneContentRevision } from './reflectionIdentity';
import { useAudioFocus } from './useAudioFocus';
import { companionIdentityText } from './branding';

class SceneBoundary extends Component<{ children: ReactNode }, { error: boolean }> {
  state = { error: false };
  static getDerivedStateFromError() { return { error: true }; }
  render() { return this.state.error ? <div className="cr-empty">这一页暂时无法展示。你仍可以查看讲解文字、提问或学习下一页。</div> : this.props.children; }
}
/** 互动脚本在独立沙箱中运行，不授予同源、弹窗、表单、网络或页面导航能力。 */
export function sandboxedHtml(html: string): string {
  return `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; worker-src blob:; connect-src 'none'; form-action 'none'; base-uri 'none'"><meta name="referrer" content="no-referrer">${html}`;
}
type SelectionInfo = { reference: ElementReference; summary: string };
type ActionReceipt = ActionResult & { running?: boolean };
const ACTION_LABELS: Record<string, string> = {
  wb_open: '打开白板', wb_close: '收起白板', wb_clear: '清空白板', wb_delete: '删除白板对象',
  wb_draw_text: '白板文字', wb_draw_shape: '白板图形', wb_draw_latex: '白板公式', wb_draw_line: '白板连线',
  wb_draw_table: '白板表格', wb_draw_chart: '白板图表', wb_draw_code: '白板代码示例',
  widget_setState: '调整互动参数', widget_highlight: '强调互动对象', widget_annotation: '添加互动提示', widget_reveal: '展示互动对象',
};

export default function CoursePlayer({ course, onProgress, onCourseChange, onEditorDirtyChange, waitForProgress }: {
  course: Course; onProgress(courseId: string, sceneIndex: number, actionIndex: number): void;
  onCourseChange?(course: Course): void;
  onEditorDirtyChange?(dirty: boolean): void;
  waitForProgress?(): Promise<unknown>;
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
  const audioFocus = useAudioFocus();
  const recordingBusyRef = useRef(false);
  const [mobilePanel, setMobilePanel] = useState<'lesson' | 'conversation'>('lesson');
  const [editing, setEditing] = useState(false);
  const [editorDirty, setEditorDirty] = useState(false);
  const [boardEditing, setBoardEditing] = useState(false);
  const [boardDirty, setBoardDirty] = useState(false);
  const [interactiveDirty, setInteractiveDirty] = useState(false);
  const [picking, setPicking] = useState(false);
  const [selection, setSelection] = useState<SelectionInfo | null>(null);
  const [mutationBusy, setMutationBusy] = useState(false);
  const [localReceipts, setLocalReceipts] = useState<ActionReceipt[]>([]);
  const [prefill, setPrefill] = useState<{ id: string; text: string }>();
  const iframe = useRef<InteractiveSceneHandle | null>(null);
  const mounted = useRef(true);
  const writeChain = useRef<Promise<unknown>>(Promise.resolve());
  const actionExecuting = useRef(false);
  const editRevision = useRef(course.revision ?? 0);
  const boardRevision = useRef(course.revision ?? 0);
  const latestInteractiveState = useRef<Record<string, unknown> | undefined>(undefined);
  const interactiveSaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingInteractiveState = useRef<{ sceneId: string; state: Record<string, unknown> } | null>(null);
  const progress = useRef({ index, courseId: course.id });
  const queue = useRef<NarrationQueue | null>(null);
  const readingResponse = useRef(false);
  const scene = course.scenes[clampIndex(index, course.scenes.length)];
  const reflectionRevision = useMemo(() => sceneContentRevision(scene?.content), [scene?.content]);
  const live = useRef({ course, scene, onCourseChange, editing, boardEditing, recordingBusy });
  const latestCourse = live.current.course.id === course.id && (live.current.course.revision ?? 0) > (course.revision ?? 0) ? live.current.course : course;
  live.current = { course: latestCourse, scene, onCourseChange, editing, boardEditing, recordingBusy };
  const actionRunner = useRef<(action: TeachingAction, signal: AbortSignal) => Promise<void>>(async () => {});
  const runtime = scene ? course.runtime?.[scene.id] : undefined;
  const board = runtime?.whiteboard;
  useEffect(() => { onEditorDirtyChange?.(editorDirty || boardDirty || interactiveDirty); }, [editorDirty, boardDirty, interactiveDirty, onEditorDirtyChange]);
  progress.current = { index, courseId: course.id };
  const applyCourse = useCallback((next: Course) => {
    const current = live.current.course;
    if (next.id !== current.id || (next.revision ?? 0) < (current.revision ?? 0)) return;
    live.current.course = next;
    if (mounted.current) live.current.onCourseChange?.(next);
  }, []);
  const write = useCallback(<T,>(operation: () => Promise<T>): Promise<T> => {
    const next = writeChain.current.catch(() => undefined).then(operation);
    writeChain.current = next;
    return next;
  }, []);
  const commitRuntime = useCallback((sceneId: string, change: Partial<SceneRuntime> | ((previous: SceneRuntime) => Partial<SceneRuntime>), expectedRevision?: number): Promise<Course> => write(async () => {
    const current = live.current.course;
    const previous = current.runtime?.[sceneId] || {};
    const changes = typeof change === 'function' ? change(previous) : change;
    const next = await saveRuntime(current.id, { ...changes, sceneId, operationId: newLocalId(), expectedRevision: expectedRevision ?? current.revision ?? 0 } as RuntimeUpdate);
    applyCourse(next);
    return next;
  }), [applyCourse, write]);
  const saveCanvas = async (canvas: Slide) => {
    const active = live.current.scene;
    if (!active) return;
    setMutationBusy(true); setError('');
    try {
      const next = await write(async () => { await waitForProgress?.(); return saveScene(live.current.course.id, active.id, editRevision.current, { ...active.content, type: 'slide', canvas }); });
      editRevision.current = next.revision ?? 0;
      applyCourse(next); setEditorDirty(false); setSelection(null); setActionIndex(0); setEffects({}); setCaption(''); setLocalReceipts([]);
    } catch (error) { setError(error instanceof Error ? error.message : '课件未保存'); throw error; }
    finally { setMutationBusy(false); }
  };
  const saveBoard = async (nextBoard: WhiteboardState) => {
    const active = live.current.scene;
    if (!active) return;
    setMutationBusy(true); setError('');
    try {
      const next = await commitRuntime(active.id, { whiteboard: nextBoard }, boardEditing ? boardRevision.current : undefined);
      boardRevision.current = next.revision ?? 0; setBoardDirty(false);
    } catch (error) { setError(error instanceof Error ? error.message : '白板未保存'); throw error; }
    finally { setMutationBusy(false); }
  };
  const saveInteractiveHtml = async (html: string) => {
    const active = live.current.scene;
    if (!active) return;
    const revision = live.current.course.revision ?? 0;
    setMutationBusy(true); setError(''); queue.current?.stop();
    if (interactiveSaveTimer.current) clearTimeout(interactiveSaveTimer.current);
    pendingInteractiveState.current = null;
    try {
      const next = await write(async () => { await waitForProgress?.(); return saveScene(live.current.course.id, active.id, revision, { ...active.content, html }); });
      applyCourse(next); setSelection(null); setPicking(false); setActionIndex(0); setEffects({}); setCaption(''); setLocalReceipts([]); latestInteractiveState.current = undefined;
    } catch (error) { setError(error instanceof Error ? error.message : '互动页面未保存'); throw error; }
    finally { setMutationBusy(false); }
  };
  const saveObservedState = useCallback((state: Record<string, unknown>) => {
    const active = live.current.scene;
    if (!active) return;
    latestInteractiveState.current = state;
    if (actionExecuting.current || JSON.stringify(state) === JSON.stringify(live.current.course.runtime?.[active.id]?.interactiveState)) return;
    pendingInteractiveState.current = { sceneId: active.id, state };
    if (interactiveSaveTimer.current) clearTimeout(interactiveSaveTimer.current);
    interactiveSaveTimer.current = setTimeout(() => {
      const pending = pendingInteractiveState.current; pendingInteractiveState.current = null;
      if (pending) void commitRuntime(pending.sceneId, { interactiveState: pending.state }).catch(error => setError(`互动状态未保存：${error instanceof Error ? error.message : '请求失败'}`));
    }, 500);
  }, [commitRuntime]);
  const prepareQuestion = async (signal?: AbortSignal) => {
    await waitForProgress?.();
    if (signal?.aborted) return;
    const active = live.current.scene;
    if (active?.type === 'interactive') {
      try {
        if (!iframe.current) throw new Error('互动页面尚未准备好。');
        // 主动读取，覆盖 input/change 事件的 80ms 合并窗口；不等下一次自动保存或渲染。
        const observed = await iframe.current.readState(signal);
        if (live.current.scene?.id !== active.id || signal?.aborted) throw new Error('互动页面已切换。');
        latestInteractiveState.current = observed;
        if (!observed) pendingInteractiveState.current = null;
        if (observed && JSON.stringify(observed) !== JSON.stringify(live.current.course.runtime?.[active.id]?.interactiveState)) pendingInteractiveState.current = { sceneId: active.id, state: observed };
      } catch (error) {
        latestInteractiveState.current = undefined;
        pendingInteractiveState.current = null;
        if (signal?.aborted || live.current.scene?.id !== active.id || selection?.reference.kind === 'interactive_component') throw error;
        // 没有选中互动对象时仍可询问课件知识，但绝不把旧参数伪装成当前观察。
        setError('暂未读到互动页面状态，这次将只依据课件内容回答。');
      }
    }
    if (interactiveSaveTimer.current) clearTimeout(interactiveSaveTimer.current);
    const pending = pendingInteractiveState.current; pendingInteractiveState.current = null;
    if (pending) await commitRuntime(pending.sceneId, { interactiveState: pending.state });
    await writeChain.current;
  };
  const pickElement = (element: PPTElement, kind: 'slide_element' | 'whiteboard_element') => {
    if (!scene || editing || boardEditing) return;
    queue.current?.stop();
    const reference: ElementReference = kind === 'whiteboard_element' && board
      ? { kind, sceneId: scene.id, whiteboardId: board.id, elementId: element.id }
      : { kind: 'slide_element', sceneId: scene.id, elementId: element.id };
    setSelection({ reference, summary: elementSummary(element) }); setPicking(false);
  };
  actionRunner.current = async (action, signal) => {
    if (signal.aborted) return;
    if (!action.type.startsWith('wb_') && !action.type.startsWith('widget_')) return;
    const active = live.current.scene;
    if (!active) throw new Error('课堂页面已切换。');
    if (live.current.editing || live.current.boardEditing) throw new Error('请先保存或退出画布编辑，再执行老师的操作。');
    if (!action.id) throw new Error('老师操作缺少可追踪的编号。');
    const actionId = action.id;
    if (live.current.course.runtime?.[active.id]?.acknowledgedActionIds?.includes(actionId)) return;
    actionExecuting.current = true;
    setMutationBusy(true);
    setLocalReceipts(previous => [...previous.filter(item => item.actionId !== actionId), { actionId, success: false, running: true }].slice(-50));
    const at = () => new Date().toISOString();
    let outcome: ActionOutcome = 'unconfirmed';
    try {
      if (interactiveSaveTimer.current) clearTimeout(interactiveSaveTimer.current);
      pendingInteractiveState.current = null;
      if (action.type.startsWith('wb_')) {
        await commitRuntime(active.id, previous => {
          let whiteboard: WhiteboardState;
          try { whiteboard = applyWhiteboardAction(previous.whiteboard, action, active.content.canvas?.theme); }
          catch (error) { outcome = 'rejected'; throw error; }
          return { whiteboard, acknowledgedActionIds: [...(previous.acknowledgedActionIds || []), actionId], actionResults: [{ actionId, success: true, status: 'confirmed', at: at() }] };
        });
      } else {
        if (active.type !== 'interactive' || !iframe.current) { outcome = 'rejected'; throw new Error('当前页不是可操作的互动页面。'); }
        const result = await iframe.current.execute(action, signal);
        if (!result.success) { outcome = result.status === 'unconfirmed' ? 'unconfirmed' : 'rejected'; throw new Error(result.error || '互动页面未确认执行。'); }
        if (signal.aborted) throw new Error('操作期间页面已切换，尚未保存执行确认。');
        const state = result.state && typeof result.state === 'object' && !Array.isArray(result.state) ? result.state as Record<string, unknown> : undefined;
        if (state) latestInteractiveState.current = state;
        await commitRuntime(active.id, previous => ({
          ...(state ? { interactiveState: state } : {}),
          acknowledgedActionIds: [...(previous.acknowledgedActionIds || []), actionId],
          actionResults: [{ actionId, success: true, status: 'confirmed', at: at() }],
        }));
      }
      setLocalReceipts(previous => [...previous.filter(item => item.actionId !== actionId), { actionId, success: true, status: 'confirmed' as const, at: at() }].slice(-50));
    } catch (error) {
      const httpStatus = (error as { status?: number } | undefined)?.status;
      if (signal.aborted) outcome = 'unconfirmed';
      const message = (error instanceof Error ? error.message : '老师操作执行失败').slice(0, 300);
      // 写回遭拒不等于动作遭拒：互动 DOM 可能已改变，不能把缺失确认当成负观测。
      const persistenceRejected = typeof httpStatus === 'number' && httpStatus >= 400 && httpStatus < 500;
      const reason = signal.aborted ? 'cancelled' : outcome === 'rejected' ? 'rejected' : persistenceRejected ? 'persistence_rejected' : 'confirmation_missing';
      setLocalReceipts(previous => [...previous.filter(item => item.actionId !== actionId), { actionId, success: false, status: outcome, reason, error: message, at: at() }].slice(-50));
      try { await commitRuntime(active.id, { actionResults: [{ actionId, success: false, status: outcome, reason, error: message, at: at() }] }); }
      catch { setError(`${message}；操作记录暂未同步。`); }
      throw new Error(message);
    } finally { actionExecuting.current = false; setMutationBusy(false); }
  };
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
    onCaption: (text, role) => { setCaption(companionIdentityText(text)); setCaptionRole(role); },
    onDiscussion: (text, nextAction) => { setDiscussion(text); setActionIndex(nextAction); persist(progress.current.index, nextAction); },
    onAction: async (nextAction, action, signal, trackProgress) => {
      if (trackProgress) { setActionIndex(nextAction); persist(progress.current.index, nextAction); }
      if (action.type === 'spotlight' && action.elementId) setEffects(previous => ({ ...previous, spotlight: { elementId: action.elementId! } }));
      if (action.type === 'laser' && action.elementId) setEffects(previous => ({ ...previous, laser: { elementId: action.elementId!, color: typeof action.color === 'string' ? action.color : undefined } }));
      await actionRunner.current(action, signal);
    },
  });
  useEffect(() => {
    mounted.current = true;
    let active = true;
    void voiceHealth().then(value => { if (active) setVoice(value); }).catch(() => {});
    return () => {
      active = false; mounted.current = false; queue.current?.destroy();
      if (interactiveSaveTimer.current) clearTimeout(interactiveSaveTimer.current);
      const pending = pendingInteractiveState.current; pendingInteractiveState.current = null;
      if (pending) void commitRuntime(pending.sceneId, { interactiveState: pending.state }).catch(() => {});
    };
  }, [commitRuntime]);
  useEffect(() => {
    setPicking(false); setSelection(null); setLocalReceipts([]);
    latestInteractiveState.current = scene ? course.runtime?.[scene.id]?.interactiveState : undefined;
  }, [scene?.id]);
  useEffect(() => {
    if (!selection || !scene) return;
    const ref = selection.reference;
    if (ref.sceneId !== scene.id
      || (ref.kind === 'slide_element' && !selectedElementStillExists(scene.content.canvas, ref.elementId))
      || (ref.kind === 'whiteboard_element' && (ref.whiteboardId !== board?.id || !selectedElementStillExists(board?.canvas, ref.elementId)))) setSelection(null);
  }, [selection, scene, board]);
  const stop = useCallback(() => queue.current?.stop(), []);
  const onRecordingBusyChange = useCallback((busy: boolean) => {
    recordingBusyRef.current = busy;
    queue.current?.setMicrophoneBusy(busy);
    setRecordingBusy(busy);
    if (busy) readingResponse.current = false;
  }, []);
  const go = (next: number) => {
    if ((editorDirty || boardDirty || interactiveDirty) && !window.confirm('还有未保存的画布修改，确定切换页面吗？')) return;
    queue.current?.stop();
    if (interactiveSaveTimer.current) clearTimeout(interactiveSaveTimer.current);
    const pending = pendingInteractiveState.current; pendingInteractiveState.current = null;
    if (pending) void commitRuntime(pending.sceneId, { interactiveState: pending.state }).catch(error => setError(error instanceof Error ? error.message : '互动状态未保存'));
    const target = clampIndex(next, course.scenes.length);
    setIndex(target); setActionIndex(0); setCaption(''); setDiscussion(''); setEffects({}); setError(''); setMobilePanel('lesson');
    setEditing(false); setEditorDirty(false); setBoardEditing(false); setBoardDirty(false); setInteractiveDirty(false);
    persist(target, 0);
  };
  const togglePlay = () => {
    if (!scene || recordingBusyRef.current || audioFocus.microphoneBlocked || editing || boardEditing || interactiveDirty || mutationBusy) return;
    if (playback === 'paused') { queue.current?.resume(); return; }
    if (playback === 'playing' || playback === 'loading') { queue.current?.pause(); return; }
    setDiscussion(''); readingResponse.current = false;
    void queue.current?.play(scene.actions || [], playback === 'done' ? 0 : actionIndex);
  };
  const onMessage = async (message: ClassroomMessage, deferPlayback = false) => {
    if (!mounted.current) return;
    const responseSceneId = message.sceneId || live.current.scene?.id;
    if (message.updatedScene) {
      const current = live.current.course;
      try {
        // 页面写入还会使旧互动状态及旧动作确认失效，以 Java 的完整回读为准。
        const fresh = await getCourse(current.id);
        applyCourse(fresh);
        if (!mounted.current || live.current.scene?.id !== responseSceneId) return;
        setSelection(null); setActionIndex(0); setEffects({}); setCaption(''); setLocalReceipts([]); latestInteractiveState.current = fresh.runtime?.[message.updatedScene.id]?.interactiveState;
      } catch (error) {
        setError(`课件已更新，但刷新未完成：${error instanceof Error ? error.message : '请重新打开课堂'}`);
        return;
      }
    }
    if (!mounted.current || live.current.scene?.id !== responseSceneId) return;
    if (message.actions?.length && !deferPlayback) {
      if (recordingBusyRef.current || live.current.editing || live.current.boardEditing) { setError('老师已给出操作，请完成录音或退出编辑后再执行。'); return; }
      readingResponse.current = true;
      void queue.current?.playActions(message.actions, message.speaker === 'peer' ? 'peer' : 'teacher');
    }
  };
  const openBoard = async () => {
    if (!scene) return;
    queue.current?.stop(); setError(''); setMutationBusy(true);
    try { await commitRuntime(scene.id, previous => ({ whiteboard: { ...(previous.whiteboard || createWhiteboard(scene.content.canvas?.theme)), isOpen: true } })); }
    catch (error) { setError(error instanceof Error ? error.message : '无法打开白板'); }
    finally { setMutationBusy(false); }
  };
  const receipts = [...new Map<string, ActionReceipt>([...(runtime?.actionResults || []), ...localReceipts].map(item => [item.actionId, item])).values()].slice(-8);
  const findAction = (id: string) => [...(scene?.actions || []), ...course.messages.flatMap(message => message.actions || [])].find(action => action.id === id);
  const speech = (scene?.actions || []).filter(action => action.type === 'speech' || action.type === 'discussion');
  if (!scene) return <div className="cr-empty">课堂还在准备中，第一步完成后就会出现在这里。</div>;
  return <div className="cr-player">
    <div className="cr-mobile-panels"><button aria-pressed={mobilePanel === 'lesson'} onClick={() => setMobilePanel('lesson')}>学习内容</button><button aria-pressed={mobilePanel === 'conversation'} onClick={() => setMobilePanel('conversation')}>老师与学伴</button></div>
    <div className={`cr-lesson-column ${mobilePanel === 'lesson' ? 'mobile-active' : ''}`}>
      <div className="cr-step-heading"><div><span className="cr-eyebrow">第 {index + 1} / {course.outlines.length || course.scenes.length} 步 · {TYPE_LABELS[scene.type]}</span><h2>{scene.title}</h2></div><span className="cr-pill">慢慢来，也能学会</span></div>
      <div className="ce-classroom-tools"><div><button className={picking ? 'active' : ''} disabled={editing || boardEditing || interactiveDirty || mutationBusy} onClick={() => { queue.current?.stop(); setPicking(previous => !previous); }}>{picking ? '取消对象选择' : '点选对象提问'}</button>{scene.type === 'slide' && <button disabled={editing || boardEditing || mutationBusy || recordingBusy} onClick={() => { queue.current?.stop(); editRevision.current = live.current.course.revision ?? 0; setEditing(true); setPicking(false); }}>编辑课件</button>}<button disabled={mutationBusy || recordingBusy || editing || boardEditing || interactiveDirty} onClick={() => void openBoard()}>{board?.isOpen ? '白板已打开' : '打开课堂白板'}</button></div><small>{picking ? '点一下画布里的文字、图形或互动对象，问题会带上它的引用。' : '课件可直接编辑，老师操作会在这里实际执行。'}</small></div>
      {!editing && !boardEditing && !interactiveDirty && <RepairMediaButton course={course} scene={scene} onCourseChange={applyCourse} />}
      {editing && scene.content.canvas ? <SlideEditor key={scene.id} canvas={scene.content.canvas} saving={mutationBusy} onSave={saveCanvas} onCancel={() => { setEditing(false); setEditorDirty(false); }} onDirtyChange={setEditorDirty} /> : <SceneBoundary key={scene.id}><div className={`cr-stage cr-stage-${scene.type}`}>
        {scene.type === 'slide' && scene.content.canvas && <SlideCanvas slide={resolveSlideAssets(scene.content.canvas)} effects={{ ...effects, ...(selection?.reference.kind === 'slide_element' ? { highlight: { elementId: selection.reference.elementId, color: '#c49b42', opacity: .12, borderWidth: 2 } } : {}) }} onElementClick={element => pickElement(element, 'slide_element')} />}
        {scene.type === 'quiz' && <QuizScene key={scene.id} courseId={course.id} scene={scene} />}
        {scene.type === 'interactive' && <InteractiveScene ref={iframe} scene={scene} selectionMode={picking} selectedSelector={selection?.reference.kind === 'interactive_component' ? selection.reference.selector : undefined} onSelect={(reference, summary) => { queue.current?.stop(); setSelection({ reference, summary }); setPicking(false); }} initialState={runtime?.interactiveState} onState={saveObservedState} onContentChange={saveInteractiveHtml} onError={setError} onDirtyChange={setInteractiveDirty} />}
      </div></SceneBoundary>}
      {board?.isOpen && <WhiteboardPanel key={board.id} board={board} selectedElementId={selection?.reference.kind === 'whiteboard_element' ? selection.reference.elementId : undefined} disabled={mutationBusy || recordingBusy || editing} onSave={saveBoard} onSelect={element => pickElement(element, 'whiteboard_element')} onClose={() => { queue.current?.stop(); void saveBoard({ ...board, isOpen: false }).catch(() => {}); }} onEditingChange={value => { queue.current?.stop(); setBoardEditing(value); if (value) boardRevision.current = live.current.course.revision ?? 0; else setBoardDirty(false); }} onDirtyChange={setBoardDirty} />}
      <div className="cr-caption" aria-live="polite"><span>{captionRole === 'peer' ? '小陶' : '老师'}</span><p>{caption || companionIdentityText(String(speech[0]?.text || speech[0]?.prompt || '可以先阅读这一页，也可以点击开始听课。'))}</p></div>
      <div className="cr-player-controls"><div className="cr-inline"><button className="cr-button" onClick={togglePlay} disabled={recordingBusy || audioFocus.microphoneBlocked || editing || boardEditing || interactiveDirty || mutationBusy || !voice.ttsAvailable || !speech.length}>{recordingBusy || audioFocus.microphoneBlocked ? '请先完成语音输入' : editing || boardEditing || interactiveDirty ? '请先完成画布编辑' : playback === 'playing' ? 'Ⅱ 暂停讲解' : playback === 'loading' ? 'Ⅱ 暂停等待' : playback === 'paused' ? '▶ 继续听课' : playback === 'done' ? '↻ 再听一遍' : '▶ 开始听课'}</button><label className="cr-speed">语速<select value={rate} onChange={event => { const value = Number(event.target.value); setRate(value); queue.current?.setRate(value); }}><option value={0.8}>0.8×</option><option value={1}>1.0×</option><option value={1.2}>1.2×</option></select></label></div><div className="cr-inline"><button className="cr-button secondary" disabled={mutationBusy || index === 0} onClick={() => go(index - 1)}>← 上一步</button><button className="cr-button secondary" disabled={mutationBusy || index >= course.scenes.length - 1} onClick={() => go(index + 1)}>下一步 →</button></div></div>
      {recordingBusy && <p className="cr-muted" role="status">麦克风正在使用，讲解和回答朗读已暂停。</p>}
      {error && <p className="cr-error" role="alert">{error}</p>}
      {!voice.ttsAvailable && <p className="cr-muted">语音讲解暂不可用，你可以阅读完整讲解文字并继续练习。</p>}
      {playback === 'done' && <p className="cr-muted" role="status">这一页讲完了。可以提问、完成练习，再选择下一步。</p>}
      {discussion && <div className="cr-discussion-card"><strong>停下来，一起想一想</strong><p>{discussion}</p><button className="cr-link" onClick={() => setMobilePanel('conversation')}>去问问学伴 →</button><span>讨论后再点“开始听课”继续讲解。</span></div>}
      <StudyReflectionCard courseId={course.id} sceneId={scene.id} revision={reflectionRevision} sceneTitle={scene.title} onAskTeacher={text => { setPrefill({ id: newLocalId(), text: text.slice(0, 2000) }); setMobilePanel('conversation'); }} />
      {receipts.length > 0 && <details className="ce-action-receipts" open><summary>老师操作记录</summary>{receipts.map(receipt => <div key={receipt.actionId} className={receipt.running ? 'pending' : receipt.success ? 'success' : receipt.status === 'unconfirmed' ? 'pending' : 'failed'}><strong>{receipt.running ? '正在执行' : receipt.success ? '已确认执行并保存' : receipt.status === 'unconfirmed' ? '尚未取得执行确认' : '操作被明确拒绝'}</strong><span>{ACTION_LABELS[findAction(receipt.actionId)?.type || ''] || '课堂操作'}</span>{receipt.error && <p>{receipt.error}</p>}{!receipt.success && !receipt.running && findAction(receipt.actionId) && <button disabled={recordingBusy || editing || boardEditing || mutationBusy} onClick={() => { readingResponse.current = true; void getCourse(course.id).then(fresh => { applyCourse(fresh); if (mounted.current && live.current.scene?.id === scene.id) void queue.current?.playActions([findAction(receipt.actionId)!]); }).catch(problem => setError(problem instanceof Error ? problem.message : '暂未取得最新状态')); }}>核对后重试</button>}</div>)}</details>}
      <details className="cr-transcript"><summary>查看本页完整讲解</summary>{speech.map((action, i) => <p key={i}>{companionIdentityText(String(action.text || action.prompt || action.topic || ''))}</p>)}</details>
      <nav className="cr-timeline" aria-label="课堂目录">{course.outlines.map((outline, i) => { const available = course.scenes.some(item => item.id === outline.id); return <button key={outline.id} className={i === index ? 'active' : ''} disabled={!available} onClick={() => go(course.scenes.findIndex(item => item.id === outline.id))}><span>{String(i + 1).padStart(2, '0')}</span><div><strong>{outline.title}</strong><small>{available ? TYPE_LABELS[outline.type] : '准备中'}</small></div></button>; })}</nav>
    </div>
    <div className={`cr-conversation-column ${mobilePanel === 'conversation' ? 'mobile-active' : ''}`}><ClassroomConversation key={course.id} course={course} sceneId={scene.id} prefill={prefill} onInterrupt={stop} onRecordingBusyChange={onRecordingBusyChange} asrAvailable={voice.asrAvailable} discussionPrompt={discussion} selection={selection || undefined} onClearSelection={() => { if (interactiveDirty && !window.confirm('对象属性尚未保存，确定放弃修改并取消引用吗？')) return; if (interactiveDirty) iframe.current?.discardEdits(); setSelection(null); }} onMessage={onMessage} beforeRequest={prepareQuestion} getInteractiveState={() => latestInteractiveState.current} executeVoiceAction={async (action, signal) => { if (action.type === 'spotlight' && action.elementId) setEffects(previous => ({ ...previous, spotlight: { elementId: action.elementId! } })); if (action.type === 'laser' && action.elementId) setEffects(previous => ({ ...previous, laser: { elementId: action.elementId! } })); await actionRunner.current(action, signal); }} onRunActions={(actions, role) => { if (recordingBusyRef.current || live.current.editing || live.current.boardEditing || interactiveDirty) return; readingResponse.current = true; void queue.current?.playActions(actions, role); }} interactiveState={runtime?.interactiveState} interactionLocked={editing || boardEditing || interactiveDirty || mutationBusy} onReadResponse={voice.ttsAvailable ? (text, role) => { if (recordingBusyRef.current || live.current.editing || live.current.boardEditing || interactiveDirty) return; readingResponse.current = true; void queue.current?.readResponse(text, role); } : undefined} /></div>
  </div>;
}
