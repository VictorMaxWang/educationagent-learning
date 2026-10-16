import type { SupplementaryResource } from '../types';

/** 只允许明确的视频页，不解析跳转短链，也不允许凭据、端口、片段或追踪查询串。 */
export function canonicalVideoUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const match = /^https:\/\/(?:www\.)?bilibili\.com\/video\/(BV[0-9A-Za-z]{10})\/?(?:\?p=([1-9][0-9]{0,4}))?$/.exec(value);
  return match ? `https://www.bilibili.com/video/${match[1]}/?p=${match[2] || '1'}` : null;
}

function text(value: unknown, limit: number, required = false): string {
  if (value === undefined && !required) return '';
  if (typeof value !== 'string' || value.length > limit || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value) || required && !value.trim()) throw new Error('补充资料文字字段无效');
  return value.trim();
}

/** 字段逐项重建，防止接口将来的审计/提示词扩展被不经意打包或冒充已核验推荐。 */
export function portableSupplementaryResources(value: unknown, sceneIds: ReadonlySet<string>): SupplementaryResource[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > 192) throw new Error('补充资料列表无效');
  const seen = new Set<string>();
  const result: SupplementaryResource[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('补充资料格式无效');
    const row = item as Record<string, unknown>;
    const sceneId = text(row.sceneId, 200, true);
    const url = canonicalVideoUrl(row.url);
    if (!sceneIds.has(sceneId) || !url) throw new Error('补充资料必须对应本课页面和有效的 Bilibili 视频地址');
    if (!Number.isSafeInteger(row.durationSeconds) || Number(row.durationSeconds) <= 0 || Number(row.durationSeconds) > 86400) throw new Error('补充资料时长无效');
    if (row.goals !== undefined && (!Array.isArray(row.goals) || row.goals.length > 16)) throw new Error('补充资料目标无效');
    const parsed = new URL(url);
    const id = `${parsed.pathname.split('/')[2]}:${parsed.searchParams.get('p')}`;
    const key = `${sceneId}:${id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const cleaned: SupplementaryResource = {
      sceneId, id, url, title: text(row.title, 300, true), author: text(row.author, 200, true),
      durationSeconds: Number(row.durationSeconds), purpose: text(row.purpose, 200),
      goals: (row.goals as unknown[] | undefined || []).map(goal => text(goal, 300, true)),
      reason: text(row.reason, 1000), resourceStatus: 'historical',
    };
    // 核验时间仅保留为历史元数据；导入的状态一律不参与线上缓存有效性判断。
    if (typeof row.checkedAt === 'string' && row.checkedAt.length <= 40
      && /^\d{4}-\d{2}-\d{2}T[0-9:.]+(?:Z|[+-]\d{2}:\d{2})$/.test(row.checkedAt) && Number.isFinite(Date.parse(row.checkedAt))) cleaned.checkedAt = row.checkedAt;
    result.push(cleaned);
  }
  return result;
}

export function supplementaryForScene(course: { scenes: { id: string }[]; supplementaryResources?: SupplementaryResource[] }, sceneId: string): SupplementaryResource[] {
  return portableSupplementaryResources(course.supplementaryResources, new Set(course.scenes.map(scene => scene.id))).filter(item => item.sceneId === sceneId);
}

export function resourceDuration(seconds: number): string {
  return `${Math.floor(seconds / 60)}分${seconds % 60 ? `${seconds % 60}秒` : ''}`;
}

/** 链接文本按纯文字转义，避免资料标题中的 Markdown/HTML 生成额外外链。 */
export function markdownResourceLabel(value: string): string {
  return value.replace(/[\r\n]+/g, ' ').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/[\\`*_{}\[\]()!#|]/g, '\\$&');
}
