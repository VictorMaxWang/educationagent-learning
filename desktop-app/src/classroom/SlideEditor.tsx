import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { PPTElement, Slide, SlideContent } from '@educationagent/dsl';
import { applyEditorTransaction, createEditorHistory, redoEditorTransaction, undoEditorTransaction, type EditorTransaction } from '@educationagent/editor/core';
import { EMPTY_SELECTION, type Selection } from '@educationagent/editor/react';
import { EditableSlideCanvasWithUI, type EditorHostCapabilities } from '@educationagent/editor/ui';
import { newLocalId } from './navigation';
import { createShapeElement } from './whiteboardModel';
import ClassroomImagePicker from './ClassroomImagePicker';
import { resolveSlideAssets } from './slideAssets';
import { SlideCanvas } from '@educationagent/renderer';
import FreehandSurface from './FreehandSurface';
import { isFreehandElement } from './freehandModel';

export type CanvasInteractionMode = 'browse' | 'select' | 'draw';
export function initialCanvasMode(): CanvasInteractionMode {
  return typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse), (max-width: 760px)').matches ? 'browse' : 'select';
}

export default function SlideEditor({ canvas, title = '编辑课件', saving, onSave, onCancel, onSelect, onDirtyChange }: {
  canvas: Slide; title?: string; saving?: boolean;
  onSave(canvas: Slide): Promise<void>; onCancel(): void;
  onSelect?(element: PPTElement): void; onDirtyChange?(dirty: boolean): void;
}) {
  const [history, setHistory] = useState(() => createEditorHistory({ type: 'slide', canvas }));
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);
  const [error, setError] = useState('');
  const [localSaving, setLocalSaving] = useState(false);
  const [mode, setMode] = useState<CanvasInteractionMode>(initialCanvasMode);
  const [inkColor, setInkColor] = useState('#376951');
  const [inkWidth, setInkWidth] = useState(6);
  const saved = useRef<SlideContent>(history.present);
  const busy = Boolean(saving || localSaving);
  const dirty = history.present !== saved.current;
  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);
  const host = useMemo<EditorHostCapabilities>(() => ({ locale: 'zh-CN', createElementId: () => newLocalId(), onError: event => setError(event.message), shortcutsEnabled: !busy && mode === 'select', renderAssetPicker: request => <ClassroomImagePicker request={request} /> }), [busy, mode]);
  const displayCanvas = useMemo(() => resolveSlideAssets(history.present.canvas), [history.present.canvas]);
  const transact = useCallback((transaction: EditorTransaction) => {
    setHistory(current => applyEditorTransaction(current, transaction));
  }, []);
  const changeSelection = (next: Selection) => {
    setSelection(next);
    if (next.elementIds.length === 1) {
      const element = history.present.canvas.elements.find(item => item.id === next.elementIds[0]);
      if (element) onSelect?.(element);
    }
  };
  const insertShape = (shape: 'rectangle' | 'circle' | 'triangle') => {
    const element = createShapeElement(shape);
    transact({ origin: 'toolbar', history: 'record', operations: [{ type: 'element.add', element }] });
    setSelection({ elementIds: [element.id], primaryId: element.id });
    setMode('select');
  };
  const save = async () => {
    setLocalSaving(true); setError('');
    const snapshot = history.present;
    try { await onSave(snapshot.canvas); saved.current = snapshot; onDirtyChange?.(false); }
    catch (error) { setError(error instanceof Error ? error.message : '保存失败，编辑内容仍保留在这里。'); }
    finally { setLocalSaving(false); }
  };
  return <section className="ce-editor" aria-label={title} onKeyDown={event => {
    const target = event.target as HTMLElement;
    if (busy || target.isContentEditable || target.closest('input,textarea,select,.ProseMirror')) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
      event.preventDefault(); setHistory(current => event.shiftKey ? redoEditorTransaction(current) : undoEditorTransaction(current));
    }
  }}>
    <header className="ce-editor-bar"><div><strong>{title}</strong><span>{dirty ? '有未保存的修改' : '修改已保存'}</span></div><div className="ce-editor-actions"><button disabled={busy || !history.past.length} onClick={() => setHistory(undoEditorTransaction)}>撤销</button><button disabled={busy || !history.future.length} onClick={() => setHistory(redoEditorTransaction)}>重做</button><button disabled={busy} onClick={() => { if (!dirty || window.confirm('还有未保存的修改，确定返回课堂吗？')) onCancel(); }}>返回课堂</button><button className="ce-primary" disabled={busy || !dirty} onClick={() => void save()}>{busy ? '保存中…' : '保存修改'}</button></div></header>
    <div className="ce-interaction-tools" role="group" aria-label="画布操作方式">{([['browse', '浏览'], ['select', '选择'], ['draw', '画笔']] as const).map(([value, label]) => <button key={value} type="button" aria-pressed={mode === value} disabled={busy} onClick={() => { setMode(value); setSelection(EMPTY_SELECTION); }}>{label}</button>)}<small>{mode === 'browse' ? '手指可以上下滑动页面；编辑对象请选“选择”，画画请选“画笔”。' : mode === 'draw' ? '在白色画布里画画；想滑动页面时切回“浏览”。' : '可点选、拖动和缩放对象；滑动页面时切回“浏览”。'}</small></div>
    {mode === 'draw' ? <div className="ce-ink-tools"><label>画笔颜色<input type="color" value={inkColor} disabled={busy} onChange={event => setInkColor(event.target.value)} /></label><label>粗细<select value={inkWidth} disabled={busy} onChange={event => setInkWidth(Number(event.target.value))}><option value={3}>细</option><option value={6}>中</option><option value={10}>粗</option><option value={16}>特粗</option></select></label><button type="button" disabled={busy || !history.present.canvas.elements.some(isFreehandElement)} onClick={() => { transact({ origin: 'toolbar', history: 'record', operations: [{ type: 'element.deleteMany', elementIds: history.present.canvas.elements.filter(isFreehandElement).map(element => element.id) }] }); setSelection(EMPTY_SELECTION); }}>清除涂画</button><small>一笔一撤销；清除涂画保留原来的文字与图形。</small></div>
      : mode === 'select' && <div className="ce-shape-tools"><span>形状</span><button disabled={busy} onClick={() => insertShape('rectangle')}>▭ 矩形</button><button disabled={busy} onClick={() => insertShape('circle')}>○ 圆形</button><button disabled={busy} onClick={() => insertShape('triangle')}>△ 三角形</button><small>选择对象可拖动、缩放或旋转；双击文字可直接编辑。</small></div>}
    {error && <p className="cr-error" role="alert">{error}</p>}
    <div className={`ce-editor-canvas ce-canvas-${mode} ${busy ? 'is-saving' : ''}`} aria-busy={busy}>
      {mode === 'select' ? <EditableSlideCanvasWithUI slide={displayCanvas} documentSlide={history.present.canvas} selection={selection} onSelectionChange={changeSelection} onTransaction={transact} host={host} insertItems={['text', 'image', 'table', 'chart', 'line', 'background', 'latex']} snapping grid={50} ruler elementIdPrefix="educationagent-edit-" />
        : mode === 'browse' ? <SlideCanvas slide={displayCanvas} elementIdPrefix="educationagent-browse-" />
        : <FreehandSurface slide={displayCanvas} color={inkColor} width={inkWidth} disabled={busy} onError={setError} onStroke={element => {
          if (history.present.canvas.elements.length >= 1000 || JSON.stringify(history.present.canvas).length + JSON.stringify(element).length > 1_400_000) { setError('这一页涂画较多，请先保存，或清除不需要的笔迹。'); return; }
          transact({ origin: 'canvas', history: 'record', operations: [{ type: 'element.add', element }] });
        }} />}
    </div>
  </section>;
}
