import { useEffect, useState } from "react";
import "./lawrag.css";

import { API_BASE_URL as BASE_URL } from '../api/baseUrl';

const LIBS = [
  { id: "civil", label: "民法典", hint: "合同/侵权/婚姻/继承等" },
  { id: "labor", label: "劳动法", hint: "劳动合同/工伤/社保/劳动争议等" },
  { id: "criminal", label: "刑法", hint: "犯罪构成/刑罚/常见罪名等" },
];

/** 与 App.jsx 一致：localStorage 持久化的桌面端用户 ID（会话隔离用） */
function getDesktopUserId() {
  let uid = localStorage.getItem("desktopUserId");
  if (!uid) {
    uid = "user_" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem("desktopUserId", uid);
  }
  return uid;
}

function libName(id) {
  return LIBS.find((l) => l.id === id)?.label || id || "—";
}

/** distance = 1 - similarity，越小越相关 → 换算成相似度百分比 */
function fmtDist(d) {
  const n = Number(d);
  if (Number.isNaN(n)) return "—";
  return Math.round(Math.max(0, Math.min(1, 1 - n)) * 100) + "%";
}

/**
 * ⚖️ 法律检索 — 独立页面。
 * 后端 LawRagController 代理到 law-rag-module Python RAG 服务（ChromaDB + 通义千问）。
 */
export default function LawRagView() {
  const uid = getDesktopUserId();
  const [libId, setLibId] = useState("civil");
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [result, setResult] = useState(null); // { answer, articles, lib_used }
  const [health, setHealth] = useState(null); // { online, indexing, text }

  const checkHealth = async () => {
    try {
      const res = await fetch(`${BASE_URL}/api/desktop/law/health`);
      const json = await res.json();
      if (json?.code === 200) {
        const d = json.data || {};
        if (d.indexing && d.indexing !== "DONE") {
          setHealth({
            online: true,
            indexing: true,
            text: `法律知识库向量化入库中（${d.indexing === "INDEXING" ? "进行中，约需数分钟" : "等待处理"}）...`,
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
  }, []);

  const doSearch = async () => {
    if (!question.trim()) {
      setMsg("请输入法律问题");
      return;
    }
    setBusy(true);
    setMsg("⚖️ 检索中…");
    setResult(null);
    try {
      const res = await fetch(`${BASE_URL}/api/desktop/law/search`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ uid, question: question.trim(), lib_id: libId }),
      });
      const json = await res.json();
      if (json?.code === 200 && json.data) {
        setResult(json.data);
        setMsg("✅ 检索完成");
      } else {
        setMsg(json?.msg || "检索失败");
      }
    } catch (e) {
      setMsg(`❌ 检索失败：${e}`);
    }
    setBusy(false);
  };

  const clearContext = async () => {
    setMsg("🗑️ 清除中…");
    try {
      const res = await fetch(`${BASE_URL}/api/desktop/law/clear-context`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ uid }),
      });
      const json = await res.json();
      setMsg(json?.msg || "已清除");
    } catch (e) {
      setMsg(`清除失败：${e}`);
    }
  };

  return (
    <div className="quiz-container">
      <div className="mg-panel law-panel tool-card">
      <div className="mg-header">
        <h2>⚖️ 法律检索</h2>
        <span className="mg-sub">法条向量检索 + AI 解答（法律 RAG）</span>
      </div>

      <div className={`law-status${health ? (health.indexing ? " indexing" : health.online ? " online" : " offline") : ""}`}>
        {health ? `● ${health.text}` : "⏳ 检测服务状态…"}
        <button className="law-refresh" onClick={checkHealth} title="重新检测">↻</button>
      </div>

      <div className="law-libs">
        {LIBS.map((l) => (
          <button
            key={l.id}
            className={`law-lib${libId === l.id ? " active" : ""}`}
            onClick={() => setLibId(l.id)}
            title={l.hint}
          >
            {l.label}
          </button>
        ))}
      </div>

      <textarea
        className="law-question"
        rows={3}
        placeholder="输入法律问题，例如：劳动合同法第39条规定是什么？"
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
      />

      <div className="mg-actions">
        <button className="btn send-btn" disabled={busy} onClick={doSearch}>
          {busy ? "检索中…" : "🔍 检索"}
        </button>
        <button className="btn tool-btn" disabled={busy} onClick={clearContext}>
          🗑️ 清除上下文
        </button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {result && (
        <div className="law-result">
          <div className="law-answer">
            <div className="law-answer-title">
              💡 AI 解答{result.lib_used ? `（文库：${libName(result.lib_used)}）` : ""}
            </div>
            <div className="law-answer-body">{result.answer}</div>
          </div>

          {Array.isArray(result.articles) && result.articles.length > 0 && (
            <div className="law-articles">
              <div className="law-articles-title">📚 检索到的相关法条（{result.articles.length} 条）</div>
              {result.articles.map((a, i) => (
                <div className="law-article" key={i}>
                  <div className="law-article-meta">相关度 {fmtDist(a.distance)} · {a.source || "—"}</div>
                  <div className="law-article-content">{a.content}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
      </div>
    </div>
  );
}
