import { fetchRequest, fetchStreamChat } from "./request";

// 获取会话列表
export function getConversationList() {
  return fetchRequest("/api/desktop/conversations");
}

// 发起流式对话（对接 /api/desktop/chat/stream NDJSON流）
export function sendStreamChat(params, onMessage) {
  return fetchStreamChat("/api/desktop/chat/stream", params, onMessage);
}

// 上传文件/图片（base64），返回 { fileId }
export function uploadFile(payload) {
  return fetchRequest("/api/desktop/file/upload", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 格式转换（学习栏目）：fileId + targetFormat → { success, fileName, size, base64 }
export function convertFile(payload) {
  return fetchRequest("/api/desktop/fileconvert/convert", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 听力材料语音合成：quizId + passageNo → { success, audio(base64 mp3), mime }
export function passageAudio(payload) {
  return fetchRequest("/api/desktop/quiz/passage-audio", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 删除试卷（级联删题目/材料/做题记录）
export function deleteQuizPaper(payload) {
  return fetchRequest("/api/desktop/quiz/paper-delete", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// ===== 知识中心 =====
// 保存并索引进个人知识库（fileId 来自上传接口）
export function saveKnowledge(payload) {
  return fetchRequest("/api/desktop/knowledge/save", { method: "POST", body: JSON.stringify(payload) });
}
// 知识库文档列表（文件名 + 大小 + 时间）
export function listKnowledge(payload) {
  return fetchRequest("/api/desktop/knowledge/list", { method: "POST", body: JSON.stringify(payload) });
}
// 查看某文档内容
export function viewKnowledge(payload) {
  return fetchRequest("/api/desktop/knowledge/view", { method: "POST", body: JSON.stringify(payload) });
}
// 删除文档（含向量）
export function deleteKnowledge(payload) {
  return fetchRequest("/api/desktop/knowledge/delete", { method: "POST", body: JSON.stringify(payload) });
}
// 资料检索
export function searchKnowledge(payload) {
  return fetchRequest("/api/desktop/knowledge/search", { method: "POST", body: JSON.stringify(payload) });
}
// 知识库问答（RAG + LLM）
export function askKnowledge(payload) {
  return fetchRequest("/api/desktop/knowledge/ask", { method: "POST", body: JSON.stringify(payload) });
}

// 刷题：把上传的文件解析成试卷，返回 { quizId, title, questionCount, questions }
export function generateQuiz(payload) {
  return fetchRequest("/api/desktop/quiz/generate", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 我的试卷列表
export function getQuizPapers(payload) {
  return fetchRequest("/api/desktop/quiz/papers", { method: "POST", body: JSON.stringify(payload) });
}

// 加载已有试卷（一键再刷），返回与 generate 相同结构
export function loadQuizPaper(payload) {
  return fetchRequest("/api/desktop/quiz/paper", { method: "POST", body: JSON.stringify(payload) });
}

// 刷题：提交答案判分，返回 { quizId, score, total, results }
export function submitQuiz(payload) {
  return fetchRequest("/api/desktop/quiz/submit", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 刷题：单题即时判（有答案的题返回对错；无答案返回 auto:false）
export function gradeOneQuiz(payload) {
  return fetchRequest("/api/desktop/quiz/grade-one", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 学习模式：错题本
export function getMistakes(payload) {
  return fetchRequest("/api/desktop/quiz/mistakes", { method: "POST", body: JSON.stringify(payload) });
}
export function redoMistake(payload) {
  return fetchRequest("/api/desktop/quiz/mistake-redo", { method: "POST", body: JSON.stringify(payload) });
}
export function removeMistake(payload) {
  return fetchRequest("/api/desktop/quiz/mistake-remove", { method: "POST", body: JSON.stringify(payload) });
}

// 学习模式：知识点
export function getKnowledge(payload) {
  return fetchRequest("/api/desktop/quiz/knowledge", { method: "POST", body: JSON.stringify(payload) });
}
export function toggleKnowledgeFav(payload) {
  return fetchRequest("/api/desktop/quiz/knowledge-toggle", { method: "POST", body: JSON.stringify(payload) });
}
export function getKnowledgeQuestions(payload) {
  return fetchRequest("/api/desktop/quiz/knowledge-questions", { method: "POST", body: JSON.stringify(payload) });
}

// 学习模式：题目收藏
export function toggleQuestionFav(payload) {
  return fetchRequest("/api/desktop/quiz/question-fav-toggle", { method: "POST", body: JSON.stringify(payload) });
}
export function getFavQuestions(payload) {
  return fetchRequest("/api/desktop/quiz/fav-questions", { method: "POST", body: JSON.stringify(payload) });
}