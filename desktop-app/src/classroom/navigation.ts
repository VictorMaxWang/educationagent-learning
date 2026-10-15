/** hash 路由不依赖服务器 rewrite，微信打开课程链接也能定位原来的课堂。 */
export function courseIdFromHash(hash: string): string | null {
  const match = /^#\/classroom\/([^/?#]+)$/.exec(hash);
  if (!match) return null;
  try { return decodeURIComponent(match[1]); } catch { return null; }
}
export function courseHash(id?: string | null): string {
  return id ? `#/classroom/${encodeURIComponent(id)}` : '#/classroom';
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
