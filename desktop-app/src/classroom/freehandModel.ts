import type { PPTElement, PPTShapeElement } from '@educationagent/dsl';
import { newLocalId } from './navigation';

export interface InkPoint { x: number; y: number }
const rounded = (value: number) => Math.round(value * 100) / 100;

/** 同一笔落在一个普通SVG形状里，沿用现有撤销、保存与课件导出，不保存屏幕像素坐标。 */
export function createFreehandElement(points: readonly InkPoint[], color: string, width: number, id = `ink-${newLocalId()}`): PPTShapeElement {
  if (!points.length || points.length > 600 || points.some(point => !Number.isFinite(point.x) || !Number.isFinite(point.y))) throw new Error('画笔轨迹无效，请重新画这一笔。');
  if (!/^#[0-9a-f]{6}$/i.test(color) || ![3, 6, 10, 16].includes(width)) throw new Error('请选择画笔颜色和粗细。');
  if (points.length === 1) {
    const radius = width / 2;
    return { id, type: 'shape', left: rounded(points[0].x - radius), top: rounded(points[0].y - radius), width, height: width, rotate: 0,
      viewBox: [width, width], path: `M 0 ${radius} A ${radius} ${radius} 0 1 1 ${width} ${radius} A ${radius} ${radius} 0 1 1 0 ${radius} Z`,
      fixedRatio: false, fill: color };
  }
  const padding = width / 2 + 1;
  const left = rounded(Math.min(...points.map(point => point.x)) - padding);
  const top = rounded(Math.min(...points.map(point => point.y)) - padding);
  const boxWidth = Math.max(1, rounded(Math.max(...points.map(point => point.x)) - left + padding));
  const boxHeight = Math.max(1, rounded(Math.max(...points.map(point => point.y)) - top + padding));
  return { id, type: 'shape', left, top, width: boxWidth, height: boxHeight, rotate: 0, viewBox: [boxWidth, boxHeight],
    path: points.map((point, index) => `${index ? 'L' : 'M'} ${rounded(point.x - left)} ${rounded(point.y - top)}`).join(' '),
    fixedRatio: false, fill: 'transparent', outline: { color, width, style: 'solid' } };
}

export function isFreehandElement(element: PPTElement): boolean {
  return element.type === 'shape' && element.id.startsWith('ink-');
}

/** SVG的meet留白与SlideCanvas居中缩放一致；空白边缘不创建笔迹。 */
export function inkPointFromClient(clientX: number, clientY: number, box: Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>,
  canvasWidth: number, canvasHeight: number, clampToCanvas = false): InkPoint | null {
  const scale = Math.min(box.width / canvasWidth, box.height / canvasHeight);
  if (!Number.isFinite(scale) || scale <= 0) return null;
  const x = (clientX - box.left - (box.width - canvasWidth * scale) / 2) / scale;
  const y = (clientY - box.top - (box.height - canvasHeight * scale) / 2) / scale;
  if (!clampToCanvas && (x < 0 || y < 0 || x > canvasWidth || y > canvasHeight)) return null;
  return { x: rounded(Math.max(0, Math.min(canvasWidth, x))), y: rounded(Math.max(0, Math.min(canvasHeight, y))) };
}

export function appendInkPoint(points: readonly InkPoint[], point: InkPoint): InkPoint[] {
  const last = points.at(-1);
  if (last && Math.hypot(point.x - last.x, point.y - last.y) < 1) return [...points];
  // 长笔画逐级抽稀而不截断末端，限制文档大小；每笔完成后仍只产生一条撤销记录。
  const retained = points.length >= 500 ? points.filter((_, index) => index === 0 || index % 2 === 0) : points;
  return [...retained, point];
}
