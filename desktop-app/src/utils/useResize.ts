/**
 * 拖拽调整宽度 hook（导航栏 / 会话栏手动伸缩）
 * mousedown 后监听全局 mousemove/mouseup，宽度实时回调并 clamp 到合法区间。
 */
import { useCallback } from 'react';

/**
 * @param onChange 拖动中回调（已 clamp 的宽度）
 * @param clamp [min, max]
 * @param getWidth 每次 mousedown 时获取当前宽度（避免闭包旧值）
 * @returns onMouseDown 处理器
 */
export function useResize(
  onChange: (width: number) => void,
  clamp: [number, number],
  getWidth: () => number,
) {
  return useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      const startX = e.clientX;
      const startW = getWidth();
      const [min, max] = clamp;

      const move = (ev: MouseEvent) => {
        const w = startW + (ev.clientX - startX);
        onChange(Math.min(max, Math.max(min, w)));
      };
      const up = () => {
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      };

      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
    },
    [onChange, clamp, getWidth],
  );
}
