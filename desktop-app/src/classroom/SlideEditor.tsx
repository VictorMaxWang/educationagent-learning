import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { PPTElement, Slide, SlideContent } from '@educationagent/dsl';
import { applyEditorTransaction, createEditorHistory, redoEditorTransaction, undoEditorTransaction, type EditorTransaction } from '@educationagent/editor/core';
import { EMPTY_SELECTION, type Selection } from '@educationagent/editor/react';
import { EditableSlideCanvasWithUI, type EditorHostCapabilities } from '@educationagent/editor/ui';
import { newLocalId } from './navigation';
import { createShapeElement } from './whiteboardModel';
import ClassroomImagePicker from './ClassroomImagePicker';
import { resolveSlideAssets } from './slideAssets';

export default function SlideEditor({ canvas, title = '编辑课件', saving, onSave, onCancel, onSelect, onDirtyChange }: {
  canvas: Slide; title?: string; saving?: boolean;
  onSave(canvas: Slide): Promise<void>; onCancel(): void;
  onSelect?(element: PPTElement): void; onDirtyChange?(dirty: boolean): void;
}) {
  const [history, setHistory] = useState(() => createEditorHistory({ type: 'slide', canvas }));
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);
  const [error, setError] = useState('');
  const [localSaving, setLocalSaving] = useState(false);
  const saved = useRef<SlideContent>(history.present);
  const busy = Boolean(saving || localSaving);
  const dirty = history.present !== saved.current;
  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);
  const host = useMemo<EditorHostCapabilities>(() => ({ locale: 'zh-CN', createElementId: () => newLocalId(), onError: event => setError(event.message), shortcutsEnabled: !busy, renderAssetPicker: request => <ClassroomImagePicker request={request} /> }), [busy]);
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
    <div className="ce-shape-tools"><span>形状</span><button disabled={busy} onClick={() => insertShape('rectangle')}>▭ 矩形</button><button disabled={busy} onClick={() => insertShape('circle')}>○ 圆形</button><button disabled={busy} onClick={() => insertShape('triangle')}>△ 三角形</button><small>选择对象可拖动、缩放或旋转；双击文字可直接编辑。</small></div>
    {error && <p className="cr-error" role="alert">{error}</p>}
    <div className={`ce-editor-canvas ${busy ? 'is-saving' : ''}`} aria-busy={busy}>
      <EditableSlideCanvasWithUI slide={displayCanvas} documentSlide={history.present.canvas} selection={selection} onSelectionChange={changeSelection} onTransaction={transact} host={host} insertItems={['text', 'image', 'table', 'chart', 'line', 'background', 'latex']} snapping grid={50} ruler elementIdPrefix="educationagent-edit-" />
    </div>
  </section>;
}
