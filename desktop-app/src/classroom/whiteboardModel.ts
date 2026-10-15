import type { ChartType, PPTElement, PPTShapeElement, Slide, SlideTheme } from '@educationagent/dsl';
import type { TeachingAction, WhiteboardState } from './types';
import { newLocalId } from './navigation';

const DEFAULT_THEME: SlideTheme = {
  backgroundColor: '#fffef9', themeColors: ['#376951', '#dfa65c', '#6a91b1', '#b98195'],
  fontColor: '#263b35', fontName: 'Microsoft YaHei',
};
const escapeHtml = (text: string) => text.replace(/[&<>"']/g, value => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[value]!));
function number(value: unknown, fallback: number) { return typeof value === 'number' && Number.isFinite(value) ? value : fallback; }
function color(value: unknown, fallback: string) { return typeof value === 'string' && /^(#[a-f0-9]{3,8}|rgba?\([\d\s.,%]+\)|[a-z]{1,20})$/i.test(value) ? value : fallback; }

/** 白板和课件共用 DSL，编辑器不需要一套与保存格式脱节的绘图对象。 */
export function createWhiteboard(theme?: SlideTheme): WhiteboardState {
  const id = `wb-${newLocalId()}`;
  return {
    id, isOpen: true,
    canvas: { id, viewportSize: 1280, viewportRatio: 720 / 1280, theme: { ...(theme || DEFAULT_THEME) }, elements: [], background: { type: 'solid', color: '#fffef9' } },
  };
}

export function createShapeElement(shape: 'rectangle' | 'circle' | 'triangle', id = newLocalId(), x = 180, y = 140, width = 220, height = 150, fill = '#dce9d4'): PPTShapeElement {
  const path = shape === 'circle' ? 'M 100 50 A 50 50 0 1 1 0 50 A 50 50 0 1 1 100 50 Z' : shape === 'triangle' ? 'M 50 0 L 100 100 L 0 100 Z' : 'M 0 0 L 100 0 L 100 100 L 0 100 Z';
  return { id, type: 'shape', left: x, top: y, width, height, rotate: 0, viewBox: [100, 100], path, fixedRatio: false, fill, outline: { color: '#376951', width: 2, style: 'solid' } };
}

/** 只解释受限的教学绘图命令，不执行模型返回的任意代码。 */
export function applyWhiteboardAction(current: WhiteboardState | undefined, action: TeachingAction, theme?: SlideTheme): WhiteboardState {
  const board = current ? { ...current, canvas: { ...current.canvas, elements: [...current.canvas.elements] } } : createWhiteboard(theme);
  if (action.type === 'wb_open') return { ...board, isOpen: true };
  if (action.type === 'wb_close') return { ...board, isOpen: false };
  if (action.type === 'wb_clear') return { ...board, isOpen: true, canvas: { ...board.canvas, elements: [] } };
  if (action.type === 'wb_delete') {
    if (!board.canvas.elements.some(element => element.id === action.elementId)) throw new Error('要删除的白板对象已不存在。');
    return { ...board, canvas: { ...board.canvas, elements: board.canvas.elements.filter(element => element.id !== action.elementId) } };
  }
  const id = typeof action.elementId === 'string' && action.elementId ? action.elementId : `el-${String(action.id || newLocalId())}`;
  const x = number(action.x, 100), y = number(action.y, 100), width = Math.max(1, number(action.width, 520)), height = Math.max(1, number(action.height, 100));
  const base = { id, left: x, top: y, width, height, rotate: 0 };
  let element: PPTElement;
  switch (action.type) {
    case 'wb_draw_text': {
      const text = String(action.content || action.text || '');
      if (!text.trim()) throw new Error('老师没有提供要写在白板上的文字。');
      const fontSize = Math.min(180, Math.max(10, number(action.fontSize, 28)));
      element = { ...base, type: 'text', content: `<p style="font-size:${fontSize}px">${escapeHtml(text).replace(/\n/g, '<br>')}</p>`, defaultColor: color(action.color, '#263b35'), defaultFontName: 'Microsoft YaHei' };
      break;
    }
    case 'wb_draw_shape': {
      if (!['rectangle', 'circle', 'triangle'].includes(String(action.shape))) throw new Error('不支持这个白板形状。');
      element = createShapeElement(action.shape as 'rectangle' | 'circle' | 'triangle', id, x, y, width, height, color(action.fillColor, '#dce9d4'));
      break;
    }
    case 'wb_draw_latex': {
      if (typeof action.latex !== 'string' || !action.latex.trim()) throw new Error('公式内容为空。');
      element = { ...base, type: 'latex', latex: action.latex, color: color(action.color, '#263b35'), align: 'left', fixedRatio: false };
      break;
    }
    case 'wb_draw_line': {
      const startX = number(action.startX, 100), startY = number(action.startY, 100), endX = number(action.endX, 500), endY = number(action.endY, 100);
      const left = Math.min(startX, endX), top = Math.min(startY, endY);
      element = { id, type: 'line', left, top, width: Math.min(20, Math.max(1, number(action.width, 3))), start: [startX - left, startY - top], end: [endX - left, endY - top], color: color(action.color, '#376951'), style: ['solid', 'dashed', 'dotted'].includes(String(action.style)) ? action.style as 'solid' | 'dashed' | 'dotted' : 'solid', points: ['', ''] };
      break;
    }
    case 'wb_draw_table': {
      const rows = action.data;
      if (!Array.isArray(rows) || !rows.length || rows.length > 30 || !rows.every(row => Array.isArray(row) && row.length > 0 && row.length <= 15)) throw new Error('白板表格数据无效。');
      const columns = Math.max(...rows.map(row => row.length));
      element = { ...base, type: 'table', outline: { width: 1, color: '#9bad97', style: 'solid' }, colWidths: Array(columns).fill(1 / columns), cellMinHeight: Math.max(28, height / rows.length), data: rows.map((row, r) => Array.from({ length: columns }, (_, c) => ({ id: `${id}-${r}-${c}`, text: escapeHtml(String(row[c] ?? '')), rowspan: 1, colspan: 1 }))) };
      break;
    }
    case 'wb_draw_chart': {
      if (!['bar', 'column', 'line', 'pie', 'ring', 'area', 'radar', 'scatter'].includes(String(action.chartType))) throw new Error('不支持这个图表类型。');
      const data = action.data as { labels?: unknown; legends?: unknown; series?: unknown } | undefined;
      if (!data || !Array.isArray(data.labels) || !Array.isArray(data.legends) || !Array.isArray(data.series) || !data.series.every(series => Array.isArray(series) && series.every(value => typeof value === 'number' && Number.isFinite(value)))) throw new Error('图表缺少有效数据。');
      element = { ...base, type: 'chart', chartType: action.chartType as ChartType, data: { labels: data.labels.map(String), legends: data.legends.map(String), series: data.series as number[][] }, themeColors: Array.isArray(action.themeColors) ? action.themeColors.map(value => color(value, '#376951')) : DEFAULT_THEME.themeColors };
      break;
    }
    case 'wb_draw_code': {
      if (typeof action.code !== 'string' || !action.code.trim()) throw new Error('代码示例为空。');
      element = { ...base, type: 'code', language: typeof action.language === 'string' ? action.language : 'text', lines: action.code.split('\n').map((content, i) => ({ id: `${id}-L${i + 1}`, content })), showLineNumbers: true, fontSize: 20, ...(typeof action.fileName === 'string' ? { fileName: action.fileName } : {}) };
      break;
    }
    default: throw new Error(`未实现的白板操作：${action.type}`);
  }
  const existing = board.canvas.elements.find(item => item.id === id);
  if (existing) {
    if (JSON.stringify(existing) === JSON.stringify(element)) return { ...board, isOpen: true };
    throw new Error('白板中已有同名对象，请先更新或删除它。');
  }
  return { ...board, isOpen: true, canvas: { ...board.canvas, elements: [...board.canvas.elements, element] } };
}

export function elementSummary(element: PPTElement): string {
  let text = '';
  switch (element.type) {
    case 'text': text = element.content; break;
    case 'shape': text = element.text?.content || '形状'; break;
    case 'latex': text = element.latex; break;
    case 'chart': text = element.data.labels.join('、'); break;
    case 'table': text = element.data.flat().map(cell => cell.text).join(' '); break;
    case 'code': text = element.lines.map(line => line.content).join('\n'); break;
    case 'image': text = '图片'; break;
    case 'line': text = '线条'; break;
    default: text = element.type;
  }
  return text.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 180) || '画布对象';
}

export function selectedElementStillExists(slide: Slide | undefined, id?: string): boolean {
  return Boolean(id && slide?.elements.some(element => element.id === id));
}
