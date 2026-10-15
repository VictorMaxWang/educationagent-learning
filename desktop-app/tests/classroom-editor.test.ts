import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { BaseLatexElement } from '@educationagent/renderer/elements';
import { applyEditorTransaction, createEditorHistory, redoEditorTransaction, undoEditorTransaction } from '@educationagent/editor/core';
import { applyWhiteboardAction, createShapeElement, createWhiteboard, elementSummary, selectedElementStillExists } from '../src/classroom/whiteboardModel';
import { resolveSlideAssets } from '../src/classroom/slideAssets';

describe('可保存的画布编辑', () => {
  it('移动、改尺寸、撤销和重做都作用于真实 DSL 内容，原画布不被原地改写', () => {
    const board = createWhiteboard();
    const element = createShapeElement('rectangle', 'rect-1');
    let history = createEditorHistory({ type: 'slide', canvas: board.canvas });
    history = applyEditorTransaction(history, { origin: 'toolbar', history: 'record', operations: [{ type: 'element.add', element }] });
    history = applyEditorTransaction(history, { origin: 'canvas', history: 'record', operations: [{ type: 'element.update', elementId: 'rect-1', patch: { left: 310, top: 220, width: 400, height: 240, rotate: 15 } }] });
    expect(history.present.canvas.elements[0]).toMatchObject({ id: 'rect-1', left: 310, top: 220, width: 400, height: 240, rotate: 15 });
    expect(board.canvas.elements).toHaveLength(0);
    const undone = undoEditorTransaction(history);
    expect(undone.present.canvas.elements[0].left).toBe(180);
    expect(redoEditorTransaction(undone).present.canvas.elements[0].left).toBe(310);
  });
  it('老师白板绘图可生成后续被选择/编辑的真实对象，删除后引用失效', () => {
    const original = createWhiteboard();
    const changed = applyWhiteboardAction(original, { id: 'teacher-1', type: 'wb_draw_text', elementId: 'reason', content: '先求总面积，再求每份面积。', x: 100, y: 140, width: 700 });
    expect(selectedElementStillExists(changed.canvas, 'reason')).toBe(true);
    expect(elementSummary(changed.canvas.elements[0])).toContain('先求总面积');
    expect(original.canvas.elements).toHaveLength(0);
    const deleted = applyWhiteboardAction(changed, { id: 'teacher-2', type: 'wb_delete', elementId: 'reason' });
    expect(selectedElementStillExists(deleted.canvas, 'reason')).toBe(false);
    expect(() => applyWhiteboardAction(deleted, { id: 'teacher-3', type: 'wb_delete', elementId: 'reason' })).toThrow('已不存在');
  });
  it('支持公式、形状、线、表格、图表和代码对象；模型文字不会变成可执行 HTML', () => {
    let board = createWhiteboard();
    board = applyWhiteboardAction(board, { id: 'a', type: 'wb_draw_latex', latex: '\\frac{1}{2}', x: 20, y: 20 });
    board = applyWhiteboardAction(board, { id: 'b', type: 'wb_draw_shape', shape: 'circle', x: 50, y: 100, width: 100, height: 100 });
    board = applyWhiteboardAction(board, { id: 'c', type: 'wb_draw_line', startX: 200, startY: 80, endX: 90, endY: 200 });
    board = applyWhiteboardAction(board, { id: 'd', type: 'wb_draw_table', data: [['<img src=x onerror=bad()>', '数值'], ['A', '2']], x: 400, y: 100, width: 300, height: 150 });
    board = applyWhiteboardAction(board, { id: 'e', type: 'wb_draw_chart', chartType: 'bar', data: { labels: ['A'], legends: ['面积'], series: [[2]] }, x: 400, y: 300, width: 300, height: 180 });
    board = applyWhiteboardAction(board, { id: 'f', type: 'wb_draw_code', language: 'python', code: 'x = 2\nprint(x)', x: 20, y: 400 });
    expect(board.canvas.elements.map(element => element.type)).toEqual(['latex', 'shape', 'line', 'table', 'chart', 'code']);
    const table = board.canvas.elements[3];
    expect(table.type === 'table' && table.data[0][0].text).toContain('&lt;img');
    const code = board.canvas.elements[5];
    expect(code.type === 'code' && code.lines).toHaveLength(2);
    expect(() => applyWhiteboardAction(board, { id: 'f', type: 'wb_draw_code', code: 'again' })).toThrow('同名对象');
  });
  it('白板清空与关闭更新实际文档，不把不支持的命令当作成功', () => {
    const board = applyWhiteboardAction(undefined, { id: 'open', type: 'wb_open' });
    expect(board.isOpen).toBe(true);
    expect(applyWhiteboardAction(board, { id: 'close', type: 'wb_close' }).isOpen).toBe(false);
    expect(applyWhiteboardAction(board, { id: 'clear', type: 'wb_clear' }).canvas.elements).toHaveLength(0);
    expect(() => applyWhiteboardAction(board, { id: 'invalid', type: 'wb_edit_code' })).toThrow('未实现');
  });
});

describe('在线课件与白板公式显示适配', () => {
  it('plain latex 在实际公式渲染器中产生可见排版，同时保留持久对象身份和源码', () => {
    const board = applyWhiteboardAction(createWhiteboard(), { id: 'formula', type: 'wb_draw_latex', elementId: 'fraction', latex: '\\frac{1}{2}', x: 60, y: 90 });
    const stored = board.canvas.elements[0];
    Object.freeze(stored);
    const rendered = resolveSlideAssets(board.canvas);
    const formula = rendered.elements[0];
    expect(formula).toMatchObject({ id: 'fraction', type: 'latex', latex: '\\frac{1}{2}', left: 60, top: 90 });
    expect('html' in stored).toBe(false);
    expect(formula).not.toBe(stored);
    if (formula.type !== 'latex') throw new Error('预期公式对象');
    const markup = renderToStaticMarkup(createElement(BaseLatexElement, { elementInfo: formula }));
    expect(markup).toContain('class="katex"');
    expect(markup).toContain('mfrac');
    expect(resolveSlideAssets(board.canvas).elements[0]).toEqual(formula);
  });
  it('无效公式回退为转义源码，不引入可执行 HTML；旧SVG公式保持原表示', () => {
    const invalid = applyWhiteboardAction(createWhiteboard(), { id: 'invalid-latex', type: 'wb_draw_latex', latex: '\\notACommand{<img src=x onerror=alert(1)>}' });
    const fallback = resolveSlideAssets(invalid.canvas).elements[0];
    expect(fallback.type).toBe('latex');
    if (fallback.type !== 'latex') throw new Error('预期公式对象');
    expect(fallback.html).toContain('&lt;img');
    expect(fallback.html).not.toContain('<img');
    const original = invalid.canvas.elements[0];
    if (original.type !== 'latex') throw new Error('预期公式对象');
    const legacy = { ...original, path: 'M 0 0 L 10 10', viewBox: [10, 10] as [number, number] };
    const displayed = resolveSlideAssets({ ...invalid.canvas, elements: [legacy] });
    expect(displayed.elements[0]).toBe(legacy);
  });
});
