import { parse, parseFragment, serialize, type DefaultTreeAdapterMap } from 'parse5';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

type Node = DefaultTreeAdapterMap['node'];
type Parent = DefaultTreeAdapterMap['parentNode'];
type Element = DefaultTreeAdapterMap['element'];
export interface InteractivePolicyOptions { widgetType?: string }

const PROSE_TAGS = new Set(['p', 'div', 'span', 'br', 'strong', 'b', 'em', 'i', 'u', 's', 'sub', 'sup', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'small', 'blockquote']);
const DROP_TAGS = new Set(['script', 'style', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'form', 'input', 'button', 'textarea', 'select', 'svg', 'math']);

function children(node: Node): Node[] { return 'childNodes' in node ? node.childNodes : []; }
function element(node: Node): node is Element { return 'tagName' in node; }

/** 画布正文会进入宿主 DOM，因此只保留教学文本所需标记，不能依赖 iframe 沙箱。 */
export function sanitizeProse(html: string): string {
  const root = parseFragment(html);
  const visit = (parent: Parent): void => {
    parent.childNodes = parent.childNodes.flatMap((node) => {
      if (!element(node)) return node.nodeName === '#text' ? [node] : [];
      if (DROP_TAGS.has(node.tagName)) return [];
      visit(node);
      if (!PROSE_TAGS.has(node.tagName)) return node.childNodes;
      node.attrs = node.attrs.filter(({ name, value }) => {
        if (name === 'style') return !/url\s*\(|expression\s*\(|@import|behavior\s*:|-moz-binding/i.test(value);
        return name === 'data-inline-math' && value.length <= 2_000;
      });
      return [node];
    });
  };
  visit(root);
  return serialize(root);
}

/** 清理所有嵌套正文（shape/table 也包含 HTML），不修改原始生成对象。 */
export function sanitizeSlidePayload<T>(input: T, imageIds: ReadonlySet<string> = new Set()): T {
  const copy = structuredClone(input);
  const walk = (value: unknown): void => {
    if (!value || typeof value !== 'object') return;
    if (Array.isArray(value)) { value.forEach(walk); return; }
    const obj = value as Record<string, unknown>;
    for (const [key, item] of Object.entries(obj)) {
      if (key === 'link' || /^(?:on[a-z]+|srcdoc|innerhtml|outerhtml)$/i.test(key)) { delete obj[key]; continue; }
      if (typeof item === 'string' && ['style', 'fill', 'color', 'backgroundimage', 'filter'].includes(key.toLowerCase()) && /url\s*\(|expression\s*\(|@import|javascript:/i.test(item)) { obj[key] = ''; continue; }
      if (typeof item === 'string' && ['src', 'url', 'href', 'poster'].includes(key.toLowerCase())) {
        if (!isApprovedSlideImage(item, imageIds)) obj[key] = '';
      } else if (typeof item === 'string' && ['content', 'text'].includes(key) && /[<>]/.test(item)) obj[key] = sanitizeProse(item);
      else walk(item);
    }
  };
  walk(copy);
  return copy;
}

/** 画布媒体只能来自本页批准的生成占位、本地课堂资产或受限光栅 data URL。 */
export function isApprovedSlideImage(src: string, imageIds: ReadonlySet<string> = new Set()): boolean {
  return imageIds.has(src) && /^gen_img_[A-Za-z0-9_-]{1,80}$/.test(src)
    || /^data:image\/(?:png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=]+$/i.test(src) && src.length <= 400_000
    || /^\/api\/classroom\/(?:media|assets)\/[A-Za-z0-9_./-]+$/.test(src) && !src.includes('..');
}

const mathAssets = new Map<boolean, string>();
function localMathAssets(compact = false): string {
  const cached = mathAssets.get(compact);
  if (cached) return cached;
  const require = createRequire(import.meta.url);
  const root = dirname(require.resolve('katex/package.json'));
  const coreFonts = new Set(['Main-Regular', 'Main-Italic', 'Main-Bold', 'Math-Italic', 'AMS-Regular', 'Size1-Regular', 'Size2-Regular', 'Size3-Regular', 'Size4-Regular']);
  let css = readFileSync(join(root, 'dist/katex.min.css'), 'utf8');
  // 3D同时带着WebGL库时，只嵌入常规数学字体；书法/花体/等宽变体用本机字体回退。
  if (compact) css = css.replace(/@font-face\s*\{[^}]+\}/g, face => {
    const name = face.match(/KaTeX_([\w-]+)\.woff2/);
    return name && coreFonts.has(name[1]) ? face : '';
  });
  css = css.replace(/url\(fonts\/([^)]+)\)/g, (_all, file: string) => {
    if (!/^[\w.-]+\.(woff2|woff|ttf)$/.test(file)) return 'url()';
    const suffix = file.endsWith('woff2') ? 'woff2' : file.endsWith('woff') ? 'woff' : 'ttf';
    // 只嵌入 woff2，避免同一字体三种格式使 JSONL 超过进程通信上限。
    return suffix === 'woff2' ? `url(data:font/woff2;base64,${readFileSync(join(root, 'dist/fonts', file)).toString('base64')})` : 'url()';
  });
  const main = readFileSync(join(root, 'dist/katex.min.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
  const auto = readFileSync(join(root, 'dist/contrib/auto-render.min.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
  const assets = `<style>${css}</style><script>${main}</script><script>${auto}</script><script>document.addEventListener('DOMContentLoaded',function(){if(typeof renderMathInElement!=='function')return;const options={delimiters:[{left:'$$',right:'$$',display:true},{left:'\\\\[',right:'\\\\]',display:true},{left:'\\\\(',right:'\\\\)',display:false}],throwOnError:false,trust:false,strict:'ignore',maxExpand:1000,macros:{}};let pending=false;const observer=new MutationObserver(function(){if(!pending){pending=true;setTimeout(render,80)}});function render(){pending=false;observer.disconnect();renderMathInElement(document.body,options);observer.observe(document.body,{childList:true,subtree:true,characterData:true})}render()});</script>`;
  mathAssets.set(compact, assets);
  return assets;
}

const runtimeCache = new Map<string, string>();
function localRuntime(name: 'three' | 'code-runner'): string {
  let source = runtimeCache.get(name);
  if (!source) {
    try { source = readFileSync(new URL(`./runtime/${name}.js`, import.meta.url), 'utf8'); }
    catch { throw new Error(`课堂${name === 'three' ? '3D' : '编程'}运行资源尚未构建，请运行 build-classroom-interactive 脚本`); }
    if (source.length > 800_000) throw new Error('课堂运行资源超过内联大小限制');
    runtimeCache.set(name, source);
  }
  return `<script data-educationagent-runtime="${name}">${source.replace(/<\/script/gi, '<\\/script')}</script>`;
}

/** 只采纳唯一、可由服务端再次定位的稳定 ID。script/style 内的字符串不算真实目标。 */
export function interactiveTargets(html: string): { ids: Set<string>; controls: Map<string, 'value' | 'checked'> } {
  const doc = parse(html);
  const counts = new Map<string, number>();
  const candidates = new Map<string, 'value' | 'checked'>();
  const excluded = new Set(['html', 'head', 'body', 'script', 'style', 'meta', 'link', 'base', 'iframe', 'object', 'embed', 'canvas']);
  const walk = (node: Node): void => {
    if (element(node)) {
      if (['iframe', 'object', 'embed', 'base', 'link', 'meta', 'form'].includes(node.tagName)) return;
      const id = node.attrs.find(attr => attr.name === 'id')?.value;
      if (id && /^[A-Za-z][A-Za-z0-9_-]{0,126}$/.test(id)) {
        counts.set(id, (counts.get(id) || 0) + 1);
        if (excluded.has(node.tagName)) counts.set(id, 2);
        const kind = node.attrs.find(attr => attr.name === 'type')?.value?.toLowerCase() || 'text';
        if (['select', 'textarea'].includes(node.tagName) || node.tagName === 'input' && !['file', 'password', 'hidden', 'button', 'submit', 'reset', 'image'].includes(kind)) {
          candidates.set(id, ['checkbox', 'radio'].includes(kind) ? 'checked' : 'value');
        }
      }
    }
    children(node).forEach(walk);
  };
  walk(doc);
  return { ids: new Set([...counts].filter(([, count]) => count === 1).map(([id]) => id)),
    controls: new Map([...candidates].filter(([id]) => counts.get(id) === 1)) };
}

/**
 * 互动脚本需要运行，但不应访问宿主或网络。这里移除外部资源并植入最先执行的 CSP，
 * 浏览器仍须配合 sandbox="allow-scripts"，不得加入 allow-same-origin。
 */
export function restrictInteractiveHtml(html: string, options: InteractivePolicyOptions = {}): string {
  if (html.length > 600_000) throw new Error('互动内容过大，请减少场景复杂度后重试');
  const doc = parse(html);
  const forbidden = new Set(['iframe', 'object', 'embed', 'base', 'link', 'meta', 'form']);
  const visit = (parent: Parent): void => {
    parent.childNodes = parent.childNodes.filter((node) => {
      if (!element(node)) return true;
      if (forbidden.has(node.tagName)) return false;
      if (node.tagName === 'script' && node.attrs.some((attr) => attr.name === 'src')) return false;
      // 上游后处理器注入的自动排版使用 trust:true；删除后用受限本地版本替换。
      if (node.tagName === 'script' && children(node).some((child) => 'value' in child && child.value.includes('const katexOptions') && child.value.includes('observer.observe(document.body') && child.value.includes('setInterval'))) return false;
      node.attrs = node.attrs.filter(({ name, value }) => {
        if (['action', 'formaction', 'srcdoc', 'target', 'ping', 'download', 'nonce', 'integrity'].includes(name)) return false;
        if (['src', 'href', 'xlink:href', 'poster'].includes(name)) return value.startsWith('#') || /^data:image\/(png|jpeg|gif|webp);base64,/i.test(value);
        if (name === 'style' && /url\s*\(|@import|expression\s*\(/i.test(value)) return false;
        return true;
      });
      visit(node);
      return true;
    });
  };
  visit(doc);
  const cleanHtml = serialize(doc);
  if (options.widgetType === 'code' || options.widgetType === 'game') {
    const targets = interactiveTargets(cleanHtml);
    const required = options.widgetType === 'code' ? ['code-input', 'run-code', 'stop-code', 'code-output', 'code-status'] : ['game-goal', 'game-score', 'game-feedback', 'game-retry'];
    if (required.some(id => !targets.ids.has(id))) throw new Error(`${options.widgetType === 'code' ? '编程' : '游戏'}互动缺少必要的操作与反馈控件，请重新生成`);
    if (options.widgetType === 'code' && targets.controls.get('code-input') !== 'value') throw new Error('编程练习缺少可编辑的代码输入框');
  }
  const csp = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; worker-src blob:; connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'">`;
  const serialized = cleanHtml;
  const hasMath = /\\\(|\\\[|\$\$/.test(serialized);
  const runtime = options.widgetType === 'visualization3d' ? localRuntime('three') : options.widgetType === 'code' ? localRuntime('code-runner') : '';
  const documentHtml = serialized.replace('<head>', () => `<head>${csp}${runtime}${hasMath ? localMathAssets(options.widgetType === 'visualization3d') : ''}`);
  if (documentHtml.length > 1_190_000 || Buffer.byteLength(documentHtml, 'utf8') > 1_700_000) throw new Error('互动内容含运行资源后过大，请减少场景复杂度后重试');
  // srcDoc 也需要 standards mode；否则 KaTeX 在缺少 doctype 的模型输出中拒绝排版。
  return /^<!doctype html>/i.test(documentHtml) ? documentHtml : `<!DOCTYPE html>${documentHtml}`;
}
