/**
 * 图片灯箱 —— 聊天窗口内图片点击放大查看
 * 能力：滚轮/按钮缩放、拖拽平移、双击 100% ↔ 200% 切换；
 * 关闭方式：ESC 键 / 点击图片外空白 / 工具条 ✕。
 * 用法：<ImageLightbox src={previewSrc} onClose={() => setPreviewSrc(null)} />
 */
import { useEffect, useRef, useState } from 'react';

interface ImageLightboxProps {
  /** 预览图 src（null 时不渲染） */
  src: string | null;
  /** 关闭回调 */
  onClose: () => void;
}

const MIN_ZOOM = 0.5;
const MAX_ZOOM = 5;

export default function ImageLightbox({ src, onClose }: ImageLightboxProps) {
  const [zoom, setZoom] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ startX: number; startY: number; originX: number; originY: number } | null>(null);

  const clampZoom = (z: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z));

  // 换图时重置视图
  useEffect(() => {
    setZoom(1);
    setOffset({ x: 0, y: 0 });
  }, [src]);

  // ESC 关闭 + 滚轮缩放（native listener，{passive:false} 才能阻止页面滚动）
  useEffect(() => {
    if (!src) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      setZoom((z) => clampZoom(z - e.deltaY * 0.0015));
    };
    window.addEventListener('keydown', onKey);
    const el = wrapRef.current;
    el?.addEventListener('wheel', onWheel, { passive: false });
    return () => {
      window.removeEventListener('keydown', onKey);
      el?.removeEventListener('wheel', onWheel);
    };
  }, [src, onClose]);

  if (!src) return null;

  // 拖拽平移（指针捕获保证移出图片后仍跟手）
  const onPointerDown = (e: React.PointerEvent<HTMLImageElement>) => {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    dragRef.current = { startX: e.clientX, startY: e.clientY, originX: offset.x, originY: offset.y };
    setDragging(true);
  };
  const onPointerMove = (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragRef.current) return;
    setOffset({
      x: dragRef.current.originX + (e.clientX - dragRef.current.startX),
      y: dragRef.current.originY + (e.clientY - dragRef.current.startY),
    });
  };
  const onPointerUp = () => {
    dragRef.current = null;
    setDragging(false);
  };

  // 双击：100% ↔ 200% 切换
  const onDoubleClick = () => {
    setZoom((z) => (z === 1 ? 2 : 1));
    setOffset({ x: 0, y: 0 });
  };

  const reset = () => {
    setZoom(1);
    setOffset({ x: 0, y: 0 });
  };

  return (
    <div className="img-lightbox" ref={wrapRef} onClick={onClose}>
      {/* 图片舞台：空白区域 pointer-events:none，点击透传给背景关闭 */}
      <div className="img-lightbox-stage">
        <img
          className={`img-lightbox-img${dragging ? ' dragging' : ''}`}
          src={src}
          alt="图片放大预览"
          draggable={false}
          style={{ transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom})` }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          onDoubleClick={onDoubleClick}
          onClick={(e) => e.stopPropagation()}
        />
      </div>

      {/* 顶部工具条 */}
      <div className="img-lightbox-toolbar" onClick={(e) => e.stopPropagation()}>
        <span className="img-lightbox-zoom">{Math.round(zoom * 100)}%</span>
        <button title="缩小" onClick={() => setZoom((z) => clampZoom(z - 0.25))}>−</button>
        <button title="放大" onClick={() => setZoom((z) => clampZoom(z + 0.25))}>+</button>
        <button title="还原 100%" onClick={reset}>⤾</button>
        <button className="img-lightbox-close" title="关闭（Esc）" onClick={onClose}>✕</button>
        <span className="img-lightbox-hint">滚轮缩放 · 拖拽移动 · Esc 关闭</span>
      </div>
    </div>
  );
}
