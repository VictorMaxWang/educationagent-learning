import JSZip from 'jszip';
import { classroomRequest } from '../api';
import type { Course } from '../types';
import { blobToDataUrl } from './snapshot';
import { checkAbort, EXPORT_ASSET_MAX_BYTES, EXPORT_MAX_BYTES, PORTABLE_MAX_JSON_BYTES, validateEnvelope, type PortableEnvelope } from './contract';
import { crc32, isSafeArchivePath } from './storedZip';

const ASSET_PATH = /^assets\/[A-Za-z0-9_-]+\.(png|jpe?g|gif|webp)$/i;
const ASSET_MIME: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };

/** 先核对 ZIP 目录声明，拒绝压缩炸弹与逃逸路径，再让库解压实际需要的 JSON/图片。 */
export function inspectZipDirectory(buffer: ArrayBuffer): Map<string, { size: number; crc: number }> {
  const bytes = new Uint8Array(buffer);
  const view = new DataView(buffer);
  let footer = -1;
  for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65557); offset--) {
    if (view.getUint32(offset, true) === 0x06054b50 && offset + 22 + view.getUint16(offset + 20, true) === bytes.length) { footer = offset; break; }
  }
  if (footer < 0) throw new Error('ZIP 目录不完整');
  const count = view.getUint16(footer + 10, true);
  const centralSize = view.getUint32(footer + 12, true);
  let position = view.getUint32(footer + 16, true);
  if (view.getUint16(footer + 4, true) !== 0 || view.getUint16(footer + 6, true) !== 0 || count > 1000 || count === 0xffff || position + centralSize > footer) throw new Error('课程 ZIP 目录超过限制或使用不支持的多卷/ZIP64');
  let total = 0;
  const names = new Set<string>();
  const entries = new Map<string, { size: number; crc: number }>();
  for (let i = 0; i < count; i++) {
    if (position + 46 > footer || view.getUint32(position, true) !== 0x02014b50) throw new Error('ZIP 目录损坏');
    const size = view.getUint32(position + 24, true);
    if ((view.getUint16(position + 8, true) & 1) || ![0, 8].includes(view.getUint16(position + 10, true))) throw new Error('课程 ZIP 使用了不支持的加密或压缩方法');
    const nameLength = view.getUint16(position + 28, true);
    const extraLength = view.getUint16(position + 30, true);
    const commentLength = view.getUint16(position + 32, true);
    const end = position + 46 + nameLength + extraLength + commentLength;
    if (end > footer || size === 0xffffffff) throw new Error('ZIP 条目损坏或使用不支持的 ZIP64');
    const name = new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(position + 46, position + 46 + nameLength));
    const directory = name.endsWith('/');
    if (!isSafeArchivePath(directory ? name.slice(0, -1) : name) || names.has(name)) throw new Error('ZIP 包含不安全或重复的文件路径');
    names.add(name); total += size;
    entries.set(name, { size, crc: view.getUint32(position + 16, true) });
    if (size > EXPORT_MAX_BYTES || total > EXPORT_MAX_BYTES) throw new Error('ZIP 解压后超过 80 MB 上限');
    position = end;
  }
  if (position > footer) throw new Error('ZIP 目录越界');
  return entries;
}

/** 不用 checkCRC32 的全包预解压：按需流式读取并验证实际字节，目录伪报小尺寸也不能撑爆内存。 */
function readZipEntry(entry: JSZip.JSZipObject, maximum: number, expected?: { size: number; crc: number }): Promise<Uint8Array<ArrayBuffer>> {
  return new Promise((resolve, reject) => {
    const chunks: Uint8Array<ArrayBuffer>[] = [];
    let size = 0;
    let crc = 0;
    let failed = false;
    // JSZip 3.10 的 zipObject 实际提供此方法，发布的 JSZipObject 类型漏列了它。
    const stream = (entry as JSZip.JSZipObject & { internalStream(type: 'uint8array'): JSZip.JSZipStreamHelper<Uint8Array> }).internalStream('uint8array');
    stream.on('data', (part: Uint8Array) => {
      if (failed) return;
      size += part.byteLength;
      if (size > maximum || (expected && size > expected.size)) { failed = true; stream.pause(); reject(new Error('ZIP 条目实际解压内容超过限制')); return; }
      const copy = new Uint8Array(part);
      chunks.push(copy); crc = crc32(copy, crc);
    });
    stream.on('error', reject);
    stream.on('end', () => {
      if (failed) return;
      if (!expected || size !== expected.size || crc !== expected.crc) { reject(new Error('ZIP 条目长度或校验值错误')); return; }
      const result = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
      resolve(result);
    });
    stream.resume();
  });
}

async function substituteAssets(envelope: PortableEnvelope, zip: JSZip, entries: Map<string, { size: number; crc: number }>): Promise<PortableEnvelope> {
  const images = new Map<string, string>();
  const visit = async (value: unknown): Promise<unknown> => {
    if (typeof value === 'string' && value.startsWith('assets/')) {
      if (!ASSET_PATH.test(value)) throw new Error('课程包含不支持的资源路径');
      if (!images.has(value)) {
        const entry = zip.file(value);
        if (!entry) throw new Error(`课包缺少图片：${value}`);
        const bytes = await readZipEntry(entry, EXPORT_ASSET_MAX_BYTES, entries.get(value));
        if (bytes.byteLength > EXPORT_ASSET_MAX_BYTES) throw new Error('课包图片超过 12 MB 上限');
        const mime = ASSET_MIME[value.split('.').pop()!.toLowerCase()];
        images.set(value, await blobToDataUrl(new Blob([new Uint8Array(bytes)], { type: mime })));
      }
      return images.get(value)!;
    }
    if (Array.isArray(value)) return Promise.all(value.map(visit));
    if (value && typeof value === 'object') return Object.fromEntries(await Promise.all(Object.entries(value).map(async ([key, item]) => [key, await visit(item)])));
    return value;
  };
  return validateEnvelope(await visit(envelope));
}

export async function readPortableFile(file: File): Promise<PortableEnvelope> {
  if (file.size <= 0 || file.size > EXPORT_MAX_BYTES) throw new Error('课程文件须小于 80 MB 且不能为空');
  if (/\.json$/i.test(file.name)) {
    if (file.size > PORTABLE_MAX_JSON_BYTES) throw new Error('JSON 课程超过 20 MB 上限');
    return validateEnvelope(JSON.parse(await file.text()));
  }
  if (!/\.zip$/i.test(file.name)) throw new Error('请选择 educationagent JSON 或课堂 ZIP 文件');
  const buffer = await file.arrayBuffer();
  const entries = inspectZipDirectory(buffer);
  const zip = await JSZip.loadAsync(buffer, { checkCRC32: false });
  const entry = zip.file('course.json');
  if (!entry) throw new Error('ZIP 中缺少 course.json');
  const text = new TextDecoder('utf-8', { fatal: true }).decode(await readZipEntry(entry, PORTABLE_MAX_JSON_BYTES, entries.get('course.json')));
  if (new TextEncoder().encode(text).byteLength > PORTABLE_MAX_JSON_BYTES) throw new Error('课包课程 JSON 超过 20 MB 上限');
  return substituteAssets(validateEnvelope(JSON.parse(text)), zip, entries);
}

export async function importCourseFile(file: File, signal?: AbortSignal): Promise<Course> {
  checkAbort(signal);
  const envelope = await prepareImportedImages(await readPortableFile(file), signal);
  checkAbort(signal);
  const body = JSON.stringify(envelope);
  if (new TextEncoder().encode(body).byteLength > PORTABLE_MAX_JSON_BYTES) throw new Error('导入课程含图片后超过 20 MB，请减少图片后重试');
  return classroomRequest<Course>('/courses/import', { method: 'POST', body, signal });
}

/**
 * JSON/ZIP 中的图片先入本站缓存，再提交轻量课件。否则多张合法图片的 base64 会膨胀，
 * 使本项目自己导出的页面超过 Java 单页 1.5M 字符边界而无法重新导入。
 */
export async function prepareImportedImages(envelope: PortableEnvelope, signal?: AbortSignal): Promise<PortableEnvelope> {
  const uploaded = new Map<string, string>();
  let decodedBytes = 0;
  const upload = async (source: string): Promise<string> => {
    checkAbort(signal);
    if (uploaded.has(source)) return uploaded.get(source)!;
    const match = source.match(/^data:image\/(png|jpe?g|gif|webp);base64,([A-Za-z0-9+/=\r\n]+)$/i);
    if (!match || match[2].length > Math.ceil(8 * 1024 * 1024 / 3) * 4 + 16) throw new Error('导入图片格式无效或超过 8 MB');
    const raw = atob(match[2].replace(/\s/g, ''));
    if (raw.length === 0 || raw.length > 8 * 1024 * 1024 || decodedBytes + raw.length > EXPORT_MAX_BYTES) throw new Error('导入图片超过单图或课包大小上限');
    decodedBytes += raw.length;
    const extension = match[1].toLowerCase().replace('jpeg', 'jpg');
    const mime = extension === 'jpg' ? 'image/jpeg' : `image/${extension}`;
    const blob = new Blob([Uint8Array.from(raw, char => char.charCodeAt(0))], { type: mime });
    const form = new FormData(); form.append('file', blob, `course-image.${extension}`);
    const asset = await classroomRequest<{ url: string }>('/media/upload', { method: 'POST', body: form, signal });
    checkAbort(signal);
    if (!asset || !/^\/api\/classroom\/media\/[0-9a-f]{64}\.jpg$/.test(asset.url)) throw new Error('图片上传没有返回有效的本站资源地址');
    uploaded.set(source, asset.url);
    return asset.url;
  };
  const mediaKeys = new Set(['src', 'poster', 'pattern', 'backgroundimage']);
  const visit = async (value: unknown, key = '', imageMap = false): Promise<unknown> => {
    if (typeof value === 'string' && value.startsWith('data:image/') && (mediaKeys.has(key.toLowerCase()) || imageMap)) return upload(value);
    if (Array.isArray(value)) {
      const result = []; for (const item of value) result.push(await visit(item, key, imageMap)); return result;
    }
    if (value && typeof value === 'object') {
      const result: Record<string, unknown> = {};
      for (const [name, item] of Object.entries(value)) result[name] = await visit(item, name, name === 'pointImages' || imageMap);
      return result;
    }
    return value;
  };
  return validateEnvelope(await visit(envelope));
}
