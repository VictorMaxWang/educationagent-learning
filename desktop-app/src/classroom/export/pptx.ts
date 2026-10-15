import PptxGenJS from 'pptxgenjs';
import type { PPTElement, PPTLatexElement, Slide } from '@educationagent/dsl';
import { checkAbort, type ExportProgress, type PortableCourse, type PortableScene } from './contract';

const PAGE_WIDTH = 13.333333;
const PAGE_HEIGHT = 7.5;
type PptxSlide = ReturnType<PptxGenJS['addSlide']>;

export function plainText(html: string): string {
  if (typeof DOMParser !== 'undefined') {
    const document = new DOMParser().parseFromString(html.replace(/<br\s*\/?\s*>/gi, '\n').replace(/<\/(?:p|div|li|h[1-6])>/gi, '\n'), 'text/html');
    return (document.body.textContent || '').trim();
  }
  return html.replace(/<br\s*\/?\s*>/gi, '\n').replace(/<\/(?:p|div|li|h[1-6])>/gi, '\n').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&').trim();
}

function color(value?: string, fallback = '33483E'): string {
  const raw = (value || '').trim();
  if (/^#[0-9a-f]{6}$/i.test(raw)) return raw.slice(1).toUpperCase();
  if (/^#[0-9a-f]{3}$/i.test(raw)) return raw.slice(1).split('').map(char => char + char).join('').toUpperCase();
  const rgb = raw.match(/^rgba?\(\s*(\d+),\s*(\d+),\s*(\d+)/i);
  if (rgb) return rgb.slice(1, 4).map(part => Math.min(255, Number(part)).toString(16).padStart(2, '0')).join('').toUpperCase();
  return fallback;
}
function xml(value: string): string { return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function svgData(svg: string): string { return `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`; }

function layout(slide: Slide) {
  const width = Math.max(1, slide.viewportSize || 1280);
  const height = width * (slide.viewportRatio || 0.5625);
  const scale = Math.min(PAGE_WIDTH / width, PAGE_HEIGHT / height);
  const offsetX = (PAGE_WIDTH - width * scale) / 2;
  const offsetY = (PAGE_HEIGHT - height * scale) / 2;
  return { scale, offsetX, offsetY, box: (element: { left: number; top: number; width: number; height?: number }) => ({ x: offsetX + (element.left || 0) * scale, y: offsetY + (element.top || 0) * scale, w: Math.max(0.01, element.width * scale), h: Math.max(0.01, (element.height || 1) * scale) }) };
}

/** 公式使用已有 SVG 路径，或在浏览器按实际 KaTeX 布局截为图片；不依赖 fork 的 addFormula。 */
async function formulaImage(element: PPTLatexElement): Promise<string | null> {
  if (element.path && element.viewBox) {
    return svgData(`<svg xmlns="http://www.w3.org/2000/svg" width="${element.width}" height="${element.height}" viewBox="0 0 ${element.viewBox[0]} ${element.viewBox[1]}"><path d="${xml(element.path)}" fill="none" stroke="#${color(element.color)}" stroke-width="${element.strokeWidth || 1}"/></svg>`);
  }
  if (typeof document === 'undefined') return null;
  const container = document.createElement('div');
  container.style.cssText = `position:fixed;left:-10000px;top:0;display:inline-block;padding:8px;color:#${color(element.color)};background:transparent;font-size:30px;`;
  const katex = (await import('katex')).default;
  container.innerHTML = katex.renderToString(element.latex || '', { throwOnError: false, trust: false, displayMode: true, strict: 'ignore' });
  document.body.appendChild(container);
  try {
    await document.fonts.ready;
    const { default: html2canvas } = await import('html2canvas');
    const canvas = await html2canvas(container, { backgroundColor: null, scale: 2, logging: false });
    return canvas.toDataURL('image/png');
  } finally { container.remove(); }
}

async function addCanvas(pptx: PptxGenJS, page: PptxSlide, canvas: Slide, warnings: string[], signal?: AbortSignal): Promise<void> {
  const { box, scale, offsetX, offsetY } = layout(canvas);
  page.background = { color: color(canvas.background?.color || canvas.theme?.backgroundColor, 'FFFFFF') };
  if (canvas.background?.image?.src?.startsWith('data:image/')) page.addImage({ data: canvas.background.image.src, x: 0, y: 0, w: PAGE_WIDTH, h: PAGE_HEIGHT });
  for (const element of canvas.elements) {
    checkAbort(signal);
    const rect = box(element);
    const rotate = 'rotate' in element ? element.rotate || 0 : 0;
    if (element.type === 'text') {
      const fontSize = Number(element.content.match(/font-size\s*:\s*([\d.]+)px/i)?.[1] || 24) * scale * 72;
      page.addText(plainText(element.content), { ...rect, rotate, fontSize: Math.max(6, Math.min(120, fontSize)), fontFace: element.defaultFontName || 'Microsoft YaHei', color: color(element.defaultColor), margin: 0, breakLine: false, valign: 'top', fit: 'shrink', bold: /<(?:strong|b)\b|font-weight\s*:\s*(?:bold|[6-9]00)/i.test(element.content), ...(element.fill ? { fill: { color: color(element.fill, 'FFFFFF') } } : {}) });
    } else if (element.type === 'image') {
      if (element.src.startsWith('data:image/')) page.addImage({ ...rect, rotate, data: element.src, altText: element.name || '课堂图片', flipH: element.flipH, flipV: element.flipV });
      else { page.addText('图片未能导出', { ...rect, fontSize: 12, color: '8B6D4B' }); warnings.push('有图片未能嵌入 PPTX'); }
      if (element.clip || element.colorMask || element.filters) warnings.push('PPTX 保留原始图片，部分裁切、滤镜或蒙版需手动核对');
    } else if (element.type === 'shape') {
      const nativeName = String(element.pathFormula || '');
      const native: Record<string, PptxGenJS.ShapeType> = { rect: pptx.ShapeType.rect, roundRect: pptx.ShapeType.roundRect, ellipse: pptx.ShapeType.ellipse, triangle: pptx.ShapeType.triangle, diamond: pptx.ShapeType.diamond, rightArrow: pptx.ShapeType.rightArrow };
      if (native[nativeName]) {
        page.addShape(native[nativeName], { ...rect, rotate, fill: { color: color(element.fill, 'FFFFFF'), transparency: element.opacity === undefined ? 0 : 100 - element.opacity * 100 }, line: { color: color(element.outline?.color || element.fill), width: (element.outline?.width || 0) * scale * 72 } });
      } else {
        const view = element.viewBox || [element.width, element.height];
        const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${element.width}" height="${element.height}" viewBox="0 0 ${view[0]} ${view[1]}"><path d="${xml(element.path)}" fill="#${color(element.fill, 'FFFFFF')}" stroke="#${color(element.outline?.color || element.fill)}" stroke-width="${element.outline?.width || 0}"/></svg>`;
        page.addImage({ ...rect, rotate, data: svgData(svg) });
      }
      if (element.text?.content) page.addText(plainText(element.text.content), { ...rect, rotate, margin: 3, valign: 'middle', fit: 'shrink', fontFace: element.text.defaultFontName || 'Microsoft YaHei', color: color(element.text.defaultColor), fontSize: Math.max(10, 24 * scale * 72) });
    } else if (element.type === 'line') {
      const x1 = offsetX + (element.left + element.start[0]) * scale;
      const y1 = offsetY + (element.top + element.start[1]) * scale;
      const x2 = offsetX + (element.left + element.end[0]) * scale;
      const y2 = offsetY + (element.top + element.end[1]) * scale;
      page.addShape(pptx.ShapeType.line, { x: Math.min(x1, x2), y: Math.min(y1, y2), w: Math.max(0.001, Math.abs(x2 - x1)), h: Math.max(0.001, Math.abs(y2 - y1)), flipH: x1 > x2, flipV: y1 > y2, line: { color: color(element.color), width: Math.max(0.5, element.width * scale * 72), beginArrowType: element.points?.[0] === 'arrow' ? 'triangle' : 'none', endArrowType: element.points?.[1] === 'arrow' ? 'triangle' : 'none', dashType: element.style === 'dashed' ? 'dash' : element.style === 'dotted' ? 'sysDot' : 'solid' } });
      if (element.curve || element.cubic || element.broken) warnings.push('PPTX 中部分曲线或折线转换为可编辑直线');
    } else if (element.type === 'chart') {
      const chartTypes: Record<string, PptxGenJS.CHART_NAME> = { bar: 'bar', column: 'bar', line: 'line', area: 'area', pie: 'pie', ring: 'doughnut', radar: 'radar', scatter: 'scatter' };
      const series = element.data.series.map((values, index) => ({ name: element.data.legends[index] || `系列 ${index + 1}`, labels: element.data.labels, values }));
      if (element.chartType === 'scatter' && series.length === 1) series.push({ ...series[0], name: '纵坐标' });
      // DSL 的 bar 是竖向柱图、column 是横向条图；遵循画布实现，避免导出后方向反转。
      page.addChart(chartTypes[element.chartType] || 'bar', series, { ...rect, showLegend: series.length > 1, showTitle: false, showValue: false, catAxisLabelFontSize: 11, valAxisLabelFontSize: 11, chartColors: element.themeColors?.length ? element.themeColors.map(value => color(value)) : ['4E8760', 'DDAA5E'], ...(element.chartType === 'column' ? { barDir: 'bar' as const } : { barDir: 'col' as const }) });
    } else if (element.type === 'table') {
      const rows: PptxGenJS.TableRow[] = element.data.map(row => row.map(cell => ({ text: plainText(cell.text), options: { colspan: Math.max(1, cell.colspan || 1), rowspan: Math.max(1, cell.rowspan || 1), bold: cell.style?.bold, italic: cell.style?.em, color: color(cell.style?.color), fill: { color: color(cell.style?.backcolor, 'FFFFFF') }, fontFace: cell.style?.fontname || 'Microsoft YaHei', fontSize: Math.max(8, Number.parseFloat(cell.style?.fontsize || '18') * scale * 72) } })));
      page.addTable(rows, { ...rect, border: { type: 'solid', color: color(element.outline?.color, 'CCD8CB'), pt: Math.max(0.5, (element.outline?.width || 1) * scale * 72) }, margin: 0.04, autoPage: false, colW: element.colWidths?.length ? element.colWidths.map(width => width * rect.w / element.colWidths.reduce((sum, item) => sum + item, 0)) : undefined, rowH: Math.max(0.1, element.cellMinHeight * scale) });
    } else if (element.type === 'latex') {
      const image = await formulaImage(element);
      if (image) page.addImage({ ...rect, rotate, data: image, altText: element.latex });
      else { page.addText(element.latex, { ...rect, fontSize: 18, color: color(element.color), margin: 0, fit: 'shrink' }); warnings.push('部分公式保留为 LaTeX 文本，需在 PowerPoint 中核对'); }
    } else if (element.type === 'code') {
      const value = element as PPTElement & { code?: string; lines?: { content?: string; text?: string; code?: string }[] };
      page.addText(value.code || value.lines?.map(line => line.content ?? line.text ?? line.code ?? '').join('\n') || '', { ...rect, fontFace: 'Consolas', fontSize: 12, color: '263D35', margin: 0.05, fit: 'shrink' });
    } else {
      warnings.push('PPTX 未包含视频或音频交互元素，正文与讲稿仍保留');
    }
  }
}

function notes(scene: PortableScene): string {
  return (scene.actions || []).filter(action => action.type === 'speech' || action.type === 'discussion').map(action => String(action.text || action.content || action.prompt || action.topic || '')).filter(Boolean).join('\n\n');
}
function heading(page: PptxSlide, title: string, subtitle?: string) {
  page.background = { color: 'F5F6EF' };
  page.addText(title, { x: 0.55, y: 0.45, w: 12.2, h: 0.8, fontFace: 'Microsoft YaHei', fontSize: 28, color: '2F5741', margin: 0, fit: 'shrink', bold: true });
  if (subtitle) page.addText(subtitle, { x: 0.55, y: 6.9, w: 12, h: 0.25, fontFace: 'Microsoft YaHei', fontSize: 10, color: '718172', margin: 0 });
}

export async function buildPptx(course: PortableCourse, options: { signal?: AbortSignal; onProgress?: (progress: ExportProgress) => void } = {}): Promise<{ blob: Blob; warnings: string[] }> {
  const pptx = new PptxGenJS();
  pptx.layout = 'LAYOUT_WIDE'; pptx.author = 'educationagent'; pptx.subject = course.subject; pptx.title = course.title;
  pptx.theme = { headFontFace: 'Microsoft YaHei', bodyFontFace: 'Microsoft YaHei' };
  const warnings: string[] = [];
  for (let index = 0; index < course.scenes.length; index++) {
    checkAbort(options.signal);
    const scene = course.scenes[index];
    options.onProgress?.({ phase: '生成可编辑 PPTX', completed: index, total: course.scenes.length });
    if (scene.type === 'slide' && scene.content.canvas) {
      const page = pptx.addSlide();
      await addCanvas(pptx, page, scene.content.canvas, warnings, options.signal);
      page.addNotes(notes(scene));
    } else if (scene.type === 'quiz' && scene.content.questions?.length) {
      for (const [questionIndex, question] of scene.content.questions.entries()) {
        const page = pptx.addSlide();
        heading(page, `${scene.title} · ${questionIndex + 1}`, `${course.title} · 离线练习`);
        page.addText(question.question, { x: 0.6, y: 1.45, w: 12.1, h: 1.3, fontFace: 'Microsoft YaHei', fontSize: 23, margin: 0, fit: 'shrink', color: '263D35' });
        page.addText(question.options.map(option => `${option.value}. ${option.label}`).join('\n\n'), { x: 0.7, y: 2.85, w: 11.9, h: 3.45, fontFace: 'Microsoft YaHei', fontSize: 19, margin: 0, fit: 'shrink', color: '33483E' });
        const answer = question.correctAnswer ?? question.answer;
        page.addNotes([notes(scene), `参考答案：${Array.isArray(answer) ? answer.join('、') : answer || '未附'}`, question.explanation || question.analysis || ''].join('\n\n'));
      }
    } else {
      const page = pptx.addSlide();
      heading(page, scene.title, `${course.title} · 动手探索`);
      page.addText('这一页包含互动活动。\n\n请打开本课程的 HTML 文件，或解压课堂 ZIP 后打开 index.html 完成操作。\n\nPowerPoint 中保留本页主题和讲授备注，网页交互不会转换为可点击的幻灯片。', { x: 0.8, y: 1.7, w: 11.6, h: 3.8, fontFace: 'Microsoft YaHei', fontSize: 22, color: '33483E', margin: 0, breakLine: false, fit: 'shrink' });
      page.addNotes(notes(scene));
      warnings.push('互动页在 PPTX 中为说明页；完整互动包含在 HTML/课堂 ZIP 中');
    }
    const boards = [...(scene.whiteboards || [])];
    const manual = course.runtime?.[scene.id]?.whiteboard?.canvas;
    if (manual) boards.push(manual);
    for (const board of boards) {
      const page = pptx.addSlide();
      await addCanvas(pptx, page, board, warnings, options.signal);
      page.addNotes(`${scene.title} · 课堂板书\n\n${notes(scene)}`);
    }
  }
  checkAbort(options.signal);
  const blob = await pptx.write({ outputType: 'blob' }) as Blob;
  return { blob, warnings: [...new Set(warnings)] };
}
