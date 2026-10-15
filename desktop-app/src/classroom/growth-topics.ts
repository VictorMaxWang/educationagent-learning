/** 成长推荐仅组织提问，不在前端伪造知识库回答。题目内容由 guide/topics 提供。 */
export const GROWTH_CATEGORY_IDS = ['study-stress', 'emotions', 'family', 'friends', 'campus-safety', 'life-safety', 'puberty', 'needs'] as const;
export type GrowthCategoryId = typeof GROWTH_CATEGORY_IDS[number];
export interface GrowthTopic { id: GrowthCategoryId; label: string; description: string; domain: string; questions: string[] }
export interface GrowthSource { file: string; content?: string; distance?: number | null }
export interface GrowthReply { id: string; text: string; sources: GrowthSource[]; suggestions: string[]; crisis: boolean; category: GrowthCategoryId }
export interface GrowthTopicsResponse { grade: number; persona: { name: string; identity: string; intro: string }; categories: GrowthTopic[] }
export interface GrowthMessage {
  id: string; role: 'user' | 'assistant'; content: string; category: GrowthCategoryId;
  sources?: GrowthSource[]; suggestions?: string[]; crisis?: boolean;
}
export const GROWTH_CATEGORY_ICONS: Record<GrowthCategoryId, string> = {
  'study-stress': '书', emotions: '心', family: '家', friends: '伴',
  'campus-safety': '校', 'life-safety': '安', puberty: '芽', needs: '声',
};
export const GROWTH_GRADE_LABELS = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三', '高一', '高二', '高三'];
export const GROWTH_STORAGE_PREFIX = 'educationagent.growth.conversation.v1.';

export function isGrowthCategory(value: unknown): value is GrowthCategoryId {
  return typeof value === 'string' && (GROWTH_CATEGORY_IDS as readonly string[]).includes(value);
}

/** 相关追问先于初始题，去重后轮换；旧项目的“静态先塞满”问题在这里显式避免。 */
export function growthSuggestionBatch(related: string[], initial: string[], page: number, count = 3): string[] {
  const values = [...new Set([...related, ...initial].filter(item => typeof item === 'string' && item.trim()).map(item => item.trim()))];
  if (!values.length) return [];
  const size = Math.max(1, Math.min(Math.floor(count) || 3, values.length));
  const start = ((Math.max(0, Math.floor(page) || 0) * size) % values.length);
  return Array.from({ length: size }, (_, index) => values[(start + index) % values.length]);
}

/** localStorage 是不可信输入，只恢复有界的展示字段，不接受任何服务端角色或脚本。 */
export function restoreGrowthMessages(raw: string | null): GrowthMessage[] {
  if (!raw || raw.length > 400_000) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.slice(-40).flatMap((value): GrowthMessage[] => {
      if (!value || typeof value !== 'object') return [];
      const item = value as Record<string, unknown>;
      if ((item.role !== 'user' && item.role !== 'assistant') || !isGrowthCategory(item.category)
        || typeof item.id !== 'string' || !item.id || item.id.length > 100
        || typeof item.content !== 'string' || !item.content.trim() || item.content.length > 6500) return [];
      const sources: GrowthSource[] = Array.isArray(item.sources) ? item.sources.slice(0, 3).flatMap(source => {
        if (!source || typeof source !== 'object' || typeof source.file !== 'string') return [];
        return [{ file: source.file.slice(0, 200), content: typeof source.content === 'string' ? source.content.slice(0, 1800) : undefined,
          distance: typeof source.distance === 'number' && Number.isFinite(source.distance) ? source.distance : undefined }];
      }) : [];
      const suggestions = Array.isArray(item.suggestions) ? item.suggestions.filter((s): s is string => typeof s === 'string' && s.length > 0 && s.length <= 200).slice(0, 6) : [];
      return [{ id: item.id, role: item.role, category: item.category, content: item.content,
        ...(item.role === 'assistant' ? { sources, suggestions, crisis: item.crisis === true } : {}) }];
    });
  } catch { return []; }
}

/** 请求历史最多12条/12000字，每条2000字；保留最近片段，界面仍显示完整回复。 */
export function growthRequestHistory(messages: GrowthMessage[]): { role: 'user' | 'assistant'; content: string }[] {
  const selected: { role: 'user' | 'assistant'; content: string }[] = [];
  let total = 0;
  for (const message of [...messages].reverse()) {
    if (selected.length >= 12) break;
    let content = message.content.slice(0, 2000);
    if (/[\ud800-\udbff]$/.test(content)) content = content.slice(0, -1);
    if (!content.trim()) continue;
    if (total + content.length > 12000) break;
    selected.unshift({ role: message.role, content }); total += content.length;
  }
  return selected;
}
