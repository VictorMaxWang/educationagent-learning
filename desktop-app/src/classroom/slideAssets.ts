import type { PPTLatexElement, Slide } from '@educationagent/dsl';
import { renderLatexSource } from '@educationagent/editor/ui';
import { resolveApiUrl } from '../api/request';

const latexCache = new WeakMap<PPTLatexElement, { source: string; html: string }>();
const escapeHtml = (text: string) => text.replace(/[&<>"']/g, value => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[value]!));

/** 公式源码是持久数据；渲染器需要的安全 KaTeX HTML 只在显示副本中补齐。 */
function resolvePlainLatex(element: PPTLatexElement): PPTLatexElement {
  if (element.html || (element.path && element.viewBox)) return element;
  const source = typeof element.latex === 'string' ? element.latex : '';
  if (!source.trim()) return element;
  let cached = latexCache.get(element);
  if (!cached || cached.source !== source) {
    // 复用编辑器同一套本地 KaTeX 安全选项：trust=false、有限宏展开、每次独立宏表。
    const result = source.length <= 4000 ? renderLatexSource(source) : { error: '公式过长' };
    const html = 'html' in result ? result.html : `<span style="white-space:pre-wrap;font-family:monospace">${escapeHtml(source)}</span>`;
    cached = { source, html }; latexCache.set(element, cached);
  }
  return { ...element, html: cached.html };
}

/** 保存仍保留本站相对地址和公式源码，绘制时才补后端源及公式 HTML。 */
export function resolveSlideAssets(slide: Slide): Slide {
  return {
    ...slide,
    ...(slide.background?.image ? { background: { ...slide.background, image: { ...slide.background.image, src: resolveApiUrl(slide.background.image.src) } } } : {}),
    elements: slide.elements.map(element => element.type === 'image'
      ? { ...element, src: resolveApiUrl(element.src) }
      : element.type === 'latex' ? resolvePlainLatex(element) : element),
  };
}
