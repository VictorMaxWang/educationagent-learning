/**
 * P017 PDF p2–3 式(1)–(6)，单个学习者 n 的评价函数，不实现 MPIO 搜索算法。
 * b/g/Net 为0或1；能力C与难度R使用同一人工1…5刻度，t/T均为分钟。
 * 特别保留原式：f4=100*(M−C覆盖数)，没有除M；f5中心=总出现次数/覆盖数，
 * 但平方差遍历全部M个知识点（包括出现次数为0的点），不能替换为常见总体方差。
 */
export interface LearningResource {
  id: string; title: string; knowledge: readonly boolean[]; difficulty: number; minutes: number;
}
export const KNOWLEDGE_TOPICS = ['认识概念', '理解关系', '解答例题', '生活应用'] as const;
/** 全部数据为人工教学示例，未读取课程、用户记录、成绩或真实学生画像。 */
export const EXAMPLE_RESOURCES: readonly LearningResource[] = [
  { id: 'concept', title: '概念小卡', knowledge: [true, false, false, false], difficulty: 1, minutes: 5 },
  { id: 'picture', title: '看图讲原理', knowledge: [true, true, false, false], difficulty: 2, minutes: 8 },
  { id: 'example', title: '例题拆步骤', knowledge: [false, true, true, false], difficulty: 3, minutes: 12 },
  { id: 'application', title: '生活应用小任务', knowledge: [false, false, true, true], difficulty: 3, minutes: 15 },
  { id: 'review', title: '综合回顾', knowledge: [true, true, true, true], difficulty: 4, minutes: 18 },
  { id: 'practice', title: '动手再练习', knowledge: [false, false, true, true], difficulty: 2, minutes: 10 },
];
export const DEFAULT_RESOURCE_WEIGHTS = [1, 1, 0.2, 0.01, 0.5] as const;
export type FiveValues = [number, number, number, number, number];
interface EvaluationCommon { selectedCount: number; totalMinutes: number; counts: number[]; coveredCount: number }
export type ResourceEvaluation = EvaluationCommon & (
  { feasible: true; values: FiveValues; weighted: FiveValues; total: number }
  | { feasible: false; reason: string; values: null; weighted: null; total: null }
);

export interface ResourceEvaluationInput {
  resources: readonly LearningResource[]; selected: readonly boolean[]; goals: readonly boolean[];
  ability: number; timeBudgetMinutes: number; weights: readonly number[];
}

export function evaluateResources(input: ResourceEvaluationInput): ResourceEvaluation {
  const { resources, selected, goals, ability, timeBudgetMinutes, weights } = input;
  if (goals.length < 1 || selected.length !== resources.length || goals.some(value => typeof value !== 'boolean')
      || selected.some(value => typeof value !== 'boolean') || weights.length !== 5
      || weights.some(weight => !Number.isFinite(weight) || weight < 0)
      || !Number.isFinite(ability) || ability < 1 || ability > 5
      || !Number.isFinite(timeBudgetMinutes) || timeBudgetMinutes < 0
      || resources.some(resource => resource.knowledge.length !== goals.length
        || resource.knowledge.some(value => typeof value !== 'boolean')
        || !Number.isFinite(resource.difficulty) || resource.difficulty < 1 || resource.difficulty > 5
        || !Number.isFinite(resource.minutes) || resource.minutes < 0)) throw new RangeError('资源评价数据或单位无效');
  const chosen = resources.filter((_, index) => selected[index]);
  const selectedCount = chosen.length;
  const counts = goals.map((_, topic) => chosen.reduce((sum, resource) => sum + Number(resource.knowledge[topic]), 0));
  const coveredCount = counts.filter(count => count > 0).length;
  const totalMinutes = chosen.reduce((sum, resource) => sum + resource.minutes, 0);
  const common = { selectedCount, totalMinutes, counts, coveredCount };
  // 原式分母为0时保持“无可行评价”，既不自动选择资源，也不把未定义值改成0分。
  if (selectedCount === 0) return { ...common, feasible: false, reason: '请至少选择一项资源，当前没有可行评价。', values: null, weighted: null, total: null };
  if (coveredCount === 0) return { ...common, feasible: false, reason: '所选资源没有覆盖任何知识点，分布评价的分母为0，当前没有可行评价。', values: null, weighted: null, total: null };
  const f1 = chosen.reduce((sum, resource) => sum + goals.reduce((difference, wanted, topic) => difference
    + Math.abs(Number(resource.knowledge[topic]) - Number(wanted)), 0), 0) / selectedCount;
  const f2 = chosen.reduce((sum, resource) => sum + Math.abs(ability - resource.difficulty), 0) / selectedCount;
  const f3 = Math.max(totalMinutes - timeBudgetMinutes, 0);
  const f4 = 100 * (goals.length - coveredCount);
  const center = counts.reduce((sum, count) => sum + count, 0) / coveredCount;
  const f5 = counts.reduce((sum, count) => sum + (count - center) ** 2, 0);
  const values: FiveValues = [f1, f2, f3, f4, f5];
  const weighted = values.map((value, index) => value * weights[index]) as FiveValues;
  return { ...common, feasible: true, values, weighted, total: weighted.reduce((sum, value) => sum + value, 0) };
}
