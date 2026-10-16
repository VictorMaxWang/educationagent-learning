import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { BaseShapeElement } from '@educationagent/renderer/elements';
import { applyEditorTransaction, createEditorHistory, redoEditorTransaction, undoEditorTransaction } from '@educationagent/editor/core';
import { appendInkPoint, createFreehandElement, inkPointFromClient, isFreehandElement } from '../src/classroom/freehandModel';
import { createWhiteboard, createShapeElement } from '../src/classroom/whiteboardModel';

describe('自由画笔与现有文档事务', () => {
  it('屏幕缩放和留白换算回同一画布位置，画布外落笔被忽略', () => {
    const box = { left: 10, top: 20, width: 320, height: 320 };
    expect(inkPointFromClient(90, 135, box, 1280, 720)).toEqual({ x: 320, y: 180 });
    expect(inkPointFromClient(20, 30, box, 1280, 720)).toBeNull();
    expect(inkPointFromClient(900, -100, box, 1280, 720, true)).toEqual({ x: 1280, y: 0 });
  });
  it('一笔只产生一个可撤销形状，保存序列化后仍由原渲染器显示开放透明路径', () => {
    const element = createFreehandElement([{ x: 20, y: 30 }, { x: 80, y: 70 }, { x: 150, y: 35 }], '#123456', 6, 'ink-regression');
    let history = createEditorHistory({ type: 'slide', canvas: createWhiteboard().canvas });
    history = applyEditorTransaction(history, { origin: 'canvas', history: 'record', operations: [{ type: 'element.add', element }] });
    expect(history.past).toHaveLength(1); expect(history.present.canvas.elements).toHaveLength(1);
    const restored = JSON.parse(JSON.stringify(history.present.canvas.elements[0]));
    expect(restored.fill).toBe('transparent'); expect(restored.path).not.toContain('Z');
    expect(renderToStaticMarkup(createElement(BaseShapeElement, { elementInfo: restored }))).toContain(restored.path);
    expect(undoEditorTransaction(history).present.canvas.elements).toHaveLength(0);
    expect(redoEditorTransaction(undoEditorTransaction(history)).present.canvas.elements[0]).toEqual(element);
  });
  it('清除涂画只删笔迹，普通图形保留且一次撤销可全部恢复', () => {
    const shape = createShapeElement('rectangle', 'original'); const ink = createFreehandElement([{ x: 10, y: 10 }], '#376951', 10, 'ink-dot');
    let history = createEditorHistory({ type: 'slide', canvas: { ...createWhiteboard().canvas, elements: [shape, ink] } });
    history = applyEditorTransaction(history, { origin: 'toolbar', history: 'record', operations: [{ type: 'element.deleteMany', elementIds: history.present.canvas.elements.filter(isFreehandElement).map(element => element.id) }] });
    expect(history.present.canvas.elements).toEqual([shape]); expect(undoEditorTransaction(history).present.canvas.elements).toEqual([shape, ink]);
    expect(ink.fill).toBe('#376951'); expect(ink.path).toContain('A');
  });
  it('长笔画抽稀保留起止点，拒绝非有限坐标和不支持的样式', () => {
    let points = [{ x: 0, y: 0 }]; for (let i = 1; i <= 2000; i++) points = appendInkPoint(points, { x: i, y: i % 5 });
    expect(points.length).toBeLessThanOrEqual(500); expect(points[0]).toEqual({ x: 0, y: 0 }); expect(points.at(-1)).toEqual({ x: 2000, y: 0 });
    expect(() => createFreehandElement([{ x: NaN, y: 1 }], '#123456', 6)).toThrow('轨迹无效');
    expect(() => createFreehandElement([{ x: 0, y: 0 }], 'url(x)', 6)).toThrow('颜色');
  });
});
