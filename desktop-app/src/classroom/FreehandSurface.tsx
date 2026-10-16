import { useEffect, useRef, useState, type PointerEvent } from 'react';
import { SlideCanvas } from '@educationagent/renderer';
import type { Slide, PPTShapeElement } from '@educationagent/dsl';
import { appendInkPoint, createFreehandElement, inkPointFromClient, type InkPoint } from './freehandModel';

export default function FreehandSurface({ slide, color, width, disabled, onStroke, onError }: {
  slide: Slide; color: string; width: number; disabled?: boolean; onStroke(element: PPTShapeElement): void; onError(message: string): void;
}) {
  const surface = useRef<SVGSVGElement>(null);
  const active = useRef<{ id: number; points: InkPoint[]; color: string; width: number } | null>(null);
  const [points, setPoints] = useState<InkPoint[]>([]);
  const canvasWidth = slide.viewportSize || 1280;
  const canvasHeight = canvasWidth * (slide.viewportRatio || .5625);
  const clear = () => { active.current = null; setPoints([]); };
  useEffect(() => { if (disabled) clear(); }, [disabled]);
  useEffect(() => () => { active.current = null; }, []);
  const readPoint = (event: PointerEvent<SVGSVGElement>, clamp = false) => inkPointFromClient(event.clientX, event.clientY, event.currentTarget.getBoundingClientRect(), canvasWidth, canvasHeight, clamp);
  const begin = (event: PointerEvent<SVGSVGElement>) => {
    if (disabled || active.current || event.button !== 0 || event.isPrimary === false) return;
    const point = readPoint(event);
    if (!point) return;
    event.preventDefault(); event.stopPropagation();
    active.current = { id: event.pointerId, points: [point], color, width }; setPoints([point]);
    try { event.currentTarget.setPointerCapture(event.pointerId); } catch { /* 不支持capture的环境由离开事件取消，避免半笔落库。 */ }
  };
  const move = (event: PointerEvent<SVGSVGElement>) => {
    if (!active.current || active.current.id !== event.pointerId) return;
    const point = readPoint(event, true);
    if (!point) return;
    event.preventDefault();
    active.current.points = appendInkPoint(active.current.points, point); setPoints(active.current.points);
  };
  const finish = (event: PointerEvent<SVGSVGElement>) => {
    const stroke = active.current;
    if (!stroke || stroke.id !== event.pointerId) return;
    const end = readPoint(event, true);
    clear();
    try {
      event.currentTarget.releasePointerCapture?.(event.pointerId);
      onStroke(createFreehandElement(end ? appendInkPoint(stroke.points, end) : stroke.points, stroke.color, stroke.width));
    } catch (error) { onError(error instanceof Error ? error.message : '这一笔暂未保存，请重新画。'); }
  };
  return <div className="ce-freehand-surface">
    <SlideCanvas slide={slide} elementIdPrefix="educationagent-ink-" />
    <svg ref={surface} className="ce-ink-overlay" viewBox={`0 0 ${canvasWidth} ${canvasHeight}`} preserveAspectRatio="xMidYMid meet" aria-label="自由画画区域" role="img"
      onPointerDown={begin} onPointerMove={move} onPointerUp={finish}
      onPointerCancel={event => { if (event.pointerId === active.current?.id) clear(); }}
      onLostPointerCapture={event => { if (event.pointerId === active.current?.id) clear(); }}
      onPointerLeave={event => { if (active.current && !event.currentTarget.hasPointerCapture?.(active.current.id)) clear(); }}>
      {points.length === 1 ? <circle cx={points[0].x} cy={points[0].y} r={width / 2} fill={color} /> : points.length > 1 && <polyline points={points.map(point => `${point.x},${point.y}`).join(' ')} fill="none" stroke={color} strokeWidth={width} strokeLinejoin="round" strokeLinecap="round" />}
    </svg>
  </div>;
}
