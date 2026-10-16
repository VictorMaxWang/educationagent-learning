import { API_BASE_URL } from '../../api/baseUrl';
import { classroomRequest, synthesizeSpeech } from '../api';
import type { Course } from '../types';
import { companionIdentityText } from '../branding';
import { checkAbort, EXPORT_ASSET_MAX_BYTES, EXPORT_MAX_BYTES, validateEnvelope, type ExportOptions, type ExportSnapshot, type PortableEnvelope } from './contract';

const LOCAL_IMAGE = /^\/api\/classroom\/media\/[A-Za-z0-9_-]+\.(?:jpe?g|png|webp|gif)$/i;
const LOCAL_AUDIO = /^\/api\/classroom\/voice\/audio\/[A-Za-z0-9_-]+\.mp3$/i;
const INLINE_IMAGE = /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\r\n]+$/;
const MIME_EXTENSION: Record<string, string> = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp', 'audio/mpeg': 'mp3' };

export function imageReferenceAllowed(value: string): boolean {
  return LOCAL_IMAGE.test(value) || INLINE_IMAGE.test(value);
}

export async function blobToDataUrl(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  const chunks: string[] = [];
  for (let offset = 0; offset < bytes.length; offset += 24_576) {
    chunks.push(btoa(String.fromCharCode(...bytes.subarray(offset, offset + 24_576))));
  }
  return `data:${blob.type || 'application/octet-stream'};base64,${chunks.join('')}`;
}

function decodeDataImage(value: string): Blob {
  if (!INLINE_IMAGE.test(value)) throw new Error('图片必须是受支持的光栅格式');
  const comma = value.indexOf(',');
  const raw = atob(value.slice(comma + 1).replace(/\s/g, ''));
  const bytes = Uint8Array.from(raw, char => char.charCodeAt(0));
  return new Blob([bytes], { type: value.slice(5, value.indexOf(';')) });
}

/** 逐块读取并限制大小；Content-Length 缺失或伪造也不会无上限占用内存。 */
export async function readBoundedResponse(response: Response, maximum = EXPORT_ASSET_MAX_BYTES): Promise<Blob> {
  if (!response.ok) throw new Error(`资源读取失败（${response.status}）`);
  const claimed = Number(response.headers.get('content-length') || '0');
  if (claimed > maximum) { await response.body?.cancel(); throw new Error('资源超过导出大小上限'); }
  if (!response.body) throw new Error('资源没有正文');
  const reader = response.body.getReader();
  const chunks: Uint8Array<ArrayBuffer>[] = [];
  let size = 0;
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.byteLength;
      if (size > maximum) { await reader.cancel(); throw new Error('资源超过导出大小上限'); }
      chunks.push(new Uint8Array(part.value));
    }
  } finally { reader.releaseLock(); }
  if (size === 0) throw new Error('资源内容为空');
  return new Blob(chunks, { type: response.headers.get('content-type')?.split(';')[0] || 'application/octet-stream' });
}

async function inlineCss(css: string, resolve: (src: string) => Promise<string>, warnings: string[]): Promise<string> {
  let result = css.replace(/@import\s+(?:url\([^)]*\)|['"][^'"]+['"])[^;]*;?/gi, () => {
    warnings.push('互动页的外部样式导入未打包'); return '';
  });
  const references = [...result.matchAll(/url\(\s*(['"]?)(.*?)\1\s*\)/gi)];
  for (const reference of references.reverse()) {
    const source = reference[2].trim();
    if (source.startsWith('#') || /^data:font\//.test(source)) continue;
    const replacement = await resolve(source);
    result = result.slice(0, reference.index) + (replacement ? `url("${replacement}")` : 'none') + result.slice(reference.index! + reference[0].length);
  }
  return result;
}

/** 互动保留内联逻辑；资源变成 data URL，外链脚本不下载、不执行，也不留离线假链接。 */
export async function inlineInteractiveHtml(html: string, resolve: (src: string) => Promise<string>, warnings: string[]): Promise<string> {
  if (typeof DOMParser === 'undefined') throw new Error('互动课件导出需要浏览器的文档解析能力');
  const document = new DOMParser().parseFromString(html, 'text/html');
  for (const element of Array.from(document.querySelectorAll('base, meta[http-equiv], iframe, object, embed'))) element.remove();
  for (const element of Array.from(document.querySelectorAll('script[src], link'))) {
    warnings.push('互动页引用的外部脚本或样式未打包，相关功能需检查'); element.remove();
  }
  for (const element of Array.from(document.querySelectorAll('[src], [poster]'))) {
    for (const attribute of ['src', 'poster']) {
      const source = element.getAttribute(attribute);
      if (!source) continue;
      const replacement = await resolve(source);
      if (replacement) element.setAttribute(attribute, replacement); else element.removeAttribute(attribute);
    }
  }
  for (const element of Array.from(document.querySelectorAll('[srcset]'))) {
    element.removeAttribute('srcset'); warnings.push('互动页的多源图片已仅保留默认图片');
  }
  for (const element of Array.from(document.querySelectorAll('[href], [action], [formaction]'))) {
    for (const name of ['href', 'action', 'formaction']) {
      const value = element.getAttribute(name);
      if (value && !value.startsWith('#')) element.removeAttribute(name);
    }
  }
  for (const element of Array.from(document.querySelectorAll('style'))) element.textContent = await inlineCss(element.textContent || '', resolve, warnings);
  for (const element of Array.from(document.querySelectorAll('[style]'))) element.setAttribute('style', await inlineCss(element.getAttribute('style') || '', resolve, warnings));
  return '<!doctype html>' + document.documentElement.outerHTML;
}

export async function collectSnapshot(envelope: PortableEnvelope, options: ExportOptions): Promise<ExportSnapshot> {
  const copy = validateEnvelope(JSON.parse(JSON.stringify(envelope)));
  const snapshot: ExportSnapshot = { envelope: copy, assets: new Map(), narration: {}, warnings: [] };
  // 后端会删去这些字段；导出端再做一次最小化，防止未来接口扩展意外带出学生对话。
  const courseObject = copy.course as unknown as Record<string, unknown>;
  for (const key of ['messages', 'sourceText', 'generationContext', 'runtimeOperations', 'submissions', 'history']) delete courseObject[key];
  if (copy.course.runtime) copy.course.runtime = Object.fromEntries(Object.entries(copy.course.runtime).flatMap(([id, runtime]) => runtime?.whiteboard?.canvas ? [[id, { whiteboard: runtime.whiteboard }]] : []));
  copy.course.progress = { sceneIndex: 0, actionIndex: 0 };
  let totalBytes = 0;
  const resolved = new Map<string, string>();
  const storeAsset = async (source: string, kind: 'image' | 'audio'): Promise<string> => {
    checkAbort(options.signal);
    if (resolved.has(source)) return resolved.get(source)!;
    if (!(kind === 'image' ? imageReferenceAllowed(source) : LOCAL_AUDIO.test(source))) {
      snapshot.warnings.push(`未打包不支持的${kind === 'image' ? '图片' : '音频'}地址`); return '';
    }
    try {
      const blob = source.startsWith('data:') ? decodeDataImage(source) : await readBoundedResponse(await fetch(`${API_BASE_URL}${source}`, { signal: options.signal, redirect: 'error' }));
      if (blob.size > EXPORT_ASSET_MAX_BYTES) throw new Error('资源超过导出大小上限');
      const extension = MIME_EXTENSION[blob.type];
      if (!extension || (kind === 'image' && !blob.type.startsWith('image/')) || (kind === 'audio' && blob.type !== 'audio/mpeg')) throw new Error('资源类型与预期不符');
      if (totalBytes + blob.size > EXPORT_MAX_BYTES) throw new Error('离线课包超过 80 MB，请关闭配音或减少课程内容');
      const key = `assets/${kind}-${String(snapshot.assets.size + 1).padStart(4, '0')}.${extension}`;
      snapshot.assets.set(key, blob); resolved.set(source, key); totalBytes += blob.size;
      return key;
    } catch (error) {
      checkAbort(options.signal);
      if (error instanceof Error && error.message.includes('80 MB')) throw error;
      snapshot.warnings.push(`${kind === 'image' ? '图片' : '音频'}未能打包：${error instanceof Error ? error.message : '读取失败'}`);
      resolved.set(source, ''); return '';
    }
  };
  const replaceImages = async (value: unknown): Promise<void> => {
    if (!value || typeof value !== 'object') return;
    for (const [key, item] of Object.entries(value)) {
      if (typeof item === 'string' && ['src', 'poster', 'pattern'].includes(key) && item) {
        (value as Record<string, unknown>)[key] = await storeAsset(item, 'image');
      } else if (typeof item === 'object') await replaceImages(item);
    }
  };
  for (let sceneIndex = 0; sceneIndex < copy.course.scenes.length; sceneIndex++) {
    const scene = copy.course.scenes[sceneIndex];
    checkAbort(options.signal);
    // 仅整理导出深副本中的学伴身份。题干、教材、用户原话不参与替换，也不回写原课程。
    for (const action of scene.actions || []) if (action.type === 'speech' || action.type === 'discussion') {
      for (const field of ['text', 'content', 'prompt', 'topic'] as const) {
        const value = action[field];
        if (typeof value === 'string') action[field] = companionIdentityText(value);
      }
    }
    options.onProgress?.({ phase: '收集页面与图片', completed: sceneIndex, total: copy.course.scenes.length });
    if (scene.content.canvas) await replaceImages(scene.content.canvas);
    if (scene.whiteboards) await replaceImages(scene.whiteboards);
    if (copy.course.runtime?.[scene.id]?.whiteboard?.canvas) await replaceImages(copy.course.runtime[scene.id].whiteboard!.canvas);
    if (scene.type === 'interactive' && scene.content.html) {
      scene.content.html = await inlineInteractiveHtml(scene.content.html, async source => {
        const asset = await storeAsset(source, 'image');
        return asset ? blobToDataUrl(snapshot.assets.get(asset)!) : '';
      }, snapshot.warnings);
    }
    if (!options.includeNarration) continue;
    for (let actionIndex = 0; actionIndex < (scene.actions || []).length; actionIndex++) {
      const action = scene.actions[actionIndex];
      if (action.type !== 'speech') continue;
      const text = String(action.text || action.content || '').trim();
      if (!text) continue;
      checkAbort(options.signal);
      options.onProgress?.({ phase: `准备第 ${sceneIndex + 1} 页配音`, completed: actionIndex, total: scene.actions.length });
      try {
        const speech = await synthesizeSpeech(text, 'teacher', options.signal);
        const audio = [];
        for (const segment of speech.segments) {
          const asset = await storeAsset(segment.url, 'audio');
          if (asset) audio.push({ text: segment.text, asset });
        }
        if (audio.length !== speech.segments.length || audio.length === 0) throw new Error('配音片段不完整');
        (snapshot.narration[scene.id] ||= {})[String(actionIndex)] = audio;
      } catch (error) {
        checkAbort(options.signal);
        snapshot.warnings.push(`第 ${sceneIndex + 1} 页有一段配音未完成，保留完整文字讲稿`);
        if (error instanceof Error && error.message.includes('80 MB')) throw error;
      }
    }
  }
  snapshot.warnings = [...new Set(snapshot.warnings)];
  return snapshot;
}

export async function fetchExportSnapshot(course: Course, options: ExportOptions): Promise<ExportSnapshot> {
  checkAbort(options.signal);
  options.onProgress?.({ phase: '读取可携带课程快照', completed: 0, total: 1 });
  const envelope = await classroomRequest<PortableEnvelope>(`/courses/${encodeURIComponent(course.id)}/portable`, { signal: options.signal });
  return collectSnapshot(validateEnvelope(envelope), options);
}

/** 用于 HTML/PPTX 的独立副本；ZIP 的原始清单仍使用同一份相对资源索引。 */
export async function inlineSnapshotAssets(snapshot: ExportSnapshot): Promise<PortableEnvelope> {
  const urls = new Map<string, string>();
  for (const [key, blob] of snapshot.assets) urls.set(key, await blobToDataUrl(blob));
  const visit = (value: unknown): unknown => {
    if (typeof value === 'string') return urls.get(value) ?? value;
    if (Array.isArray(value)) return value.map(visit);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, visit(item)]));
    return value;
  };
  return visit(snapshot.envelope) as PortableEnvelope;
}
