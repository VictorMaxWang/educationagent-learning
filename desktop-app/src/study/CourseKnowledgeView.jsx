import { useEffect, useState } from "react";
import "../tools/lawrag.css";
import "../edu/edu.css";

import { API_BASE_URL as BASE_URL } from "../api/baseUrl";

/** 课程知识库配置：domainId 对应后端 DocKnowledgeConfig 的领域 */
const COURSES = {
  tongshi: {
    title: "通识课程",
    subtitle: "通识教育知识库问答（政治 · 英语 · 数学）",
    libs: [
      { id: "politics", label: "政治", placeholder: "输入问题，例如：党的二十大报告的核心内容是什么？" },
      { id: "english", label: "英语", placeholder: "输入问题，例如：请解析这个长难句的语法结构。" },
      { id: "math", label: "数学", placeholder: "输入问题，例如：请讲解这道高数题的解题步骤。" },
    ],
    placeholder: "输入问题，例如：党的二十大报告的核心内容是什么？",
  },
  psychology: {
    title: "心理学课程",
    subtitle: "心理学知识库问答",
    libs: [{ id: "general", label: "心理学" }],
    placeholder: "输入心理学问题，例如：什么是认知失调？",
  },
  lifesafety: {
    title: "生命安全与健康教育",
    subtitle: "生命与死亡认知 · 哀伤陪伴 · 面向中小学生（依据教育部《生命安全与健康教育进中小学课程教材指南》）",
    ageNotice:
      "本页面向 12 岁以上学生，用于生命教育科普，不能替代心理辅导或医疗建议。" +
      "如果你正经历难以承受的痛苦，请告诉可信任的成年人（父母 / 老师 / 学校心理老师），" +
      "或拨打全国心理援助热线 12356（24 小时免费）、共青团青少年服务热线 12355。",
    libs: [
      { id: "life", label: "生命与死亡认知", placeholder: "输入问题，例如：人为什么会死？" },
      { id: "support", label: "哀伤陪伴", placeholder: "输入问题，例如：亲人去世后一直很难过，可以怎么办？" },
    ],
    placeholder: "输入问题，例如：人为什么会死？",
  },
  sexedu: {
    title: "青春期性教育",
    subtitle: "青春期身心变化 · 人际与身体边界 · 自我保护 · 面向中小学生",
    ageNotice:
      "本页面向 12 岁以上学生，用于青春期健康教育科普，不构成医疗建议。" +
      "身体不舒服请及时就医；如果有人碰你让你觉得不舒服，请立刻告诉可信任的成年人，" +
      "必要时拨打 110。这不是你的错。",
    libs: [
      { id: "puberty", label: "青春期身心变化", placeholder: "输入问题，例如：青春期身体发生变化是正常的吗？" },
      { id: "relationship", label: "人际与身体边界", placeholder: "输入问题，例如：怎么对让我不舒服的要求说不？" },
      { id: "protection", label: "自我保护与求助", placeholder: "输入问题，例如：遇到让我不舒服的触碰该怎么办？" },
    ],
    placeholder: "输入问题，例如：青春期身体发生变化是正常的吗？",
  },
};

/** 与 App.jsx 一致：localStorage 持久化的桌面端用户 ID（会话隔离用） */
function getDesktopUserId() {
  let uid = localStorage.getItem("desktopUserId");
  if (!uid) {
    uid = "user_" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem("desktopUserId", uid);
  }
  return uid;
}

function libName(libs, id) {
  return libs.find((l) => l.id === id)?.label || id || "—";
}

/** 历史记录：按领域 + 分类隔离存储 */
function historyKey(domainId, libId) {
  return `course_history_${domainId}_${libId}`;
}

function loadHistory(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || "[]");
  } catch {
    return [];
  }
}

function saveHistory(key, items) {
  try {
    localStorage.setItem(key, JSON.stringify(items));
  } catch {}
}

function formatTime(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  const now = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (d.toDateString() === now.toDateString()) return `今天 ${hm}`;
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`;
}

/**
 * 课程知识库问答视图（通识教育 / 心理学）。
 * 后端 BaseDocKnowledgeController 提供 /health /search /clear-context 接口，
 * 返回结构与法律检索（LawRagView）一致，复用 lawrag.css 样式。
 * 每个标签（子库）维护独立会话 id 与独立历史记录。
 */
export default function CourseKnowledgeView({ domainId, learnerId }) {
  const cfg = COURSES[domainId] || COURSES.tongshi;
  const [baseUid] = useState(() => learnerId || getDesktopUserId());
  const [libId, setLibId] = useState(cfg.libs[0]?.id || "");
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [result, setResult] = useState(null); // { answer, articles, lib_used }
  const [health, setHealth] = useState(null); // { online, indexing, text }
  const [history, setHistory] = useState([]); // 当前标签的历史对话
  // 适龄与安全提示：只在教育域（生命安全与健康教育/青春期性教育）配置了 ageNotice 时出现，
  // 确认一次后按领域记 localStorage，不再打扰。
  const [noticeAck, setNoticeAck] = useState(
    () => localStorage.getItem(`edu_age_notice_${domainId}`) === "1"
  );

  const currentLib = cfg.libs.find((l) => l.id === libId);
  const placeholder = currentLib?.placeholder || cfg.placeholder;
  // 每个标签独立会话 id：基础用户 id + 分类标识，后端据此隔离上下文
  const sessionUid = `${baseUid}_${libId}`;
  const hKey = historyKey(domainId, libId);

  const checkHealth = async () => {
    try {
      const res = await fetch(`${BASE_URL}/api/desktop/${domainId}/health`);
      const json = await res.json();
      if (json?.code === 200) {
        const d = json.data || {};
        if (d.indexing && d.indexing !== "DONE") {
          setHealth({
            online: true,
            indexing: true,
            text: `${cfg.title}知识库向量化入库中（${d.indexing === "INDEXING" ? "进行中，约需数分钟" : "等待处理"}）...`,
          });
        } else {
          setHealth({
            online: true,
            indexing: false,
            text: `服务在线（活跃会话 ${d.active_sessions ?? 0} 个）`,
          });
        }
      } else {
        setHealth({ online: false, indexing: false, text: json?.msg || "服务未启动" });
      }
    } catch (e) {
      setHealth({ online: false, indexing: false, text: "后端未响应，请确认主工程已启动" });
    }
  };

  useEffect(() => {
    checkHealth();
    // 每 8 秒自动重检：后端重启 / 入库完成后状态自愈
    const timer = setInterval(checkHealth, 8000);
    return () => clearInterval(timer);
  }, [domainId]);

  // 切换标签时：清空输入框与结果，加载该标签自己的历史记录
  useEffect(() => {
    setQuestion("");
    setResult(null);
    setMsg("");
    setHistory(loadHistory(hKey));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [libId]);

  const doSearch = async () => {
    const q = question.trim();
    if (!q) {
      setMsg("请输入问题");
      return;
    }
    setBusy(true);
    setMsg("检索中…");
    setResult(null);
    try {
      const res = await fetch(`${BASE_URL}/api/desktop/${domainId}/search`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ uid: sessionUid, question: q, lib_id: libId }),
      });
      const json = await res.json();
      if (json?.code === 200 && json.data) {
        setResult(json.data);
        // 写入当前标签的历史记录（最新在前，最多 50 条）
        const item = {
          question: q,
          answer: json.data.answer,
          articles: json.data.articles || [],
          lib_used: json.data.lib_used || libId,
          libId,
          time: Date.now(),
        };
        setHistory((prev) => {
          const next = [item, ...prev].slice(0, 50);
          saveHistory(hKey, next);
          return next;
        });
        setMsg("检索完成");
      } else {
        setMsg(json.data?.error || json?.msg || "检索失败");
      }
    } catch (e) {
      setMsg(`检索失败：${e}`);
    }
    setBusy(false);
  };

  const clearContext = async () => {
    setMsg("清除中…");
    try {
      const res = await fetch(`${BASE_URL}/api/desktop/${domainId}/clear-context`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ uid: sessionUid }),
      });
      const json = await res.json();
      setMsg(json?.msg || "已清除");
      // 同步删除当前标签的历史记录
      setHistory([]);
      saveHistory(hKey, []);
    } catch (e) {
      setMsg(`清除失败：${e}`);
    }
  };

  const loadHistoryItem = (h) => {
    setResult({ answer: h.answer, articles: h.articles || [], lib_used: h.lib_used });
    setQuestion(h.question);
  };

  return (
    <div className="quiz-container">
      <div className="mg-panel law-panel tool-card">
      <div className="mg-header">
        <h2>{cfg.title}</h2>
        <span className="mg-sub">{cfg.subtitle}</span>
      </div>

      <div className={`law-status${health ? (health.indexing ? " indexing" : health.online ? " online" : " offline") : ""}`}>
        {health ? `● ${health.text}` : "检测服务状态…"}
        <button className="law-refresh" onClick={checkHealth} title="重新检测">↻</button>
      </div>

      {cfg.ageNotice && !noticeAck && (
        <div className="edu-age-notice">
          <div className="edu-age-notice-title">适龄与安全提示</div>
          <div>{cfg.ageNotice}</div>
          <button
            className="btn tool-btn"
            onClick={() => {
              localStorage.setItem(`edu_age_notice_${domainId}`, "1");
              setNoticeAck(true);
            }}
          >
            我已阅读并知晓
          </button>
        </div>
      )}

      {cfg.libs.length > 1 && (
        <div className="law-libs">
          {cfg.libs.map((l) => (
            <button
              key={l.id}
              className={`law-lib${libId === l.id ? " active" : ""}`}
              onClick={() => setLibId(l.id)}
            >
              {l.label}
            </button>
          ))}
        </div>
      )}

      <textarea
        className="law-question"
        rows={3}
        placeholder={placeholder}
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
      />

      <div className="mg-actions">
        <button className="btn send-btn" disabled={busy} onClick={doSearch}>
          {busy ? "检索中…" : "检索"}
        </button>
        <button className="btn tool-btn" disabled={busy} onClick={clearContext}>
          清除上下文
        </button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {result && result.crisis && (
        <div className="law-result">
          <div className="edu-crisis-banner">
            <div className="edu-crisis-title">🤝 先照顾好自己</div>
            <div className="edu-crisis-body">{result.answer}</div>
          </div>
        </div>
      )}

      {result && !result.crisis && (
        <div className="law-result">
          <div className="law-answer">
            <div className="law-answer-title">
              AI 解答{result.lib_used ? `（文库：${libName(cfg.libs, result.lib_used)}）` : ""}
            </div>
            <div className="law-answer-body">{result.answer}</div>
          </div>

          {/* 检索片段列表已按需求隐藏：答案区只保留最终回答。
              后端的 articles（文件名 / 片段序号 / 检索距离）仍然照常返回并存进历史，
              所以「回答有据可依」这个能力没丢——需要时把这块渲染放回来即可。 */}
        </div>
      )}

      {history.length > 0 && (
        <div className="law-articles">
          <div className="law-articles-title">历史对话（{history.length}）</div>
          {history.map((h, i) => (
            <div
              className="law-article law-history-item"
              key={`${h.time}-${i}`}
              onClick={() => loadHistoryItem(h)}
            >
              <div className="law-article-meta">{formatTime(h.time)}</div>
              <div className="law-article-content">{h.question}</div>
            </div>
          ))}
        </div>
      )}
      </div>
    </div>
  );
}
