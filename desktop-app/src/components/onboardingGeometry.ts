export interface TourBox { left: number; top: number; width: number; height: number }
export interface TourPlacement { top: number; left: number; width: number; maxHeight: number; arrow: { side: 'top' | 'bottom'; offset: number } | null }
const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max));

/** 按实际气泡高度和手机可视区布局；键盘/浏览器栏缩小时仍保留可滚动的退出按钮。 */
export function positionTourBubble(target: TourBox | null, viewport: TourBox, measuredHeight: number): TourPlacement {
  const margin = 12, gap = 16;
  const width = Math.max(1, Math.min(360, viewport.width - 2 * margin));
  const maxHeight = Math.max(1, viewport.height - 2 * margin);
  const height = Math.min(maxHeight, Math.max(1, measuredHeight));
  const minLeft = viewport.left + margin, maxLeft = viewport.left + viewport.width - width - margin;
  const minTop = viewport.top + margin, maxTop = viewport.top + viewport.height - height - margin;
  const centered = { top: clamp(viewport.top + (viewport.height - height) / 2, minTop, maxTop), left: clamp(viewport.left + (viewport.width - width) / 2, minLeft, maxLeft), width, maxHeight, arrow: null };
  if (!target || target.top + target.height < viewport.top || target.top > viewport.top + viewport.height) return centered;
  const below = viewport.top + viewport.height - (target.top + target.height + gap) - margin;
  const above = target.top - viewport.top - gap - margin;
  // 当高亮区覆盖整个手机屏幕时，居中展示说明比画一个误导的箭头更可靠。
  if (below < height && above < height) return centered;
  const belowFits = below >= height;
  const left = clamp(target.left + target.width / 2 - width / 2, minLeft, maxLeft);
  return { top: clamp(belowFits ? target.top + target.height + gap : target.top - gap - height, minTop, maxTop), left, width, maxHeight,
    arrow: { side: belowFits ? 'top' : 'bottom', offset: clamp(target.left + target.width / 2 - left, 20, width - 20) } };
}

export function clippedTourTarget(target: TourBox | null, viewport: TourBox): TourBox | null {
  if (!target) return null;
  const left = Math.max(viewport.left, target.left - 6), top = Math.max(viewport.top, target.top - 6);
  const right = Math.min(viewport.left + viewport.width, target.left + target.width + 6);
  const bottom = Math.min(viewport.top + viewport.height, target.top + target.height + 6);
  return right > left && bottom > top ? { left, top, width: right - left, height: bottom - top } : null;
}
