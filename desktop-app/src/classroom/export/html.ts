import { blobToDataUrl, inlineSnapshotAssets } from './snapshot';
import { checkAbort, EXPORT_MAX_BYTES, type ExportSnapshot, type PlayerData } from './contract';

/** JSON 中的 HTML 不能闭合外层 script，读取时 JSON.parse 会恢复原始字符串。 */
export function serializeScriptJson(value: unknown): string {
  return JSON.stringify(value).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}
export function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
export function offlineCsp(linkedMedia = false): string {
  return `default-src 'none'; script-src 'unsafe-inline' data: blob:; style-src 'unsafe-inline' data:; img-src data: blob:; media-src ${linkedMedia ? "'self' " : ''}data: blob:; font-src data:; frame-src data: blob:; worker-src blob:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'`;
}

export interface PlayerAssets { script: string; css: string }
export async function readPlayerAssets(signal?: AbortSignal): Promise<PlayerAssets> {
  const prefix = import.meta.env.BASE_URL || '/';
  const read = async (name: string) => {
    const response = await fetch(`${prefix}educationagent-export/${name}`, { signal, cache: 'no-cache' });
    if (!response.ok) throw new Error('离线播放器尚未构建，请先运行课堂构建命令');
    const content = await response.text();
    if (!content || /^\s*<!doctype html/i.test(content)) throw new Error('离线播放器资源缺失，请重新构建网页');
    return content;
  };
  const [script, css] = await Promise.all([read('player.js'), read('player.css')]);
  if (/<\/script\s*>/i.test(script) || /<\/style\s*>/i.test(css)) throw new Error('播放器资源不能安全嵌入导出文件');
  return { script, css };
}

export function assembleHtml(data: PlayerData, assets: PlayerAssets, linkedMedia = false): Blob {
  const title = escapeHtml(data.envelope.course.title);
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="${escapeHtml(offlineCsp(linkedMedia))}"><meta name="referrer" content="no-referrer"><title>${title} · 晓陶学伴</title><style>${assets.css}</style></head><body><div id="educationagent-player"><noscript>请在支持 JavaScript 的浏览器中打开本课程。</noscript></div><script id="educationagent-course-data" type="application/json">${serializeScriptJson(data)}</script><script>${assets.script}</script></body></html>`;
  const result = new Blob([html], { type: 'text/html;charset=utf-8' });
  if (result.size > EXPORT_MAX_BYTES) throw new Error('单文件课堂超过 80 MB，请取消配音或选择课堂 ZIP');
  return result;
}

export async function buildOfflineHtml(snapshot: ExportSnapshot, assets: PlayerAssets, options: { linkedMedia?: boolean; signal?: AbortSignal } = {}): Promise<Blob> {
  const envelope = await inlineSnapshotAssets(snapshot);
  const narration: PlayerData['narration'] = {};
  const encoded = new Map<string, string>();
  for (const [sceneId, actions] of Object.entries(snapshot.narration)) {
    narration[sceneId] = {};
    for (const [index, segments] of Object.entries(actions)) {
      narration[sceneId][index] = [];
      for (const segment of segments) {
        checkAbort(options.signal);
        if (!encoded.has(segment.asset)) {
          const blob = snapshot.assets.get(segment.asset);
          if (!blob) throw new Error('配音索引与导出资源不一致');
          encoded.set(segment.asset, options.linkedMedia ? segment.asset : await blobToDataUrl(blob));
        }
        narration[sceneId][index].push({ text: segment.text, src: encoded.get(segment.asset)! });
      }
    }
  }
  return assembleHtml({ envelope, narration, warnings: snapshot.warnings }, assets, options.linkedMedia);
}
