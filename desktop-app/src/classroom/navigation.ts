/** hash 路由不依赖服务器 rewrite，微信打开课程链接也能定位原来的课堂。 */
export function courseIdFromHash(hash: string): string | null {
  const match = /^#\/classroom\/([^/?#]+)(?:\?[^#]*)?$/.exec(hash);
  if (!match) return null;
  try { return decodeURIComponent(match[1]); } catch { return null; }
}
export function sceneIdFromHash(hash: string): string | null {
  if (!courseIdFromHash(hash)) return null;
  const query = new URLSearchParams(hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '');
  const scenes = query.getAll('scene');
  return scenes.length === 1 && scenes[0].trim() && scenes[0].length <= 160 && !/[\u0000-\u001f]/.test(scenes[0]) ? scenes[0] : null;
}
export function courseHash(id?: string | null, sceneId?: string | null): string {
  return id ? `#/classroom/${encodeURIComponent(id)}${sceneId ? `?scene=${encodeURIComponent(sceneId)}` : ''}` : '#/classroom';
}
/** 旧入口的场景映射只采信服务端返回且确实存在的ID；无法对应时使用当前课程的保存位置。 */
export function resolveEntryScene(course: { scenes: { id: string }[]; progress?: { sceneIndex: number }; entrySceneMap?: Record<string, string> }, requested?: string | null): string | null {
  const target = requested ? course.entrySceneMap?.[requested] || requested : null;
  if (target && course.scenes.some(scene => scene.id === target)) return target;
  return course.scenes[clampIndex(course.progress?.sceneIndex || 0, course.scenes.length)]?.id || null;
}
export function clampIndex(index: number, count: number): number {
  return Number.isFinite(index) ? Math.max(0, Math.min(Math.floor(index), Math.max(0, count - 1))) : 0;
}
export function newLocalId(): string {
  // HTTP 局域网可能没有 randomUUID；此 ID 用于去重，不充当认证凭据。
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
