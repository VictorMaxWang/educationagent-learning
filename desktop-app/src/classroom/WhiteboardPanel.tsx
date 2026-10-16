import { useState } from 'react';
import { SlideCanvas } from '@educationagent/renderer';
import type { PPTElement } from '@educationagent/dsl';
import type { WhiteboardState } from './types';
import SlideEditor from './SlideEditor';
import { resolveSlideAssets } from './slideAssets';

export default function WhiteboardPanel({ board, selectedElementId, disabled, onSave, onSelect, onClose, onEditingChange, onDirtyChange }: {
  board: WhiteboardState; selectedElementId?: string; disabled?: boolean;
  onSave(next: WhiteboardState): Promise<void>; onSelect(element: PPTElement): void; onClose(): void;
  onEditingChange?(editing: boolean): void; onDirtyChange?(dirty: boolean): void;
}) {
  const [editing, setEditing] = useState(false);
  return <section className="ce-whiteboard" aria-label="课堂白板">
    {editing ? <SlideEditor key={board.id} title="编辑课堂白板" canvas={board.canvas} saving={disabled} onSave={canvas => onSave({ ...board, canvas })} onCancel={() => { setEditing(false); onEditingChange?.(false); }} onDirtyChange={onDirtyChange} /> : <>
      <header className="ce-whiteboard-heading"><div><strong>课堂白板</strong><span>老师和你一起推演，也可以点选对象提问。</span></div><div className="ce-editor-actions"><button disabled={disabled} onClick={() => { setEditing(true); onEditingChange?.(true); }}>编辑白板</button><button disabled={disabled || !board.canvas.elements.length} onClick={() => { if (window.confirm('清空当前白板上的所有对象？')) void onSave({ ...board, canvas: { ...board.canvas, elements: [] } }).catch(() => {}); }}>清空</button><button disabled={disabled} onClick={onClose}>收起</button></div></header>
      <div className="ce-whiteboard-canvas"><SlideCanvas slide={resolveSlideAssets(board.canvas)} onElementClick={element => onSelect(element)} effects={selectedElementId ? { highlight: { elementId: selectedElementId, color: '#d5a74d', opacity: .12, borderWidth: 2 } } : undefined} elementIdPrefix="educationagent-whiteboard-" /></div>
      {!board.canvas.elements.length && <p className="ce-board-hint">点击“编辑白板”添加文字、公式、图形，或用画笔自由画画；也可以请老师在白板上讲解。</p>}
    </>}
  </section>;
}
