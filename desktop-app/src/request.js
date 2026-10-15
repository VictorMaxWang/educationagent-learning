// 英语学习（墨墨背单词）API 层
import { API_BASE_URL as BASE_URL } from './api/baseUrl';

async function maimemoRequest(url, options = {}) {
  const resp = await fetch(`${BASE_URL}${url}`, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

// ===== 词库 =====
// AI 按考纲生成词库
export const maimemoGenerate = (p) =>
  maimemoRequest("/api/maimemo/generate", { method: "POST", body: JSON.stringify(p) });
// 词库列表（支持 bookType/status/notebook 过滤）
export const maimemoGetWords = (params = {}) => {
  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ""),
  ).toString();
  return maimemoRequest(`/api/maimemo/words${qs ? `?${qs}` : ""}`);
};
// 文本导入词库（每行一个词，或 word|释义|音标）
export const maimemoImport = (bookType, content) =>
  maimemoRequest("/api/maimemo/import", { method: "POST", body: JSON.stringify({ bookType, content }) });
// 加入/移出生词本
export const maimemoToggleNotebook = (word, inNotebook) =>
  maimemoRequest("/api/maimemo/notebook", { method: "POST", body: JSON.stringify({ word, inNotebook }) });
// 更新复习状态
export const maimemoUpdateWordStatus = (id, status) =>
  maimemoRequest("/api/maimemo/update-status", { method: "POST", body: JSON.stringify({ id, status }) });
// 批量加入词库（可同步墨墨）
export const maimemoAddWords = (words, syncToMaimemo, source) =>
  maimemoRequest("/api/maimemo/add-words", { method: "POST", body: JSON.stringify({ words, syncToMaimemo, source }) });
// 今日待复习（focus=weak 只刷难词）
export const maimemoReviewToday = (focus) =>
  maimemoRequest("/api/maimemo/review-today", { method: "POST", body: JSON.stringify({ focus }) });
// 复习判分（艾宾浩斯：correct=答对，revisit=模糊明天再刷）
export const maimemoReviewAnswer = (wordId, correct, revisit) =>
  maimemoRequest("/api/maimemo/review-answer", { method: "POST", body: JSON.stringify({ wordId, correct, revisit }) });
// 复习统计（遗忘曲线排期）
export const maimemoReviewStats = () => maimemoRequest("/api/maimemo/review-stats");

// ===== 查词 =====
export const maimemoLookupWord = (word) =>
  maimemoRequest("/api/maimemo/lookup-word", { method: "POST", body: JSON.stringify({ word }) });
export const maimemoLookupText = (text, limit) =>
  maimemoRequest("/api/maimemo/lookup-text", { method: "POST", body: JSON.stringify({ text, limit }) });

// ===== 语法专项 =====
// 按语法点生成规则讲解 + 练习（含答案解析）
export const maimemoGrammarQuiz = (topic) =>
  maimemoRequest("/api/maimemo/grammar-quiz", { method: "POST", body: JSON.stringify({ topic }) });

// ===== 听力 =====
export const maimemoListenDictation = (p) =>
  maimemoRequest("/api/maimemo/listen-dictation", { method: "POST", body: JSON.stringify(p) });
export const maimemoListenPassage = (p) =>
  maimemoRequest("/api/maimemo/listen-passage", { method: "POST", body: JSON.stringify(p) });
// 音频相对路径 → 绝对 URL
export const maimemoAudioUrl = (path) => (path ? `${BASE_URL}${path}` : "");

// ===== 口语练习（讯飞 ISE 评测） =====
// 预设练习文本
export const maimemoSpeechPreset = () => maimemoRequest("/api/maimemo/speech-preset");
// 文本 + 录音(16kHz PCM base64) → 讯飞评测 + LLM 反馈
// 情景对话剧本（用户扮演 B 角色逐句跟读；length: short/standard/long）
export const maimemoSpeechDialogue = (scenario, length) =>
  maimemoRequest("/api/maimemo/speech-dialogue", { method: "POST", body: JSON.stringify({ scenario, length }) });
export const maimemoSpeechEvaluate = (p) =>
  maimemoRequest("/api/maimemo/speech-evaluate", { method: "POST", body: JSON.stringify(p) });

// ===== 墨墨同步 / 配置 =====
export const maimemoSyncWords = (bookType, words) =>
  maimemoRequest("/api/maimemo/sync", { method: "POST", body: JSON.stringify({ bookType, words }) });
export const maimemoGetConfig = () => maimemoRequest("/api/maimemo/config");
