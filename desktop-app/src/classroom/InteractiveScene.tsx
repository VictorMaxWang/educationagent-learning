import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import type { Scene, TeachingAction } from './types';
import { acceptsBridgeMessage, commandForTeachingAction, createBridgeNonce, interactiveDocument, INTERACTIVE_BRIDGE_CHANNEL, isRecord, isStableSelector, validateEditablePatch, type BridgeIdentity, type BridgeResult, type EditablePatch, type EditableProperties } from './interactiveBridge';

export interface InteractiveSceneHandle {
  execute(action: TeachingAction, signal?: AbortSignal): Promise<BridgeResult>;
  discardEdits(): void;
  readState(signal?: AbortSignal): Promise<Record<string, unknown> | undefined>;
}
export interface InteractiveSceneProps {
  scene: Scene;
  selectionMode: boolean;
  selectedSelector?: string;
  onSelect(reference: { kind: 'interactive_component'; sceneId: string; selector: string }, summary: string): void;
  onState?(state: Record<string, unknown>): void;
  initialState?: Record<string, unknown>;
  onContentChange?(html: string): Promise<void>;
  onError?(message: string): void;
  onDirtyChange?(dirty: boolean): void;
}
interface Pending { settle(result: BridgeResult): void }
const EDIT_FIELDS = [
  ['textContent', '对象文字'], ['value', '输入值'], ['title', '提示文字'], ['aria-label', '读屏名称'],
  ['color', '文字颜色'], ['backgroundColor', '背景颜色'], ['fontSize', '字号（如 20px）'],
] as const;

/** 只通过带 nonce 的消息桥接触 iframe；页面本身始终是无同源授权的隔离文档。 */
const InteractiveScene = forwardRef<InteractiveSceneHandle, InteractiveSceneProps>(function InteractiveScene(props, forwardedRef) {
  const { scene, selectionMode, selectedSelector } = props;
  const iframe = useRef<HTMLIFrameElement>(null);
  const container = useRef<HTMLDivElement>(null);
  const callbacks = useRef(props); callbacks.current = props;
  const pending = useRef(new Map<string, Pending>());
  const sequence = useRef(0);
  const ready = useRef(false);
  const restoring = useRef(false);
  const readiness = useRef(new Set<(success: boolean) => void>());
  const [width, setWidth] = useState(0);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState('');
  const [properties, setProperties] = useState<EditableProperties>();
  const [draft, setDraft] = useState<EditablePatch>({});
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [resetSerial, setResetSerial] = useState(0);
  const draftRef = useRef(draft); draftRef.current = draft;
  const selectedRef = useRef(selected); selectedRef.current = selected;
  // 文档修改也旋转 nonce，上一版迟到的 ACK 不能标记新版执行成功。
  const identity = useMemo<BridgeIdentity>(() => ({ sceneId: scene.id, nonce: createBridgeNonce() }), [scene.id, scene.content.html, resetSerial]);
  const currentIdentity = useRef(identity); currentIdentity.current = identity;
  const documentHtml = useMemo(() => interactiveDocument(scene.content.html || '', identity), [scene.content.html, identity]);
  const report = useCallback((message: string) => { setError(message); callbacks.current.onError?.(message); }, []);
  const post = useCallback((payload: Record<string, unknown>) => {
    iframe.current?.contentWindow?.postMessage({ channel: INTERACTIVE_BRIDGE_CHANNEL, ...identity, ...payload }, '*');
  }, [identity]);

  const request = useCallback((payload: Record<string, unknown>, signal?: AbortSignal): Promise<BridgeResult> => {
    if (signal?.aborted) return Promise.resolve({ success: false, status: 'unconfirmed', reason: 'cancelled', error: '操作已取消，未取得执行确认' });
    if (!iframe.current?.contentWindow || identity !== currentIdentity.current) return Promise.resolve({ success: false, status: 'unconfirmed', reason: 'scene_changed', error: '互动页面已切换，未取得执行确认' });
    const requestId = `${identity.nonce}-${++sequence.current}`;
    return new Promise(resolve => {
      let settled = false;
      const finish = (result: BridgeResult) => {
        if (settled) return;
        settled = true; clearTimeout(timer); signal?.removeEventListener('abort', aborted); pending.current.delete(requestId); resolve(result);
      };
      const aborted = () => finish({ success: false, status: 'unconfirmed', reason: 'cancelled', error: '操作已取消，未取得执行确认' });
      const timer = setTimeout(() => finish({ success: false, status: 'unconfirmed', reason: 'timeout', error: '互动页面没有确认操作，请核对当前状态后再重试' }), 6000);
      pending.current.set(requestId, { settle: finish }); signal?.addEventListener('abort', aborted, { once: true });
      post({ ...payload, requestId });
    });
  }, [identity, post]);

  const waitUntilReady = useCallback((signal?: AbortSignal): Promise<boolean> => {
    if (signal?.aborted) return Promise.resolve(false);
    if (ready.current) return Promise.resolve(true);
    return new Promise(resolve => {
      const finish = (success: boolean) => { clearTimeout(timer); signal?.removeEventListener('abort', aborted); readiness.current.delete(finish); resolve(success); };
      const aborted = () => finish(false);
      const timer = setTimeout(() => finish(false), 6500);
      readiness.current.add(finish); signal?.addEventListener('abort', aborted, { once: true });
    });
  }, []);

  useImperativeHandle(forwardedRef, () => ({
    discardEdits() { setDraft({}); setResetSerial(previous => previous + 1); },
    async readState(signal) {
      const readingIdentity = identity;
      if (!await waitUntilReady(signal) || signal?.aborted || readingIdentity !== currentIdentity.current) throw new Error(signal?.aborted ? '操作已取消' : '互动页面尚未准备好，请等待页面加载完成或取消对象引用后提问。');
      const result = await request({ command: 'snapshot' }, signal);
      if (readingIdentity !== currentIdentity.current || signal?.aborted) throw new Error('互动页面已切换，未读取旧页面状态。');
      if (!result.success) {
        if (result.error === '页面没有可读取的控件状态') return undefined;
        throw new Error(result.error || '暂时无法读取互动页面状态。');
      }
      return isRecord(result.state) ? result.state : undefined;
    },
    async execute(action, signal) {
      const executingIdentity = identity;
      if (!await waitUntilReady(signal) || signal?.aborted || executingIdentity !== currentIdentity.current) return { success: false, status: 'unconfirmed', reason: signal?.aborted ? 'cancelled' : 'not_ready', error: signal?.aborted ? '操作已取消' : '互动页面尚未准备好或已切换' };
      try {
        const result = await request({ command: 'execute', action: commandForTeachingAction(action) }, signal);
        if (!result.success && !signal?.aborted) report(result.error || '互动操作未执行');
        return result;
      } catch (cause) {
        const message = cause instanceof Error ? cause.message : '互动操作无法执行'; report(message); return { success: false, status: 'rejected', reason: 'invalid_command', error: message };
      }
    },
  }), [identity, request, report, waitUntilReady]);

  useEffect(() => {
    ready.current = false; restoring.current = false;
    setError(''); setSelected(''); setProperties(undefined); setDraft({}); setSaved(false);
    const makeReady = () => { ready.current = true; for (const resolve of Array.from(readiness.current)) resolve(true); };
    const onMessage = (event: MessageEvent) => {
      if (identity !== currentIdentity.current) return;
      if (!acceptsBridgeMessage(event, iframe.current?.contentWindow || null, identity)) return;
      const message = event.data;
      if (message.kind === 'ready') {
        post({ command: 'selection', enabled: callbacks.current.selectionMode, selector: callbacks.current.selectedSelector });
        if (ready.current || restoring.current) return;
        const initial = callbacks.current.initialState;
        if (initial && Object.keys(initial).length) {
          restoring.current = true;
          void request({ command: 'restore', state: initial }).then(result => {
            if (identity !== currentIdentity.current) return;
            restoring.current = false;
            if (!result.success) report(`互动状态未恢复：${result.error || '页面不支持这个状态'}`);
            // 恢复失败明确报告，但仍允许学生重新操作，不把失败状态写回服务器。
            makeReady();
          });
        } else makeReady();
      } else if (message.kind === 'picked' && callbacks.current.selectionMode && isStableSelector(message.selector)) {
        if (message.selector !== selectedRef.current && Object.keys(draftRef.current).length && !window.confirm('当前对象的属性还未保存，确定选择其他对象吗？')) {
          post({ command: 'selection', enabled: callbacks.current.selectionMode, selector: selectedRef.current });
          return;
        }
        setSelected(message.selector); setProperties(message.properties); setDraft({}); setSaved(false); setError('');
        callbacks.current.onSelect({ kind: 'interactive_component', sceneId: identity.sceneId, selector: message.selector }, String(message.summary || message.selector).slice(0, 200));
      } else if (message.kind === 'state' && isRecord(message.state)) {
        if (ready.current) callbacks.current.onState?.(message.state);
      } else if (message.kind === 'ack' && typeof message.requestId === 'string' && isRecord(message.result) && typeof message.result.success === 'boolean') {
        const operation = pending.current.get(message.requestId);
        if (!operation) return;
        const result = message.result as BridgeResult;
        if (result.success && isRecord(result.state)) callbacks.current.onState?.(result.state);
        operation.settle(result);
      } else if (message.kind === 'error') report(String(message.error || '互动页面操作失败').slice(0, 500));
    };
    window.addEventListener('message', onMessage);
    return () => {
      ready.current = false; window.removeEventListener('message', onMessage);
      for (const item of Array.from(pending.current.values())) item.settle({ success: false, status: 'unconfirmed', reason: 'scene_changed', error: '互动页面已切换，未取得执行确认' });
      for (const resolve of Array.from(readiness.current)) resolve(false);
    };
  }, [identity, post, report, request]);

  useEffect(() => { props.onDirtyChange?.(Object.keys(draft).length > 0); }, [draft, props.onDirtyChange]);

  useEffect(() => {
    post({ command: 'selection', enabled: selectionMode, selector: selectedSelector });
    if (!selectedSelector) { setSelected(''); setProperties(undefined); setDraft({}); return; }
    if (!isStableSelector(selectedSelector)) return;
    let active = true;
    void waitUntilReady().then(async available => {
      if (!available || !active) return;
      const result = await request({ command: 'inspect', selector: selectedSelector });
      if (active && result.success) { setSelected(selectedSelector); setProperties(result.properties); setDraft({}); setSaved(false); }
    });
    return () => { active = false; };
  }, [selectionMode, selectedSelector, post, request, waitUntilReady]);

  useEffect(() => {
    const node = container.current;
    if (!node) return;
    const measure = () => setWidth(node.getBoundingClientRect().width);
    measure();
    if (typeof ResizeObserver === 'undefined') { window.addEventListener('resize', measure); return () => window.removeEventListener('resize', measure); }
    const observer = new ResizeObserver(entries => setWidth(entries[0].contentRect.width)); observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const save = async () => {
    if (!callbacks.current.onContentChange || !selected || saving) return;
    if (!validateEditablePatch(draft)) { report('请先修改属性；颜色使用颜色名称、十六进制或 rgb，字号使用 8–120px。'); return; }
    const editingIdentity = identity;
    setSaving(true); setSaved(false); setError('');
    try {
      const result = await request({ command: 'edit', selector: selected, patch: draft });
      if (!result.success || typeof result.html !== 'string' || !result.html) throw new Error(result.error || '页面没有返回可保存的内容');
      if (editingIdentity !== currentIdentity.current) return;
      await callbacks.current.onContentChange(result.html);
      if (editingIdentity === currentIdentity.current) { setProperties(result.properties); setDraft({}); setSaved(true); }
    } catch (cause) { if (editingIdentity === currentIdentity.current) report(cause instanceof Error ? cause.message : '保存失败，页面改动尚未写入课程'); }
    finally { setSaving(false); }
  };

  return <div className="cr-interactive-editor">
    <div className="cr-interactive-slot" ref={container} style={{ height: width > 0 ? width * 720 / 1280 : undefined }}>
      <iframe ref={iframe} title={scene.title} sandbox="allow-scripts" referrerPolicy="no-referrer" srcDoc={documentHtml}
        onLoad={() => post({ command: 'hello' })}
        style={{ width: 1280, height: 720, border: 0, transform: `scale(${width / 1280})`, transformOrigin: 'top left' }} />
    </div>
    {selectionMode && <p className="cr-muted" role="status">点击控件或文字以引用。选取模式会暂停该次点击的原互动操作。</p>}
    {error && <p className="cr-error" role="alert">{error}</p>}
    {selected && properties && props.onContentChange && <details className="cr-transcript" open>
      <summary>编辑选中对象 {selected}</summary>
      <form onSubmit={event => { event.preventDefault(); void save(); }} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))', gap: 12, padding: 12 }}>
        {EDIT_FIELDS.filter(([key]) => (key !== 'textContent' || properties.canEditText) && (key !== 'value' || properties.value !== undefined)).map(([key, label]) => <label key={key} className="cr-field" style={{ display: 'grid', gap: 4 }}>
          <span>{label}</span><input aria-label={label} value={draft[key] ?? properties[key] ?? ''} maxLength={2000} disabled={saving}
            onChange={event => { setDraft(previous => ({ ...previous, [key]: event.target.value })); setSaved(false); }} />
        </label>)}
        <div className="cr-inline" style={{ gridColumn: '1 / -1' }}><button className="cr-button" type="submit" disabled={saving || !Object.keys(draft).length}>{saving ? '正在保存…' : '保存到课程'}</button><button className="cr-button secondary" type="button" disabled={saving || !Object.keys(draft).length} onClick={() => { setDraft({}); setResetSerial(previous => previous + 1); }}>放弃修改</button>{saved && <span role="status">已保存</span>}</div>
      </form>
    </details>}
  </div>;
});

export default InteractiveScene;
