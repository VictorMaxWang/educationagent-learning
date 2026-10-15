import { useCallback, useEffect, useState } from "react";
import "../ops/ops.css";
import "./edu.css";

/**
 * 学生端「班级作业」。
 *
 * <h3>两条边界（都靠后端保证，前端只是跟着显示）</h3>
 * <ul>
 *   <li><b>只看得到本班的题</b>：请求里只有 userId，班级由服务端解析；
 *       列表接口**不返回标准答案**——所以这里答题前拿不到答案，交卷后才有；</li>
 *   <li><b>重做是覆盖</b>：同一题再交一次会覆盖上次记录（避免反复重做把班级正确率刷歪），
 *       所以页面允许重复提交并刷新结果显示。</li>
 * </ul>
 */
import { API_BASE_URL as BASE_URL } from "../api/baseUrl";

/** 与 CourseKnowledgeView / StudentReportView 同款：localStorage 持久化的桌面端用户 ID */
function getDesktopUserId() {
  let uid = localStorage.getItem("desktopUserId");
  if (!uid) {
    uid = "user_" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem("desktopUserId", uid);
  }
  return uid;
}

/** quiz_question.options 是 JSON 数组字符串；容错成 "A|B|C" 这类写法。 */
function parseOptions(raw, qType) {
  const s = (raw || "").trim();
  if (!s) return qType === "JUDGE" ? ["A. 对", "B. 错"] : [];
  if (s.startsWith("[")) {
    try {
      const arr = JSON.parse(s);
      if (Array.isArray(arr) && arr.length > 0) return arr.map((x) => String(x));
    } catch {
      // 落到下面的竖线分支
    }
  }
  return s.split(/[|｜]/).map((x) => x.trim()).filter(Boolean);
}

/** 从 "A. xxx" 里取选项字母；取不到就用序号。 */
function letterOf(text, index) {
  const m = /^\s*([A-Za-z])[.、:：)）\s]/.exec(text);
  return m ? m[1].toUpperCase() : String.fromCharCode(65 + index);
}

export default function StudentHomeworkView() {
  const [userId] = useState(getDesktopUserId);
  const [data, setData] = useState(null);
  const [msg, setMsg] = useState("");
  const [answers, setAnswers] = useState({});   // questionId -> "A" / "AB"
  const [results, setResults] = useState({});   // questionId -> {correct, standardAnswer, explanation}
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    setMsg("加载中…");
    try {
      const res = await fetch(
        `${BASE_URL}/api/edu/student/exercise/list?userId=${encodeURIComponent(userId)}`
      );
      const json = await res.json().catch(() => null);
      if (json?.code === 200 && json.data?.success) {
        setData(json.data);
        setMsg("");
      } else {
        setData(null);
        setMsg(json?.msg || json?.data?.error || "加载失败");
      }
    } catch (e) {
      setMsg(`加载失败：${e}`);
    }
  }, [userId]);

  useEffect(() => {
    load();
  }, [load]);

  const pick = (question, letter) => {
    const qid = question.questionId;
    setAnswers((prev) => {
      if (String(question.qType).toUpperCase() === "MULTI") {
        const current = new Set((prev[qid] || "").split("").filter(Boolean));
        if (current.has(letter)) current.delete(letter);
        else current.add(letter);
        const next = [...current].sort().join("");
        return { ...prev, [qid]: next };
      }
      return { ...prev, [qid]: letter };
    });
  };

  const submit = async (question) => {
    const qid = question.questionId;
    const answer = answers[qid] || "";
    if (!answer) {
      setMsg("先选一个答案再提交");
      return;
    }
    setBusyId(qid);
    setMsg("提交中…");
    try {
      const res = await fetch(`${BASE_URL}/api/edu/student/exercise/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ userId, questionId: qid, answer }),
      });
      const json = await res.json().catch(() => null);
      if (json?.code === 200 && json.data?.success) {
        setResults((prev) => ({ ...prev, [qid]: json.data }));
        setMsg("");
      } else {
        setMsg(json?.msg || json?.data?.error || "提交失败");
      }
    } catch (e) {
      setMsg(`提交失败：${e}`);
    }
    setBusyId(null);
  };

  const exercises = data?.exercises || [];

  return (
    <div className="quiz-container">
      <div className="ops-root">
        <div className="ops-panel">
          <div className="ops-head">
            <div>
              <h2 className="ops-title">📚 班级作业</h2>
              <span className="ops-sub">
                老师布置的题 ｜ 只显示你所在班级的作业 ｜ 提交后自动判分
              </span>
            </div>
            <div className="edu-head-right">
              <span className="edu-head-meta">
                {data?.classId ? `我的班级：${data.classId}` : "班级未识别"}
              </span>
              <button className="btn tool-btn" onClick={load}>刷新</button>
            </div>
          </div>

          {msg && <div className="ops-msg is-error">{msg}</div>}

          {exercises.length === 0 && !msg && (
            <div className="edu-head-meta">
              本班目前没有已发布的作业（老师发布后这里会出现题目）
            </div>
          )}

          {exercises.map((q, idx) => {
            const qid = q.questionId;
            const qType = String(q.qType || "SINGLE").toUpperCase();
            const options = parseOptions(q.options, qType);
            const picked = answers[qid] || "";
            const result = results[qid];
            return (
              <div key={qid} className="edu-fold" style={{ marginTop: 10 }}>
                <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>
                  {idx + 1}. {q.stem}
                  <span className="edu-head-meta">
                    {" "}· {qType === "MULTI" ? "多选" : qType === "JUDGE" ? "判断" : "单选"}
                    {q.knowledgePoint ? ` · ${q.knowledgePoint}` : ""}
                  </span>
                </div>

                <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                  {(options.length > 0 ? options : ["A", "B"]).map((opt, i) => {
                    const letter = letterOf(opt, i);
                    const active = picked.includes(letter);
                    return (
                      <button
                        key={letter}
                        className="btn tool-btn"
                        style={active ? { outline: "2px solid #2a78d6" } : undefined}
                        onClick={() => pick(q, letter)}
                      >
                        {opt}
                      </button>
                    );
                  })}
                </div>

                <div className="mg-actions" style={{ marginTop: 8 }}>
                  <button
                    className="btn send-btn"
                    disabled={busyId === qid}
                    onClick={() => submit(q)}
                  >
                    {busyId === qid ? "提交中…" : result ? "重新提交" : "提交本题"}
                  </button>
                </div>

                {result && (
                  <div className="ops-msg" style={{ marginTop: 6, lineHeight: 1.8 }}>
                    {result.correct ? "✅ 答对了" : "❌ 答错了"}
                    {result.standardAnswer ? `　正确答案：${result.standardAnswer}` : ""}
                    {result.explanation ? (
                      <>
                        <br />
                        <span className="edu-head-meta">解析：{result.explanation}</span>
                      </>
                    ) : null}
                    <br />
                    <span className="edu-head-meta">
                      再次提交会覆盖本次作答（同一题只保留一次记录）
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
