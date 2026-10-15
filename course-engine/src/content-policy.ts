import { parse, parseFragment, serialize, type DefaultTreeAdapterMap } from 'parse5';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

type Node = DefaultTreeAdapterMap['node'];
type Parent = DefaultTreeAdapterMap['parentNode'];
type Element = DefaultTreeAdapterMap['element'];

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
export function sanitizeSlidePayload<T>(input: T): T {
  const copy = structuredClone(input);
  const walk = (value: unknown): void => {
    if (!value || typeof value !== 'object') return;
    if (Array.isArray(value)) { value.forEach(walk); return; }
    const obj = value as Record<string, unknown>;
    for (const [key, item] of Object.entries(obj)) {
      if (typeof item === 'string' && ['content', 'text'].includes(key) && /[<>]/.test(item)) obj[key] = sanitizeProse(item);
      else walk(item);
    }
  };
  walk(copy);
  return copy;
}

let mathAssets: string | undefined;
function localMathAssets(): string {
  if (mathAssets) return mathAssets;
  const require = createRequire(import.meta.url);
  const root = dirname(require.resolve('katex/package.json'));
  const css = readFileSync(join(root, 'dist/katex.min.css'), 'utf8').replace(/url\(fonts\/([^)]+)\)/g, (_all, file: string) => {
    if (!/^[\w.-]+\.(woff2|woff|ttf)$/.test(file)) return 'url()';
    const suffix = file.endsWith('woff2') ? 'woff2' : file.endsWith('woff') ? 'woff' : 'ttf';
    // 只嵌入 woff2，避免同一字体三种格式使 JSONL 超过进程通信上限。
    return suffix === 'woff2' ? `url(data:font/woff2;base64,${readFileSync(join(root, 'dist/fonts', file)).toString('base64')})` : 'url()';
  });
  const main = readFileSync(join(root, 'dist/katex.min.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
  const auto = readFileSync(join(root, 'dist/contrib/auto-render.min.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
  mathAssets = `<style>${css}</style><script>${main}</script><script>${auto}</script><script>document.addEventListener('DOMContentLoaded',function(){if(typeof renderMathInElement==='function')renderMathInElement(document.body,{delimiters:[{left:'$$',right:'$$',display:true},{left:'\\\\[',right:'\\\\]',display:true},{left:'\\\\(',right:'\\\\)',display:false}],throwOnError:false,trust:false,strict:'ignore',maxExpand:1000,macros:{}})});</script>`;
  return mathAssets;
}

/**
 * 互动脚本需要运行，但不应访问宿主或网络。这里移除外部资源并植入最先执行的 CSP，
 * 浏览器仍须配合 sandbox="allow-scripts"，不得加入 allow-same-origin。
 */
export function restrictInteractiveHtml(html: string): string {
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
  const csp = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'">`;
  const serialized = serialize(doc);
  const hasMath = /\\\(|\\\[|\$\$/.test(serialized);
  const documentHtml = serialized.replace('<head>', `<head>${csp}${hasMath ? localMathAssets() : ''}`);
  // srcDoc 也需要 standards mode；否则 KaTeX 在缺少 doctype 的模型输出中拒绝排版。
  return /^<!doctype html>/i.test(documentHtml) ? documentHtml : `<!DOCTYPE html>${documentHtml}`;
}
