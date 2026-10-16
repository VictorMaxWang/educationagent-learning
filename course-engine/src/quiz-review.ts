import type { HostQuizQuestion } from './engine.js';

/**
 * 习题两级审核：L1 确定性规则 + L2 盲解复核。
 *
 * 设计依据（本项目核心不变量）：**凡是自动计分的题目，答案必须正确；修复不了就不许计分。**
 *
 * 背景：曾出现一道题 20×8×3=480，四个选项 48/240/60/512 全是错的，标答还被标成 B（240），
 * 解析里漏出模型原始思考过程。整条链路（Node 结构校验、Java 结构校验、内容安全校验）
 * 都只查形状不查正确性，而 ClassroomQuizAdapter.attach() 会把错的标答原样存进题库当标准答案，
 * 从此 QuizService 永远拿它判学生。
 *
 * 本模块的边界要说清楚，不能吹大：
 * - L1 抓不住上面那道题：它选项合法、互异、解析非空（只是内容烂）。**只有 L2 能抓。**
 * - L2 不是「独立验证」。盲解与出题是**同族模型、temperature 0**，出题错一次复核可能原样再错。
 *   它的价值来自**上下文差异**（批量作答、不看答案、不产出思考过程），不是独立性。
 *   代码、证据字段与对外话术都不得称它「验证通过」。
 * - short_answer 无法用比对答案的方式复核，一律 UNVERIFIED；绝不因文本不同触发重生成（会误杀正确题）。
 * - fill 只有纯数值可数值比对，文本填空不比对。
 * - PASS 只意味着「第二次也同意」，不等于「正确」。
 * - 「没算出来」(UNVERIFIED) 是与 FAILED 同级的独立结论，**任何路径都不许把它记成 PASS**。
 */

/** 提示词版本；改动任一提示词时递增，使误报率只在同版本内可比。 */
export const PROMPT_VERSION = 'quiz-review-v1';

/** 单次盲解复核最多送入的题目数，与 engine.ts 的 1–5 题上限一致。 */
const MAX_REVIEW_QUESTIONS = 5;
/** 题干送入复核前的截断长度；被截断的题一律 UNVERIFIED，不拿半句话去算。 */
const MAX_STEM_CHARS = 800;
const MAX_LABEL_CHARS = 200;
/** 整条复核请求的用户消息上限；超限则丢弃尾部题目并标 UNVERIFIED，绝不中途截断单题。 */
const MAX_REVIEW_PROMPT_CHARS = 8000;

export type ReviewVerdict = 'PASS' | 'REGENERATED' | 'RISKY_THINK' | 'UNVERIFIED';
export type ReviewMethod = 'rule' | 'model' | 'none';

export interface QuestionReview {
  verdict: ReviewVerdict;
  method: ReviewMethod;
  reason: string;
}

export type IssueCode =
  | 'analysis-empty'
  | 'analysis-meta'
  | 'option-value-not-letter'
  | 'option-label-duplicate'
  | 'recheck-disagrees';

export interface QualityIssue {
  index: number;
  code: IssueCode;
  detail: string;
  /**
   * 是否值得花一次重生成去修。
   * option-value-not-letter / option-label-duplicate 是改写选项即可，repairable。
   * option-value-not-letter 尤其重要：ClassroomQuizAdapter.attach():48,83 用 option.value 建映射，
   * 值非法会在 Java 侧抛 422「答案引用了无效选项」，把整门课判为 error。
   */
  repairable: boolean;
}

export interface ReSolveEntry {
  /** 选择/判断题与 single 为单值；multiple 保留数组原形，交由 normalizeForCompare 归一化。 */
  answer: string | string[];
  confidence: string;
  reason: string;
}

/**
 * 模型思考过程泄漏的特征串。
 *
 * 刻意**不用**扁平短语表：`让我们`、`等等`、`重新` 都出现在完全正常的中文解析里
 * （「让我们先看分母」「等等，这里要先统一单位」）。因此全部写成多词共现的正则，
 * 且每次新增都要过 cleanNegatives 语料（见 test/fixtures/quiz-review-cases.json）确认零命中。
 */
const META_LEAK_PATTERNS: RegExp[] = [
  /让我(?:重新|再算|重新想)/,
  /我们(?:重新|再)算/,
  /重新(?:计算|算一|核对)(?:一下|一遍)?/,
  /我(?:刚才|刚|一开始)(?:算|想|看)错/,
  /修正(?:选项|答案|题目)/,
  /(?:选项|答案)(?:设置)?(?:需要|要)调整/,
  /或者修改(?:题目|选项|数据)/,
  /选项设置需要调整/,
  /(?:let me|let's|i need to) (?:re-?(?:compute|calculate|check)|reconsider)/i,
  /(?:wait|hold on)[，,]|actually,? (?:i|we)/i,
  /i made a(?:n)? (?:mistake|error)/i,
  /(?:fix|adjust|change) the (?:options|answer key)/i,
];

function normalizeText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

/** 把选项字母统一为「单个大写字母」，其余一律视为非法。 */
function isSingleLetter(value: unknown): boolean {
  return typeof value === 'string' && /^[A-Z]$/.test(value);
}

/**
 * L1：确定性规则，零模型调用。只报告问题，不抛异常、不修改输入。
 *
 * 命中即丢题（见 engine.ts 的接线），因此这里的每一条都必须保证零误报；
 * 拿不准的一律不做检查——宁可漏放，不可误杀正确题目。
 */
export function detectQualityIssues(questions: HostQuizQuestion[]): QualityIssue[] {
  const issues: QualityIssue[] = [];
  questions.forEach((question, index) => {
    const analysis = normalizeText(question.analysis);
    const choice = question.type === 'single' || question.type === 'multiple';
    // 简答的「评分要点」在 ClassroomQuizAdapter.attach() 里会被折进 explanation，
    // 因此有 gradingPoints 就等于有面向学生的说明，不算解析缺失。
    const hasExplanation = !!analysis || !!question.gradingPoints?.length;
    if (!hasExplanation) {
      issues.push({ index, code: 'analysis-empty', detail: '缺少面向学生的解析', repairable: true });
    }
    if (analysis && META_LEAK_PATTERNS.some(pattern => pattern.test(analysis))) {
      issues.push({ index, code: 'analysis-meta', detail: '解析包含模型思考过程或自我更正文字', repairable: true });
    }
    // 「解析断言的答案与标答矛盾」这一条**刻意不做**。
    // 解析里合法地提到干扰项字母极其常见（「常见错误：应选C」「B 是典型误答」），
    // 而 L1 命中即丢题，误报会直接删掉一道正确的题。在拿到真实语料校准误报率之前不放它上线；
    // 真正的答案正确性由 L2 盲解负责。若日后要加回，必须先过 cleanNegatives 语料。
    if (!choice) return;
    const labels = new Set<string>();
    const keys = new Set<string>();
    let badValue = false;
    let duplicateLabel = false;
    for (const option of question.options || []) {
      const label = normalizeText(option?.label);
      const value = normalizeText(option?.value);
      if (!isSingleLetter(value)) badValue = true;
      if (value) keys.add(value);
      if (label && labels.has(label)) duplicateLabel = true;
      if (label) labels.add(label);
    }
    if (badValue) {
      issues.push({ index, code: 'option-value-not-letter', detail: '选项键必须为单个大写字母 A–Z', repairable: true });
    }
    if (duplicateLabel) {
      issues.push({ index, code: 'option-label-duplicate', detail: '选项正文重复', repairable: true });
    }
  });
  return issues;
}

/**
 * 构造盲解复核提示词。
 *
 * **绝不传入 answer、analysis、gradingPoints**：上面那道错题的解析里写着
 * 「修正选项以匹配正确答案 480」，喂进去等于把错误锚点又递回去，会把唯一的检测手段
 * 变成批量盖章。question/options 之外的字段一律剔除。
 *
 * 返回 null 表示没有可复核的题目（此时调用方应全部记 UNVERIFIED）。
 */
export function buildReSolvePrompt(questions: HostQuizQuestion[]): { system: string; user: string; payload: Array<Record<string, unknown>>; reviewed: string[]; skipped: string[] } | null {
  const reviewed: string[] = [];
  const skipped: string[] = [];
  const payload: Array<Record<string, unknown>> = [];
  let budget = MAX_REVIEW_PROMPT_CHARS;
  for (const question of questions.slice(0, MAX_REVIEW_QUESTIONS)) {
    const stem = normalizeText(question.question);
    // 题干过长不做截断复核：拿半句话算出的结论不可信。
    if (stem.length > MAX_STEM_CHARS) { skipped.push(question.id); continue; }
    const entry: Record<string, unknown> = { id: question.id, type: question.type, question: stem };
    if (question.options?.length) {
      entry.options = question.options.map(option => ({ value: normalizeText(option.value), label: normalizeText(option.label).slice(0, MAX_LABEL_CHARS) }));
    }
    const size = JSON.stringify(entry).length;
    if (size > budget) { skipped.push(question.id); continue; }
    budget -= size;
    reviewed.push(question.id);
    payload.push(entry);
  }
  if (!payload.length) return null;
  // payload 单独返回，便于测试直接做结构性断言：送入复核的对象只允许有 id/type/question/options。
  return { system: RESOLVE_SYSTEM, user: RESOLVE_USER.replace('{{QUESTIONS}}', JSON.stringify(payload)), payload, reviewed, skipped };
}

const RESOLVE_SYSTEM = '你是独立解题裁判。只输出 JSON 数组，不要 markdown、不要解释、不要计算过程、不要复述题目。'
  + '你不参与出题，也不会看到出题方给出的答案。若题目信息不足、答案不唯一、或你无法确信，必须如实标注 low 或 NONE，不要猜测。';

const RESOLVE_USER = '独立求解下列题目，只给题干和选项。\n{{QUESTIONS}}\n\n'
  + '逐题输出：\n'
  + '- answer：single 填选项键如 "A"；multiple 填键数组如 ["A","C"]；judge 填 "对" 或 "错"；fill 填你算出的答案（≤80字，不要带单位之外的说明）。\n'
  + '- 若正确结果不在给出的选项中，或题目条件不足、有歧义，answer 填 "NONE"。\n'
  + '- confidence：确信结论唯一且正确才填 "high"，否则 "low"。\n'
  + '- reason：≤30字，说明依据。\n'
  + '只输出 JSON 数组，顺序与输入一致，不增删题目：\n'
  + '[{"id":"...","answer":"...","confidence":"high|low","reason":"..."}]';

/**
 * 解析盲解响应。任何失败都返回空 Map——调用方据此把相关题目记为 UNVERIFIED，
 * 而不是当成「复核通过」。这是本模块最重要的诚实性约束。
 */
export function parseReSolve(raw: string | null, questions: HostQuizQuestion[]): Map<string, ReSolveEntry> {
  const result = new Map<string, ReSolveEntry>();
  if (!raw) return result;
  const parsed = extractJsonArray(raw);
  if (!parsed) return result;
  const known = new Set(questions.map(question => question.id));
  for (const item of parsed) {
    if (!item || typeof item !== 'object') continue;
    const entry = item as Record<string, unknown>;
    const id = normalizeText(entry.id);
    if (!id || !known.has(id)) continue;
    const answer = Array.isArray(entry.answer)
      ? entry.answer.map(value => normalizeText(value)).filter(Boolean)
      : normalizeText(entry.answer);
    if (!answer || (Array.isArray(answer) && !answer.length)) continue;
    result.set(id, {
      answer,
      confidence: normalizeText(entry.confidence).toLowerCase(),
      reason: normalizeText(entry.reason).slice(0, 120),
    });
  }
  return result;
}

/** 从可能带代码块/前后缀说明的文本里取出第一个 JSON 数组。 */
function extractJsonArray(raw: string): unknown[] | null {
  const text = raw.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start < 0 || end <= start) return null;
  try {
    const value = JSON.parse(text.slice(start, end + 1));
    return Array.isArray(value) ? value : null;
  } catch { return null; }
}

/**
 * 把答案规范化成可比形式；返回 null 表示该题型不支持比对，必须记 UNVERIFIED。
 *
 * short_answer 恒返回 null：用文本比对参考答案会持续误报，并触发对正确题目的重生成。
 */
export function normalizeForCompare(type: string, answer: unknown): string | null {
  if (type === 'single') {
    // 库里选择题的标答是数组（["A"]），复核按契约回的是裸字母（"A"），两边都要认。
    const raw = Array.isArray(answer) ? (answer.length === 1 ? answer[0] : '') : answer;
    const value = normalizeText(raw).toUpperCase();
    return /^[A-Z]$/.test(value) ? value : null;
  }
  if (type === 'multiple') {
    const letters = (Array.isArray(answer) ? answer : [answer])
      .map(item => normalizeText(item).toUpperCase()).filter(Boolean);
    if (!letters.length || !letters.every(item => /^[A-Z]$/.test(item))) return null;
    return [...new Set(letters)].sort().join('');
  }
  if (type === 'judge') {
    if (typeof answer === 'boolean') return answer ? '对' : '错';
    const value = normalizeText(answer);
    return value === '对' || value === '错' ? value : null;
  }
  if (type === 'fill') {
    // 填空题标答可能是数值（24）而非文本；只有双方都能解析为数值时才比对。
    // 文本填空一律不比对，避免把「格式不同」当成「答案错」而误杀正确题。
    const raw = typeof answer === 'number' && Number.isFinite(answer) ? String(answer) : normalizeText(answer);
    if (!raw) return null;
    const numeric = toFiniteNumber(raw);
    return numeric === null ? null : String(numeric);
  }
  return null;
}

function toFiniteNumber(value: string): number | null {
  // 全角数字转半角后再试；带单位/文字的填空不参与比对。
  const half = value.replace(/[０-９．－]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xfee0));
  if (!/^-?\d+(?:\.\d+)?$/.test(half)) return null;
  const parsed = Number(half);
  return Number.isFinite(parsed) ? parsed : null;
}

export type ReSolveStatus = 'agree' | 'disagree' | 'unjudged';

export interface ReSolveOutcome {
  status: ReSolveStatus;
  reason: string;
  issue?: QualityIssue;
}

/**
 * 比对盲解结果与标答。
 *
 * 返回**显式三态**而不是「有分歧就返回问题、否则返回 null」：后者会让调用方无法区分
 * 「复核同意」与「复核根本没跑出结论」，极易把 UNVERIFIED 误记成 PASS——而计划里
 * 明令禁止任何路径把「没算出来」当成通过。只有 status==='agree' 才算通过。
 *
 * 低置信度、题型不支持比对（short_answer 恒不支持）、答案缺失，一律 unjudged。
 */
export function compareReSolve(question: HostQuizQuestion, entry: ReSolveEntry | undefined): ReSolveOutcome {
  if (!entry) return { status: 'unjudged', reason: '复核未给出该题的结论' };
  if (entry.confidence !== 'high') return { status: 'unjudged', reason: `复核置信度为 ${entry.confidence || '未知'}，不足以判定` };
  // issue 的 index 由调用方按其所在位置回填；此处只负责判定内容。
  const index = -1;
  const declared = Array.isArray(entry.answer) ? '' : entry.answer.toUpperCase();
  if (declared === 'NONE') {
    return { status: 'disagree', reason: `独立求解认为正确结果不在给出的选项中：${entry.reason}`,
      issue: { index, code: 'recheck-disagrees', detail: `独立求解认为正确结果不在给出的选项中：${entry.reason}`, repairable: true } };
  }
  const modelAnswer = normalizeForCompare(question.type, entry.answer);
  const key = normalizeForCompare(question.type, question.answer);
  if (modelAnswer === null || key === null) {
    // short_answer 恒落在这里：参考答案无法用文本比对，必须如实标为「无法判定」。
    return { status: 'unjudged', reason: `题型 ${question.type} 不支持自动比对` };
  }
  if (modelAnswer === key) return { status: 'agree', reason: '独立求解与标答一致' };
  return { status: 'disagree', reason: `独立求解得到 ${modelAnswer}，标答为 ${key}`,
    issue: { index, code: 'recheck-disagrees', detail: `独立求解得到 ${modelAnswer}，标答为 ${key}：${entry.reason}`, repairable: true } };
}

/**
 * 给「重试仍失败」的题目生成降级用的思考题：**只有题干**。
 *
 * 刻意不带任何原因说明。recheck-disagrees 的 detail 里含模型算出的答案
 * （「独立求解得到 480」），那本身就是答案——写进来等于给正在思考这道题的学生泄题。
 * 详细原因只进 review 证据字段，而证据字段在 publicCourse() 与导出时都会被剥掉。
 */
export function toThinkingItem(question: HostQuizQuestion): Record<string, unknown> {
  return { id: question.id, question: question.question };
}

/**
 * 构造单题重生成提示词。
 *
 * 注意第 2 条的要求方向：**让正确结果出现在选项里**（必要时改写选项或调整题干数据），
 * 这正是上面那道错题做不到的——它在「改选项」和「改数据」之间反复摇摆，最后两个都没做。
 * 这里刻意不给它「只改标答」的选项。
 */
export function buildRegeneratePrompt(question: HostQuizQuestion, issue: QualityIssue): { system: string; user: string } {
  const original = { type: question.type, question: question.question, options: question.options, answer: question.answer, analysis: question.analysis };
  const user = `下面这道题在独立复核中发现问题：${issue.code} — ${issue.detail}\n`
    + `原题：${JSON.stringify(original)}\n\n`
    + '要求：\n'
    + '1. 自己重新独立推导一遍，得出正确答案；\n'
    + '2. 让正确结果出现在选项中——必要时改写选项或调整题干数据，并让 answer 指向它；\n'
    + '3. analysis 必须是面向学生的一段干净最终解析：先结论后理由，不含自言自语、不含「等等/让我重新算/修正选项」这类字样；\n'
    + '4. 题型不变；single 只允许 1 个答案；选项 2–8 个，value 为单个大写字母 A–Z，label 互不相同；\n'
    + '5. 只输出这一个题目的 JSON 对象。';
  return { system: '你是晓陶学伴的出题老师，只输出一个 JSON 题目对象，不输出代码块、不输出讲解动作。', user };
}
