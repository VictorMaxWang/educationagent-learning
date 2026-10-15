import { useCallback, useEffect, useState } from "react";
import "../ops/ops.css";
import "./edu.css";
import { arrayBufferToBase64, downloadBase64File, fileToBase64 } from "../utils/file";
import { useUiStore } from "../store/uiStore";

/**
 * 乡村教师助教工作台。
 *
 * <h3>这个页面刻意"看不到学生"</h3>
 * 页面上能出现的只有班级级数字：窗口天数、学生人数、危机信号计数、等级、聚合学情。
 * 没有学生列表、没有对话记录、没有"查看详情"按钮——
 * 不是没做，而是后端接口的响应里就不存在这些字段（`EduPrivacyTest` 用反射钉住了）。
 *
 * <h3>信息密度（2026-10-15 改版）</h3>
 * 改版前最大的问题是**解释性文字散落在每个卡片里**，把核心数字淹了。现在的分层是：
 * <pre>
 *   ① 4 张核心指标卡（标题 + 大号数字 + 一行小字）
 *   ② 左右分栏：风险时序 ｜ 匿名学情
 *   ③ 工具区（全部折叠，默认收起）
 *   ④ 底部**统一**隐私说明（折叠，默认收起）—— 所有解释只在这一处
 * </pre>
 * 逐日分布的"单日不足 2 条不展示""等级无法反推到个人"这类说明移到**悬浮提示**，
 * 页面上不再堆文字；颜色只在一处加重（风险等级），其余靠字号与灰阶做层级。
 */
import { API_BASE_URL as BASE_URL } from "../api/baseUrl";

export default function TeacherWorkbenchView() {
  // 登录会话放在 uiStore（持久化到 localStorage）：刷新/重启不丢，且导航能响应登录状态
  const token = useUiStore((s) => s.eduTeacherToken);
  const teacherName = useUiStore((s) => s.eduTeacherUsername);
  const teacherClassName = useUiStore((s) => s.eduTeacherClassName);
  const setEduTeacher = useUiStore((s) => s.setEduTeacher);
  const clearEduTeacher = useUiStore((s) => s.clearEduTeacher);

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginMsg, setLoginMsg] = useState("");
  const [loggingIn, setLoggingIn] = useState(false);

  const [days, setDays] = useState(7);
  const [data, setData] = useState(null);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const [stat, setStat] = useState(null);
  const [access, setAccess] = useState(null);
  const [accessMsg, setAccessMsg] = useState("");
  const [training, setTraining] = useState([]);
  const [resources, setResources] = useState([]);
  const [uploadMsg, setUploadMsg] = useState("");

  const [topic, setTopic] = useState("");
  const [notes, setNotes] = useState("");
  const [lesson, setLesson] = useState(null);
  const [lessonMsg, setLessonMsg] = useState("");

  const [exercises, setExercises] = useState([]);
  const [exerciseMsg, setExerciseMsg] = useState("");
  const emptyDraft = { stem: "", qType: "SINGLE", options: "", answer: "", explanation: "", knowledgePoint: "", expireTime: "" };
  const [draft, setDraft] = useState(emptyDraft);

  const [feedback, setFeedback] = useState("");
  const [feedbackList, setFeedbackList] = useState([]);
  const [feedbackMsg, setFeedbackMsg] = useState("");

  /** 统一带口令请求；401 统一处理（口令错/后端重启都会走这里） */
  const call = useCallback(
    async (path, options = {}) => {
      if (!token) {
        setMsg("请输入教师账号");
        return null;
      }
      const res = await fetch(`${BASE_URL}${path}`, {
        ...options,
        headers: { "X-Edu-Token": token, ...(options.headers || {}) },
      });
      if (res.status === 401) {
        // 令牌存在后端内存里，**重启后端即失效**——这时必须清掉本地会话，
        // 让页面自动退回登录页；否则会停在"看起来已登录、但每次请求都失败"的状态。
        clearEduTeacher();
        setMsg("登录已失效（后端可能已重启），请重新登录");
        return null;
      }
      return res;
    },
    [token, clearEduTeacher]
  );

  const loadSummary = useCallback(async () => {
    setBusy(true);
    setMsg("加载中…");
    const res = await call(`/api/edu/teacher/crisis-summary?days=${days}`);
    if (res) {
      const json = await res.json().catch(() => null);
      if (json?.code === 200 && json.data) {
        setData(json.data);
        setMsg("");
      } else {
        setMsg(json?.msg || "加载失败");
      }
    }
    setBusy(false);
  }, [call, days, token]);

  const loadStat = useCallback(async () => {
    const res = await call(`/api/edu/teacher/class/stat?days=${days}`);
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) setStat(json.data || null);
  }, [call, days]);

  const loadAccess = useCallback(async () => {
    const res = await call("/api/edu/teacher/knowledge/access");
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) setAccess(json.data || null);
  }, [call]);

  const loadTraining = useCallback(async () => {
    const res = await call("/api/edu/teacher/training/doc");
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) setTraining(json.data || []);
  }, [call]);

  const loadResources = useCallback(async () => {
    const res = await call("/api/edu/teacher/resource/list");
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) setResources(json.data || []);
  }, [call]);

  const loadFeedback = useCallback(async () => {
    const res = await call("/api/edu/teacher/feedback/list");
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) setFeedbackList(json.data || []);
  }, [call]);

  const loadExercises = useCallback(async () => {
    const res = await call("/api/edu/teacher/exercise/list");
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) setExercises(json.data || []);
  }, [call]);

  /** 「A. 1|B. 2」这类竖线写法转成 quiz_question 要的 JSON 数组；已经是 JSON 就原样用。 */
  const toOptionsJson = (raw) => {
    const s = (raw || "").trim();
    if (!s) return "";
    if (s.startsWith("[")) return s;
    return JSON.stringify(s.split(/[|｜]/).map((x) => x.trim()).filter(Boolean));
  };

  const createExercise = async () => {
    if (!draft.stem.trim() || !draft.answer.trim()) {
      setExerciseMsg("题干与标准答案不能为空");
      return;
    }
    setExerciseMsg("创建中…");
    const res = await call("/api/edu/teacher/exercise/create", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        stem: draft.stem.trim(),
        qType: draft.qType,
        options: toOptionsJson(draft.options),
        answer: draft.answer.trim(),
        explanation: draft.explanation.trim(),
        knowledgePoint: draft.knowledgePoint.trim(),
        expireTime: draft.expireTime.trim(),
        publish: true,
      }),
    });
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) {
      setExerciseMsg("已创建并发布到本班");
      setDraft(emptyDraft);
      loadExercises();
    } else {
      setExerciseMsg(json?.msg || "创建失败");
    }
  };

  const togglePublish = async (ex) => {
    setExerciseMsg("更新中…");
    const res = await call("/api/edu/teacher/exercise/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ questionId: ex.questionId, published: !ex.published }),
    });
    if (!res) return;
    const json = await res.json().catch(() => null);
    setExerciseMsg(json?.code === 200 ? (json.msg || "已更新") : (json?.msg || "更新失败"));
    loadExercises();
  };

  const refreshAll = useCallback(() => {
    loadSummary();
    loadStat();
    loadAccess();
    loadTraining();
    loadResources();
    loadFeedback();
    loadExercises();
  }, [loadSummary, loadStat, loadAccess, loadTraining, loadResources, loadFeedback, loadExercises]);

  useEffect(() => {
    if (token) refreshAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [days, token]);

  /** 教师登录：成功则把 token 与身份写进 uiStore（导航里的教师工作台随之出现） */
  const doLogin = async () => {
    if (!username.trim() || !password) {
      setLoginMsg("请输入账号与密码");
      return;
    }
    setLoggingIn(true);
    setLoginMsg("登录中…");
    try {
      const res = await fetch(`${BASE_URL}/api/edu/teacher/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
      });
      const json = await res.json().catch(() => null);
      if (json?.code === 200 && json.data?.token) {
        setEduTeacher({
          token: json.data.token,
          username: json.data.username || username,
          classId: json.data.classId || "",
          className: json.data.className || "",
        });
        setPassword("");
        setLoginMsg("");
      } else {
        setLoginMsg(json?.msg || "登录失败");
      }
    } catch (e) {
      setLoginMsg(`登录失败：${e}`);
    }
    setLoggingIn(false);
  };

  const doLogout = async () => {
    try {
      await call("/api/edu/teacher/logout", { method: "POST" });
    } catch {
      // 退出失败也要清本地会话，否则用户会卡在"看起来已登录"的状态
    }
    clearEduTeacher();
    setData(null);
    setStat(null);
    setResources([]);
    setFeedbackList([]);
    setLesson(null);
    setUsername("");
    setLoginMsg("");
  };

  /** 一键开关某专题库对本班的访问（只影响检索，不删材料）。 */
  const toggleAccess = async (domainId, enabled) => {
    setAccessMsg("设置中…");
    const res = await call("/api/edu/teacher/knowledge/access", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ domainId, enabled }),
    });
    if (!res) return;
    const json = await res.json().catch(() => null);
    setAccessMsg(json?.code === 200 ? (json.msg || "已更新") : (json?.msg || "设置失败"));
    loadAccess();
  };

  /** 上传校本资源：先走既有的 base64 上传通道换 fileId，再交给教师端接口入库 */
  const uploadFile = async (file) => {
    if (!file) return;
    setUploadMsg(`正在上传 ${file.name}…`);
    try {
      const base64 = await fileToBase64(file);
      const up = await fetch(`${BASE_URL}/api/desktop/file/upload`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data: base64, fileName: file.name, mime: file.type }),
      });
      const upJson = await up.json().catch(() => null);
      if (!upJson?.fileId) {
        setUploadMsg(`上传失败：${upJson?.error || up.status}`);
        return;
      }
      setUploadMsg("已上传，正在入库（首次入库需要几十秒）…");
      const res = await call("/api/edu/teacher/resource/upload", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ fileId: upJson.fileId }),
      });
      if (!res) return;
      const json = await res.json().catch(() => null);
      setUploadMsg(json?.code === 200
        ? `已入库：${json.data?.fileName || file.name}（索引状态 ${json.data?.indexState}）`
        : `失败：${json?.msg || "未知错误"}`);
      loadResources();
    } catch (e) {
      setUploadMsg(`上传失败：${e}`);
    }
  };

  const prepareLesson = async () => {
    if (!topic.trim()) {
      setLessonMsg("请先填写备课主题");
      return;
    }
    setLessonMsg("生成中…（约 10~30 秒）");
    setLesson(null);
    const res = await call("/api/edu/teacher/lesson/prepare", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ topic, notes }),
    });
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200 && json.data?.success) {
      setLesson(json.data);
      setLessonMsg("");
    } else {
      setLessonMsg(json?.msg || json?.data?.error || "生成失败");
    }
  };

  const exportLessonPdf = async () => {
    if (!lesson?.material) return;
    const res = await call("/api/edu/teacher/lesson/export-pdf", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: `备课素材：${topic}`, content: lesson.material }),
    });
    if (!res) return;
    if (!res.ok) {
      const j = await res.json().catch(() => null);
      setLessonMsg(j?.msg || `导出失败（HTTP ${res.status}）`);
      return;
    }
    // 必须走 utils/file.js 的原生保存：Tauri 的 WebView 里 a.download + blob URL 会被静默忽略
    const base64 = arrayBufferToBase64(await res.arrayBuffer());
    const savedResult = await downloadBase64File(base64, `备课素材_${topic || "lesson"}.pdf`);
    if (savedResult?.canceled) {
      setLessonMsg("已取消保存");
    } else if (savedResult?.saved) {
      setLessonMsg(`PDF 已保存到：${savedResult.saved}`);
    } else {
      setLessonMsg("已触发导出，请查看浏览器的下载目录");
    }
  };

  const submitFeedback = async () => {
    if (!feedback.trim()) {
      setFeedbackMsg("请先填写反馈内容");
      return;
    }
    const res = await call("/api/edu/teacher/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: feedback }),
    });
    if (!res) return;
    const json = await res.json().catch(() => null);
    if (json?.code === 200) {
      setFeedback("");
      setFeedbackMsg("已记录，谢谢反馈");
      loadFeedback();
    } else {
      setFeedbackMsg(json?.msg || "提交失败");
    }
  };

  const maxCount = Math.max(1, ...(data?.days || []).map((d) => d.count || 0));

  // 未登录：只显示登录卡（导航里的「教师工作台」此时也是隐藏的，见 App.jsx）
  if (!token) {
    return (
      <div className="quiz-container">
        <div className="ops-root">
          <div className="ops-panel" style={{ maxWidth: 460, margin: "40px auto" }}>
            <div className="ops-head">
              <h2 className="ops-title">🎓 教师登录</h2>
              <span className="ops-sub">乡村教师助教工作台 · 需教师账号</span>
            </div>
            <input
              className="law-question"
              style={{ minHeight: 36, padding: "6px 10px" }}
              placeholder="教师账号"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && doLogin()}
            />
            <input
              type="password"
              className="law-question"
              style={{ minHeight: 36, padding: "6px 10px", marginTop: 8 }}
              placeholder="密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && doLogin()}
            />
            <div className="mg-actions" style={{ marginTop: 10 }}>
              <button className="btn send-btn" disabled={loggingIn} onClick={doLogin}>
                {loggingIn ? "登录中…" : "登录"}
              </button>
            </div>
            {loginMsg && <div className="ops-msg is-error">{loginMsg}</div>}
            <div className="ops-msg" style={{ marginTop: 12, lineHeight: 1.8, fontSize: 13 }}>
              演示账号：<b>teacher1 / teacher123</b>（启动时自动创建，见
              <code> edu.auth.demo-teacher.* </code>）。
              <br />
              登录后只能看到<b>本班匿名聚合数据</b>：没有学生名单、没有学生 ID、没有对话内容。
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="quiz-container">
      <div className="ops-root">
        {/* ==================== 顶部：标题 + 控制区 ==================== */}
        <div className="ops-panel">
          <div className="ops-head">
            <div>
              <h2 className="ops-title">🎓 教师助教工作台</h2>
              <span className="ops-sub">班级匿名聚合视图 ｜ 只读，无学生个人原始数据</span>
            </div>
            <div className="edu-head-right">
              <span className="edu-head-meta">
                {teacherClassName || "（未命名班级）"} · {teacherName}
              </span>
              <select
                className="law-question"
                style={{ minHeight: 30, padding: "2px 6px", width: "auto" }}
                value={days}
                onChange={(e) => setDays(Number(e.target.value))}
              >
                <option value={7}>近 7 天</option>
                <option value={14}>近 14 天</option>
                <option value={30}>近 30 天</option>
              </select>
              <button className="btn send-btn" disabled={busy} onClick={refreshAll}>
                {busy ? "加载中…" : "刷新"}
              </button>
              <button className="btn tool-btn" onClick={doLogout}>退出登录</button>
            </div>
          </div>

          {msg && <div className="ops-msg is-error">{msg}</div>}

          {/* ==================== ① 核心指标卡（4 张） ==================== */}
          <div className="ops-tiles edu-tiles-lg">
            <div className="ops-tile edu-tile-lg">
              {/* 大号位放**班级名**（中文、短、可读），ID 放小字——
                  长 ASCII 串占大号位会撑破卡片，而 ID 本来就是内部标识，放副行更合适 */}
              <div className="ops-tile-label">班级</div>
              <div className="ops-tile-value edu-value-sm">
                {teacherClassName || data?.classId || "—"}
              </div>
              <div className="ops-tile-hint">
                {data?.classId || "—"} · 绑定当前教师账号
              </div>
            </div>
            <div className="ops-tile edu-tile-lg">
              <div className="ops-tile-label">班级总人数</div>
              <div className="ops-tile-value">{data?.studentCount ?? "—"}</div>
              <div className="ops-tile-hint">仅统计人数</div>
            </div>
            <div className="ops-tile edu-tile-lg">
              <div className="ops-tile-label">{`近 ${data?.windowDays ?? days} 天风险预警`}</div>
              <div className={`ops-tile-value${data && !data.suppressed ? " is-bad" : ""}`}>
                {data ? (data.suppressed ? "样本不足" : data.total) : "—"}
              </div>
              <div className="ops-tile-hint">按「学生 + 日期」去重</div>
            </div>
            <div className="ops-tile edu-tile-lg">
              <div className="ops-tile-label">最高风险等级</div>
              <div className={`ops-tile-value edu-value-sm${(data?.maxLevel || 0) >= 3 ? " is-bad" : ""}`}>
                {data ? levelLabel(data.maxLevel) : "—"}
              </div>
              <div className="ops-tile-hint">无法定位到单个学生</div>
            </div>
          </div>
        </div>

        {/* ==================== ② 左右分栏 ==================== */}
        <div className="edu-cols">
          {/* 左：风险信号逐日分布 */}
          <div className="ops-panel">
            <div className="ops-head">
              <h3 className="ops-title">风险信号 · 逐日分布</h3>
              <span className="ops-sub">按等级着色，鼠标悬浮看说明</span>
            </div>
            {(data?.days || []).length === 0 && (
              <div className="edu-head-meta">该窗口内没有风险信号记录</div>
            )}
            {(data?.days || []).map((d) => (
              <div className="edu-bar-row" key={d.date}>
                <span className="edu-bar-date">{d.date.slice(5)}</span>
                <span
                  className={`${d.hidden ? "edu-bar-track" : "edu-bar-fill"} edu-level-${d.level || 0}`}
                  style={{ width: `${d.hidden ? 6 : Math.max(6, Math.round(((d.count || 0) / maxCount) * 150))}px` }}
                  title={d.hidden
                    ? `计数不展示：${d.reason || "样本不足"}；等级照常给出——它无法反推到具体学生`
                    : `当日 ${d.count} 条 · 最高 ${levelLabel(d.level)}`}
                />
                <span className="edu-bar-value" title="等级无法反推到具体学生，故不参与隐藏">
                  {levelLabel(d.level)}
                  {d.hidden ? " · 计数不展示" : ` · ${d.count} 条`}
                </span>
              </div>
            ))}
          </div>

          {/* 右：班级匿名学情 */}
          <div className="ops-panel">
            <div className="ops-head">
              <h3 className="ops-title">班级匿名学情</h3>
              <span className="ops-sub">全部为聚合值</span>
            </div>

            {!stat && <div className="edu-head-meta">暂无数据，点右上「刷新」加载</div>}

            {stat && (
              <>
                <div className="ops-tiles">
                  <div className="ops-tile edu-tile">
                    <div className="ops-tile-label">习题正确率</div>
                    <div className="ops-tile-value">
                      {stat.quizHidden ? "样本不足" : `${stat.quizAccuracy}%`}
                    </div>
                    <div className="ops-tile-hint">
                      {stat.quizHidden
                        ? `作答 ${stat.quizAnswered} 条，未达展示阈值`
                        : `答对 ${stat.quizCorrect} / ${stat.quizAnswered} 题`}
                    </div>
                  </div>
                  <div className="ops-tile edu-tile">
                    <div className="ops-tile-label">专题提问</div>
                    <div className="ops-tile-value">
                      {stat.topicHidden ? "样本不足" : stat.topicTotal}
                    </div>
                    <div className="ops-tile-hint">窗口内提问条数</div>
                  </div>
                  <div className="ops-tile edu-tile">
                    <div className="ops-tile-label">最薄弱知识点</div>
                    <div className="ops-tile-value edu-value-sm">
                      {(stat.weakKnowledgePoints || [])[0]?.point || "—"}
                    </div>
                    <div className="ops-tile-hint">
                      {(stat.weakKnowledgePoints || [])[0]
                        ? `错 ${(stat.weakKnowledgePoints || [])[0].wrong} 题`
                        : "窗口内没有错题记录"}
                    </div>
                  </div>
                </div>

                {!stat.weakPointsHidden && (stat.weakKnowledgePoints || []).length > 0 && (
                  <>
                    <div className="edu-block-title">薄弱知识点分布</div>
                    {(stat.weakKnowledgePoints || []).slice(0, 6).map((w) => (
                      <div className="edu-bar-row" key={w.point}
                           title={`${w.point}：错 ${w.wrong} 题，占错题 ${w.share}%`}>
                        <span className="edu-bar-date" style={{ width: 130 }}>{w.point}</span>
                        <span className="edu-stat-bar" style={{ width: `${Math.max(4, Math.round((w.share || 0) * 1.3))}px` }} />
                        <span className="edu-bar-value">{w.share}%</span>
                      </div>
                    ))}
                  </>
                )}

                {!stat.topicHidden && (stat.topicDistribution || []).length > 0 && (
                  <>
                    <div className="edu-block-title">专题提问占比</div>
                    {(stat.topicDistribution || []).map((t) => (
                      <div className="edu-bar-row" key={t.topic}
                           title={`${topicLabel(t.topic)}：${t.count} 次，占 ${t.share}%`}>
                        <span className="edu-bar-date" style={{ width: 130 }}>{topicLabel(t.topic)}</span>
                        <span className="edu-stat-bar" style={{ width: `${Math.max(4, Math.round((t.share || 0) * 1.3))}px` }} />
                        <span className="edu-bar-value">{t.share}%</span>
                      </div>
                    ))}
                  </>
                )}
              </>
            )}
          </div>
        </div>

        {/* ==================== ③ 工具区（折叠，默认收起） ==================== */}
        <details className="edu-fold">
          <summary>
            📝 习题管理
            <span className="edu-fold-hint">出题 → 发布到本班 → 看聚合答题统计（学生端「班级作业」可见）</span>
          </summary>
          <div style={{ marginTop: 10 }}>
            <input
              className="law-question"
              style={{ minHeight: 34, padding: "6px 10px" }}
              placeholder="题干"
              value={draft.stem}
              onChange={(e) => setDraft({ ...draft, stem: e.target.value })}
            />
            <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
              <select
                className="law-question"
                style={{ minHeight: 34, padding: "6px 10px", width: "auto" }}
                value={draft.qType}
                onChange={(e) => setDraft({ ...draft, qType: e.target.value })}
              >
                <option value="SINGLE">单选</option>
                <option value="MULTI">多选</option>
                <option value="JUDGE">判断</option>
              </select>
              <input
                className="law-question"
                style={{ minHeight: 34, padding: "6px 10px", flex: 1, minWidth: 200 }}
                placeholder="选项（用 | 分隔，如：A. 1|B. 2|C. 3）"
                value={draft.options}
                onChange={(e) => setDraft({ ...draft, options: e.target.value })}
              />
              <input
                className="law-question"
                style={{ minHeight: 34, padding: "6px 10px", width: 170 }}
                placeholder="标准答案（单选 A／多选 AB）"
                value={draft.answer}
                onChange={(e) => setDraft({ ...draft, answer: e.target.value })}
              />
            </div>
            <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
              <input
                className="law-question"
                style={{ minHeight: 34, padding: "6px 10px", flex: 1, minWidth: 180 }}
                placeholder="知识点标签（用于薄弱项归因，如：情绪调节）"
                value={draft.knowledgePoint}
                onChange={(e) => setDraft({ ...draft, knowledgePoint: e.target.value })}
              />
              <input
                className="law-question"
                style={{ minHeight: 34, padding: "6px 10px", width: 190 }}
                placeholder="截止时间（可空，2026-11-01T18:00）"
                value={draft.expireTime}
                onChange={(e) => setDraft({ ...draft, expireTime: e.target.value })}
              />
            </div>
            <input
              className="law-question"
              style={{ minHeight: 34, padding: "6px 10px", marginTop: 8 }}
              placeholder="解析（可空）"
              value={draft.explanation}
              onChange={(e) => setDraft({ ...draft, explanation: e.target.value })}
            />
            <div className="mg-actions" style={{ marginTop: 8 }}>
              <button className="btn send-btn" onClick={createExercise}>创建并发布</button>
            </div>
            {exerciseMsg && <div className="ops-msg">{exerciseMsg}</div>}
            <div className="ops-msg" style={{ fontSize: 12, lineHeight: 1.7 }}>
              简答题暂不开放（自动判分需接入 LLM 判分，否则未判分的题会被当成错题污染学情）；
              题目内容会过安全词表，命中即拒绝保存。
            </div>

            <div className="edu-block-title">本班习题（{exercises.length}）</div>
            {exercises.length === 0 && <div className="edu-head-meta">还没有出过题</div>}
            {exercises.map((ex) => {
              const answered = Number(ex.answeredCount || 0);
              const correct = Number(ex.correctCount || 0);
              const rate = answered === 0 ? "—" : `${Math.round((correct * 100.0) / answered)}%`;
              return (
                <div className="edu-bar-row" key={ex.questionId}>
                  <span className="edu-bar-date" style={{ width: 150 }}>{ex.knowledgePoint || "未标知识点"}</span>
                  <span className="edu-bar-value" style={{ flex: 1 }}>
                    {ex.stem}
                    <span className="edu-head-meta">
                      {" "}· {ex.published ? "已发布" : "草稿"} · 作答 {answered} 人 · 正确率 {rate}
                    </span>
                  </span>
                  <button className="btn tool-btn" onClick={() => togglePublish(ex)} title="已有人作答时不能改题干/答案">
                    {ex.published ? "下架" : "发布"}
                  </button>
                </div>
              );
            })}
          </div>
        </details>

        <details className="edu-fold">
          <summary>
            🔐 知识库访问开关
            <span className="edu-fold-hint">专题库默认对本班学生开放，可一键停用（不删除材料）</span>
          </summary>
          <div style={{ marginTop: 10 }}>
            {access && Object.entries(access.domains || {}).map(([domainId, item]) => (
              <div className="edu-bar-row" key={domainId}>
                <span className="edu-bar-date" style={{ width: 170 }}>{topicLabel(domainId)}</span>
                <span className="edu-bar-value" style={{ flex: 1 }}>
                  {item.enabled ? "开放中" : "已停用"}
                  <span className="edu-head-meta"> · 权限等级 {item.domainLevel}</span>
                </span>
                <button className="btn tool-btn" onClick={() => toggleAccess(domainId, !item.enabled)}>
                  {item.enabled ? "停用" : "恢复开放"}
                </button>
              </div>
            ))}
            {accessMsg && <div className="ops-msg">{accessMsg}</div>}
          </div>
        </details>

        <details className="edu-fold">
          <summary>
            📚 乡土校本资源
            <span className="edu-fold-hint">上传后生成本班私有知识库（支持 txt / pdf / docx）</span>
          </summary>
          <div style={{ marginTop: 10 }}>
            <input
              type="file"
              accept=".txt,.pdf,.docx,.md"
              onChange={(e) => {
                uploadFile(e.target.files?.[0]);
                e.target.value = "";
              }}
            />
            {uploadMsg && <div className="ops-msg">{uploadMsg}</div>}
            <div className="edu-block-title">已上传（{resources.length}）</div>
            {resources.length === 0 && <div className="edu-head-meta">还没有上传过校本资料</div>}
            {resources.map((r) => (
              <div className="edu-bar-row" key={r.id}>
                <span className="edu-bar-date">{String(r.uploadedAt || "").slice(0, 10)}</span>
                <span className="edu-bar-value">{r.fileName}</span>
              </div>
            ))}
          </div>
        </details>

        <details className="edu-fold">
          <summary>
            🧑🏫 备课素材
            <span className="edu-fold-hint">依据本班校本资料生成，可导出 PDF</span>
          </summary>
          <div style={{ marginTop: 10 }}>
            <input
              className="law-question"
              style={{ minHeight: 34, padding: "6px 10px" }}
              placeholder="备课主题，例如：青春期身体变化 一节"
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
            />
            <input
              className="law-question"
              style={{ minHeight: 34, padding: "6px 10px", marginTop: 8 }}
              placeholder="补充要求（可空），例如：面向七年级，40 分钟课时"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
            <div className="mg-actions" style={{ marginTop: 8 }}>
              <button className="btn send-btn" onClick={prepareLesson}>生成素材</button>
              <button className="btn tool-btn" disabled={!lesson?.material} onClick={exportLessonPdf}>
                导出 PDF
              </button>
            </div>
            {lessonMsg && <div className="ops-msg">{lessonMsg}</div>}
            {lesson?.material && (
              <div style={{ marginTop: 10 }}>
                {lesson.references?.length > 0 ? (
                  <div className="ops-msg">
                    <strong>依据的校本资料：</strong>{lesson.references.join("、")}
                  </div>
                ) : (
                  <div className="ops-msg">
                    <strong>未检索到校本资料</strong>，以下为通用建议（请核对后使用）
                  </div>
                )}
                <div className="law-article-content" style={{ whiteSpace: "pre-wrap", marginTop: 8 }}>
                  {lesson.material}
                </div>
                {lesson.disclaimer && (
                  <div className="ops-msg" style={{ marginTop: 8, fontSize: 12 }}>{lesson.disclaimer}</div>
                )}
              </div>
            )}
          </div>
        </details>

        <details className="edu-fold">
          <summary>
            📖 专题教育培训参考
            <span className="edu-fold-hint">与知识库对学生的作答口径一致，避免两套说法</span>
          </summary>
          <div style={{ marginTop: 10 }}>
            {training.length === 0 && <div className="edu-head-meta">暂无资料，点右上「刷新」加载</div>}
            {training.map((t) => (
              <div key={t.topic} style={{ marginBottom: 12 }}>
                <div className="edu-block-title">
                  {t.title}
                  {!t.available && <span className="edu-head-meta">（知识库未注册）</span>}
                </div>
                {(t.sources || []).length > 0 && (
                  <div className="edu-head-meta">
                    依据语料 {(t.sources || []).length} 份
                  </div>
                )}
                {t.guidance && (
                  <details className="law-article-content" style={{ marginTop: 6 }}>
                    <summary style={{ cursor: "pointer", fontSize: 13 }}>查看 AI 回答口径（教学边界）</summary>
                    <div style={{ whiteSpace: "pre-wrap", marginTop: 6 }}>{t.guidance}</div>
                  </details>
                )}
                {t.disclaimer && (
                  <div className="ops-msg" style={{ marginTop: 6, fontSize: 12 }}>{t.disclaimer}</div>
                )}
              </div>
            ))}
          </div>
        </details>

        <details className="edu-fold">
          <summary>
            ✍️ 错误反馈
            <span className="edu-fold-hint">反馈 AI 输出质量问题（资料不对、答非所问等）</span>
          </summary>
          <div style={{ marginTop: 10 }}>
            <textarea
              className="law-question"
              rows={3}
              placeholder="例如：关于「青春期心理变化」的回答引用了不适龄的材料"
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
            />
            <div className="mg-actions" style={{ marginTop: 8 }}>
              <button className="btn send-btn" onClick={submitFeedback}>提交反馈</button>
            </div>
            {feedbackMsg && <div className="ops-msg">{feedbackMsg}</div>}
            <div className="edu-block-title">历史反馈（{feedbackList.length}）</div>
            {feedbackList.map((f) => (
              <div className="edu-bar-row" key={f.id}>
                <span className="edu-bar-date">{String(f.createdAt || "").slice(0, 10)}</span>
                <span className="edu-bar-value">{f.message}</span>
              </div>
            ))}
          </div>
        </details>

        {/* ==================== ④ 底部统一隐私说明（折叠） ==================== */}
        <details className="edu-privacy">
          <summary>📖 隐私说明（本页所有数据的口径都在这）</summary>
          <ol>
            <li>
              本工作台<b>仅展示班级聚合统计</b>，不存储、不展示任何学生个体标识；
              数据库里<b>没有</b>这些数据的个人级记录（风险预警表连 user_id 字段都没有）。
            </li>
            <li>
              班级由教师账号绑定，<b>页面不可切换班级</b>；接口也<b>不接收</b>班级参数——
              改 URL、改请求体都取不到别的班。
            </li>
            <li>
              风险计数按「<b>学生 + 日期</b>」去重：每位学生每日最多计 1 次，系统无法追溯到具体学生；
              计数不足阈值时隐藏数值，但<b>等级照常给出</b>——等级无法反推到个人，
              而"班里存在高风险信号"恰恰是教师最需要知道的。
            </li>
            <li>
              所有学情为聚合结果，<b>不提供学生明细清单</b>；
              当前数据为<b>原型演示样本</b>，用于功能验证，正式落地后对接学校平台的真实匿名班级数据。
            </li>
          </ol>
        </details>
      </div>
    </div>
  );
}

/**
 * 预警等级的文字标签。
 *
 * <p>等级<b>必须配文字</b>，不能只靠颜色深浅：色盲用户分辨不出三档，
 * 而且"3 级"这个数字本身不含语义，教师看到"高度关注"才知道该做什么。
 */
function levelLabel(level) {
  if (level === 3) return "3 级 · 高度关注";
  if (level === 2) return "2 级 · 需关注";
  if (level === 1) return "1 级 · 留意";
  return "无信号";
}

/** 专题标识 → 中文名（与后端 edu_topic_ask.topic 的取值对应）。 */
function topicLabel(topic) {
  const map = {
    lifesafety: "生命安全与健康教育",
    sexedu: "青春期性教育",
    school: "校本资源",
  };
  return map[topic] || topic;
}
