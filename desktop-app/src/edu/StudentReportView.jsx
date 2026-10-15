import { useState } from "react";
import "../ops/ops.css";
import "./edu.css";
import { arrayBufferToBase64, downloadBase64File } from "../utils/file";

/**
 * 我的学习周报（学生侧）。
 *
 * 三条原则直接写在页面上，而不是藏在文档里：
 * ① 只包含你自己的数据；② 只有你点"生成"才会生成；③ 系统不会自动上传或分享给老师。
 * 这三句是"无隐私学习档案"这个卖点的可见部分——评委和用户都该在界面上看到它。
 */

import { API_BASE_URL as BASE_URL } from "../api/baseUrl";

/** 与 CourseKnowledgeView / App.jsx 同款：localStorage 持久化的桌面端用户 ID */
function getDesktopUserId() {
  let uid = localStorage.getItem("desktopUserId");
  if (!uid) {
    uid = "user_" + Math.random().toString(36).slice(2, 10);
    localStorage.setItem("desktopUserId", uid);
  }
  return uid;
}

export default function StudentReportView() {
  const [userId] = useState(getDesktopUserId);
  const [days, setDays] = useState(7);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const exportReport = async () => {
    setBusy(true);
    setMsg("生成中…");
    try {
      const res = await fetch(`${BASE_URL}/api/edu/student/weekly-report`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ userId, days }),
      });
      if (!res.ok) {
        const j = await res.json().catch(() => null);
        setMsg(j?.msg || `生成失败（HTTP ${res.status}）`);
        setBusy(false);
        return;
      }
      // 必须走 utils/file.js 的原生保存：Tauri 的 WebView 里 a.download + blob URL 会被静默忽略
      // （界面上"看起来下载了"，实际什么文件都没有），原生 command 会弹出"另存为"对话框；
      // 浏览器调试时该函数自动降级为 a.download（且延后 revoke，避免下载没开始引用就被撤销）。
      const base64 = arrayBufferToBase64(await res.arrayBuffer());
      const result = await downloadBase64File(base64, `学习周报_${userId}.docx`);
      if (result?.canceled) {
        setMsg("已取消保存");
      } else if (result?.saved) {
        setMsg(`已保存到：${result.saved}`);
      } else {
        setMsg("已触发生成，请查看浏览器的下载目录");
      }
    } catch (e) {
      setMsg(`生成失败：${e}`);
    }
    setBusy(false);
  };

  return (
    <div className="quiz-container">
      <div className="ops-root">
        <div className="ops-panel">
          <div className="ops-head">
            <h2 className="ops-title">📄 我的学习周报</h2>
            <span className="ops-sub">只包含你自己的数据 · 只有你点生成才会生成</span>
          </div>

          <div className="ops-tiles" style={{ alignItems: "flex-end" }}>
            <div>
              <div style={{ fontSize: 12, color: "var(--text-muted)", marginBottom: 4 }}>统计窗口</div>
              <select className="law-question" style={{ minHeight: 34, padding: "6px 10px" }} value={days} onChange={(e) => setDays(Number(e.target.value))}>
                <option value={7}>近 7 天</option>
                <option value={14}>近 14 天</option>
                <option value={30}>近 30 天</option>
              </select>
            </div>
            <button className="btn send-btn" disabled={busy} onClick={exportReport}>
              {busy ? "生成中…" : "生成并下载 Word 周报"}
            </button>
          </div>

          {msg && <div className="ops-msg">{msg}</div>}

          <div className="ops-msg" style={{ marginTop: 12, lineHeight: 1.8 }}>
            <strong>你会得到什么：</strong>本周的对话与提问条数、刷题作答与正确率、错题数，
            以及本地学习档案的条数（这份档案只存在这台电脑上）。
            <br />
            <strong>隐私说明：</strong>本报告由你的设备发起、只查询你自己的记录，
            系统不会自动上传，也不会把内容分享给老师或其他人。
            老师能看到的是整个班级的匿名统计（例如本周有多少人使用过、出现多少条需要关注的风险信号），
            看不到任何人的对话内容。
            <br />
            <span style={{ fontSize: 12, color: "var(--text-muted)" }}>
              当前身份：{userId}（保存在本机浏览器中，用于区分不同使用者的数据）
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
