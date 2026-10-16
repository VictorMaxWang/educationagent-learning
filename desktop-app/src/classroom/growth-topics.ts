/** 成长推荐仅组织提问，不在前端伪造知识库回答。题目内容由 guide/topics 提供。 */
export const GROWTH_CATEGORY_IDS = ['study-stress', 'emotions', 'family', 'friends', 'campus-safety', 'life-safety', 'puberty', 'needs', 'english', 'general', 'practice'] as const;
export type GrowthCategoryId = typeof GROWTH_CATEGORY_IDS[number];
export const GROWTH_GROUPS = [
  { id: 'mind-communication', label: '心灵与沟通', mark: '心', description: '认识情绪，也练习把心里话说清楚', categories: ['study-stress', 'emotions', 'family', 'friends', 'needs'] },
  { id: 'safety-health', label: '安全与健康', mark: '安', description: '照顾自己，在生活中学会保护与求助', categories: ['campus-safety', 'life-safety', 'puberty'] },
  { id: 'language-general', label: '语言与通识', mark: '知', description: '从身边的问题出发，打开更大的世界', categories: ['english', 'general'] },
  { id: 'practice-creation', label: '实践与创造', mark: '创', description: '亲手试一试，让想法在操作中长出来', categories: ['practice'] },
] as const;
export type GrowthGroupId = typeof GROWTH_GROUPS[number]['id'];
export interface GrowthTopic { id: GrowthCategoryId; label: string; description: string; domain: string; questions: string[]; group?: GrowthGroupId }
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
  english: '语', general: '知', practice: '创',
};
export const GROWTH_GRADE_LABELS = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三', '高一', '高二', '高三'];
export const GROWTH_STORAGE_PREFIX = 'educationagent.growth.conversation.v1.';
export const GROWTH_GROUP_STORAGE_PREFIX = 'educationagent.growth.conversation.v2.';

export function growthGroupForCategory(category: GrowthCategoryId): GrowthGroupId {
  return GROWTH_GROUPS.find(group => (group.categories as readonly string[]).includes(category))?.id || 'mind-communication';
}
export function growthStorageKey(grade: number, group: GrowthGroupId): string { return `${GROWTH_GROUP_STORAGE_PREFIX}${grade}.${group}`; }

/** 旧记录只按已有分类迁入本组；保留 v1 原值，v2 的空数组也代表已清空，不能再次迁回。 */
export function readGrowthGroupMessages(storage: Pick<Storage, 'getItem'>, grade: number, group: GrowthGroupId): GrowthMessage[] {
  const current = storage.getItem(growthStorageKey(grade, group));
  return current !== null ? restoreGrowthMessages(current).filter(item => growthGroupForCategory(item.category) === group)
    : restoreGrowthMessages(storage.getItem(GROWTH_STORAGE_PREFIX + grade)).filter(item => growthGroupForCategory(item.category) === group);
}

/** 本地只提供可点选的问题；真实回答必须来自服务端，离线时不填造聊天记录。 */
export const GROWTH_FALLBACK_TOPICS: GrowthTopic[] = [
  ['study-stress', '学习压力', '慢慢找到适合自己的节奏', ['作业很多时，怎样安排先后顺序？', '这次考试没考好，怎样重新开始？', '我一做难题就着急，可以怎么办？']],
  ['emotions', '认识情绪', '听见感受，练习调节', ['生气时，怎样让自己先停一停？', '我不知道为什么难过，能一起理一理吗？', '遇到不开心的事，可以怎样表达？']],
  ['family', '家人沟通', '把心里话说给家人听', ['怎样和家人谈一谈我的学习计划？', '和家人意见不一样时，可以怎么说？', '我想得到家人的理解，可以从哪句话开始？']],
  ['friends', '朋友相处', '在相处中理解彼此', ['和朋友闹别扭后，怎样开口？', '怎样邀请新同学一起玩？', '朋友的玩笑让我不舒服，该怎么说？']],
  ['needs', '表达需要', '清楚、温和地说出想法', ['我需要帮助时，怎样说清楚？', '怎样礼貌地拒绝不想做的事？', '我想要一点独处时间，怎么表达？']],
  ['campus-safety', '校园安全', '学会保护，也学会求助', ['在校园里遇到推搡时可以怎么办？', '课间活动有哪些安全小习惯？', '看到同学被欺负，我可以怎样帮助？']],
  ['life-safety', '网络与生活安全', '辨别风险，照顾自己', ['陌生人索要照片和地址时怎么办？', '怎样辨别网上不可靠的信息？', '一个人在家时，有哪些安全提醒？']],
  ['puberty', '身体与青春期', '理解成长中的身体变化', ['身体发生变化时，有疑问可以找谁？', '怎样理解自己的身体边界？', '规律作息和运动能怎样帮助成长？']],
  ['english', '生活英语', '从一句熟悉的话开始', ['怎样用英语介绍我的家乡？', '请陪我练习向老师请教问题。', '怎样记住今天刚学的英语单词？']],
  ['general', '通识发现', '把好奇变成一个好问题', ['为什么雨后有时能看到彩虹？', '读到不理解的句子，可以怎样提问？', '怎样判断一段资料是否可信？']],
  ['practice', '动手探索', '观察、尝试，再解释', ['怎样用循环记录一周的浇水次数？', '为什么通分后分数的大小不会改变？', '做小实验时，怎样记录观察结果？']],
].map(([id, label, description, questions]) => ({ id, label, description, questions, domain: '', group: growthGroupForCategory(id as GrowthCategoryId) } as GrowthTopic));

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
