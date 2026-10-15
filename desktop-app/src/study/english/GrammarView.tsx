import { useState } from "react";
import { maimemoGrammarQuiz } from "../../request";
import { renderMarkdown } from "../../chat/markdown";

type GQ = { question?: string; options?: string[]; answerIndex?: number; explanation?: string };
type QuizRes = { code?: number; rules?: string; questions?: GQ[]; msg?: string };

/** 语法点分类（时态 / 从句 / 虚拟语气 / 倒装 / 非谓语 / 其他） */
const CATS: [string, string[]][] = [
  ["时态", ["一般现在时", "一般过去时", "现在完成时", "过去完成时", "现在进行时", "将来时"]],
  ["从句", ["定语从句", "名词性从句", "状语从句"]],
  ["虚拟语气", ["虚拟条件句", "wish / if only", "建议类虚拟语气"]],
  ["倒装", ["完全倒装", "部分倒装"]],
  ["非谓语动词", ["不定式", "动名词", "分词"]],
  ["其他", ["主谓一致", "被动语态", "冠词与代词"]],
];

/**
 * 📝 语法专项 — 全宽布局，左右两个卡片：
 *  左「语法讲解」：LLM 生成的规则讲解（Markdown）
 *  右「题目生成」：5 道单选题，逐题作答 + 解析
 */
export default function GrammarView() {
  const [topic, setTopic] = useState("");
  const [loading, setLoading] = useState(false);
  const [rules, setRules] = useState("");
  const [questions, setQuestions] = useState<GQ[]>([]);
  const [idx, setIdx] = useState(0);
  const [picked, setPicked] = useState<number | null>(null);
  const [score, setScore] = useState(0);
  const [done, setDone] = useState(false);
  const [msg, setMsg] = useState("");

  const load = async (t: string) => {
    setTopic(t);
    setLoading(true);
    setMsg("");
    setRules("");
    setQuestions([]);
    setIdx(0);
    setPicked(null);
    setScore(0);
    setDone(false);
    try {
      const res: QuizRes = await maimemoGrammarQuiz(t);
      if (res?.code === 200) {
        setRules(res.rules || "");
        setQuestions(res.questions || []);
        if (!res.questions?.length) setMsg("本轮未生成题目，请重试");
      } else {
        setMsg(res?.msg || "生成失败");
      }
    } catch (e) {
      console.error("[Grammar] 生成失败:", e);
      setMsg("请求失败，请检查后端服务");
    } finally {
      setLoading(false);
    }
  };

  const answer = (i: number) => {
    if (picked !== null) return;
    setPicked(i);
    if (i === questions[idx]?.answerIndex) setScore((s) => s + 1);
  };

  const next = () => {
    if (idx + 1 >= questions.length) setDone(true);
    else {
      setIdx(idx + 1);
      setPicked(null);
    }
  };

  const q = questions[idx];

  return (
    <div className="gm-wrap">
      <div className="gm-header">
        <h2>📝 语法专项</h2>
        <span className="gm-sub">按语法点分类 · 规则讲解 + 练习解析</span>
      </div>

      {/* 语法点分类（全宽） */}
      <div className="gm-topics">
        {CATS.map(([cat, items]) => (
          <div key={cat} className="gm-cat">
            <div className="gm-cat-name">{cat}</div>
            <div className="gm-cat-items">
              {items.map((t) => (
                <button
                  key={t}
                  className={`gm-topic${topic === t ? " active" : ""}`}
                  onClick={() => load(t)}
                  disabled={loading}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>

      {msg && <div className="mg-msg">{msg}</div>}
      {loading && <div className="mg-loading">✨ 生成语法讲解与练习中…</div>}

      {/* 左右两张卡片：语法讲解 / 题目生成 */}
      <div className="gm-cards">
        {/* 语法讲解卡片 */}
        <div className="gm-card">
          <div className="gm-card-title">📖 语法讲解</div>
          {!loading && rules ? (
            <div className="md-render" dangerouslySetInnerHTML={{ __html: renderMarkdown(rules) }} />
          ) : (
            <div className="gm-card-empty">选择一个语法点，生成规则讲解</div>
          )}
        </div>

        {/* 题目生成卡片 */}
        <div className="gm-card">
          <div className="gm-card-title">✏️ 题目生成</div>
          {!loading && questions.length > 0 ? (
            <div className="gm-quiz">
              <div className="gm-quiz-progress">
                第 {Math.min(idx + 1, questions.length)} / {questions.length} 题 · 答对 {score}
              </div>

              {!done && q ? (
                <>
                  <div className="gm-question">{q.question}</div>
                  <div className="rv-actions rv-mcq-actions">
                    {(q.options || []).map((opt, i) => {
                      let cls = "btn tool-btn rv-mcq-opt";
                      if (picked !== null) {
                        if (i === q.answerIndex) cls += " gm-opt-correct";
                        else if (i === picked) cls += " gm-opt-wrong";
                      }
                      return (
                        <button key={i} className={cls} onClick={() => answer(i)} disabled={picked !== null}>
                          {opt}
                        </button>
                      );
                    })}
                  </div>
                  {picked !== null && (
                    <div className={`rv-feedback ${picked === q.answerIndex ? "rv-fb-ok" : "rv-fb-no"}`}>
                      <span>
                        {picked === q.answerIndex ? "✅ 回答正确！" : `❌ 正确答案：${q.options?.[q.answerIndex ?? -1] ?? ""}`}
                      </span>
                      {q.explanation && <span className="gm-explanation">解析：{q.explanation}</span>}
                      <button className="btn tool-btn" onClick={next}>
                        {idx + 1 >= questions.length ? "查看结果" : "下一题 →"}
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <div className="rv-summary">
                  <div className="rv-summary-title">练习完成！</div>
                  <div className="rv-summary-row">
                    答对 <b>{score}</b> / {questions.length}
                  </div>
                  <div className="rv-summary-actions">
                    <button className="btn send-btn" onClick={() => load(topic)}>
                      🔄 再来一组
                    </button>
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="gm-card-empty">选择语法点后自动生成 5 道练习</div>
          )}
        </div>
      </div>
    </div>
  );
}
