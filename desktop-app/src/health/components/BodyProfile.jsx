import { useEffect, useState } from "react";
import {
  loadMembers, createMember, updateMember, deleteMember,
  loadBodyRecords, saveBodyRecords,
  loadPeriod, savePeriod,
  loadBloodRecords, saveBloodRecords,
} from "../utils/members.js";

/**
 * 📋 个人身体档案 — 围度 / 生理期 / 血压血糖（成员作用域）
 * 从 health 原 App.jsx 移植，复用 members.js + health.css。
 */

const MEASURE_FIELDS = [
  { key: "height",   label: "身高",   unit: "cm", icon: "📏", category: "基础" },
  { key: "weight",   label: "体重",   unit: "kg", icon: "⚖️", category: "基础" },
  { key: "chest",    label: "胸围",   unit: "cm", icon: "📐", category: "躯干" },
  { key: "waist",    label: "腰围",   unit: "cm", icon: "📐", category: "躯干" },
  { key: "hip",      label: "臀围",   unit: "cm", icon: "📐", category: "躯干" },
  { key: "neck",     label: "颈围",   unit: "cm", icon: "📐", category: "躯干" },
  { key: "shoulder", label: "肩宽",   unit: "cm", icon: "📐", category: "躯干" },
  { key: "armL",     label: "左上臂", unit: "cm", icon: "💪", category: "上肢" },
  { key: "armR",     label: "右上臂", unit: "cm", icon: "💪", category: "上肢" },
  { key: "forearmL", label: "左前臂", unit: "cm", icon: "💪", category: "上肢" },
  { key: "forearmR", label: "右前臂", unit: "cm", icon: "💪", category: "上肢" },
  { key: "thighL",   label: "左大腿", unit: "cm", icon: "🦵", category: "下肢" },
  { key: "thighR",   label: "右大腿", unit: "cm", icon: "🦵", category: "下肢" },
  { key: "calfL",    label: "左小腿", unit: "cm", icon: "🦵", category: "下肢" },
  { key: "calfR",    label: "右小腿", unit: "cm", icon: "🦵", category: "下肢" },
  { key: "bodyFat",  label: "体脂率", unit: "%",  icon: "🔬", category: "成分" },
  { key: "muscle",   label: "肌肉量", unit: "kg", icon: "🔬", category: "成分" },
];

const calcBMI = (h, w) => (h && w ? (w / (h / 100) ** 2).toFixed(1) : null);
const calcWHR = (waist, hip) => (waist && hip ? (waist / hip).toFixed(2) : null);

const groupFields = () =>
  MEASURE_FIELDS.reduce((g, f) => {
    if (!g[f.category]) g[f.category] = [];
    g[f.category].push(f);
    return g;
  }, {});

export default function BodyProfile({ members, activeMemberId, onSwitchMember, onMembersChange, onClose }) {
  const [bodyTab, setBodyTab] = useState("measure");
  const [bodyRecords, setBodyRecords] = useState([]);
  const [editingRecord, setEditingRecord] = useState(null);
  const [periodData, setPeriodData] = useState({ cycleDays: 28, periodDays: 5, lastStart: "", symptoms: [] });
  const [bloodRecords, setBloodRecords] = useState([]);
  const [editingBlood, setEditingBlood] = useState(null);
  const [showMemberForm, setShowMemberForm] = useState(false);
  const [memberForm, setMemberForm] = useState({ name: "", age: "", gender: "", wxid: "" });
  const [editingMemberId, setEditingMemberId] = useState(null);

  const activeMember = members.find((m) => m.id === activeMemberId) || members[0] || { id: "default", name: "本人" };
  const isFemale = activeMember.gender === "女";
  const latestRecord = bodyRecords[0] || {};
  const bmi = calcBMI(latestRecord.height, latestRecord.weight);
  const whr = calcWHR(latestRecord.waist, latestRecord.hip);

  // 成员切换时加载该成员数据；非女性时自动切离生理期标签
  useEffect(() => {
    setBodyRecords(loadBodyRecords(activeMemberId));
    setPeriodData(loadPeriod(activeMemberId));
    setBloodRecords(loadBloodRecords(activeMemberId));
    setEditingRecord(null);
    setEditingBlood(null);
    if (!isFemale && bodyTab === "period") {
      setBodyTab("measure");
    }
  }, [activeMemberId]);

  // ==================== 围度 ====================
  const saveBodyRecord = () => {
    if (!editingRecord?.date) return;
    const next = [...bodyRecords.filter((r) => r.date !== editingRecord.date), { ...editingRecord }]
      .sort((a, b) => b.date.localeCompare(a.date));
    setBodyRecords(next);
    saveBodyRecords(next, activeMemberId);
  };
  const updateEditField = (key, val) =>
    setEditingRecord((prev) => ({ ...prev, [key]: val ? parseFloat(val) || "" : "" }));
  const deleteBodyRecord = (date) => {
    const next = bodyRecords.filter((r) => r.date !== date);
    setBodyRecords(next);
    saveBodyRecords(next, activeMemberId);
  };

  // ==================== 生理期 ====================
  const predictNextPeriod = () => {
    if (!periodData.lastStart) return null;
    const d = new Date(periodData.lastStart);
    d.setDate(d.getDate() + (periodData.cycleDays || 28));
    return d.toISOString().slice(0, 10);
  };
  const savePeriodData = () => savePeriod({ ...periodData }, activeMemberId);
  const daysSincePeriod = periodData.lastStart
    ? Math.floor((new Date() - new Date(periodData.lastStart)) / 86400000)
    : null;

  // ==================== 血压血糖 ====================
  const saveBloodRecord = () => {
    if (!editingBlood?.date) return;
    const next = [...bloodRecords.filter((r) => r.date !== editingBlood.date || r.time !== editingBlood.time), { ...editingBlood }]
      .sort((a, b) => b.date.localeCompare(a.date) || (a.time || "").localeCompare(b.time || ""));
    setBloodRecords(next);
    saveBloodRecords(next, activeMemberId);
  };
  const deleteBloodRecord = (idx) => {
    const next = bloodRecords.filter((_, i) => i !== idx);
    setBloodRecords(next);
    saveBloodRecords(next, activeMemberId);
  };

  // ==================== 成员管理 ====================
  const switchMember = (id) => {
    onSwitchMember(id);
  };
  const handleAddMember = () => {
    if (!memberForm.name) return;
    createMember(memberForm.name, memberForm.age, memberForm.gender, memberForm.wxid);
    setMemberForm({ name: "", age: "", gender: "", wxid: "" });
    setShowMemberForm(false);
    onMembersChange();
  };
  const handleUpdateMember = () => {
    if (!memberForm.name) return;
    updateMember(editingMemberId, memberForm);
    setMemberForm({ name: "", age: "", gender: "", wxid: "" });
    setShowMemberForm(false);
    setEditingMemberId(null);
    onMembersChange();
  };
  const handleDeleteMember = (id) => {
    if (!window.confirm("确定删除该成员档案？所有数据将丢失。")) return;
    deleteMember(id);
    onMembersChange();
  };
  const openEditMember = (m) => {
    setEditingMemberId(m.id);
    setMemberForm({ name: m.name, age: m.age || "", gender: m.gender || "", wxid: m.wxid || "" });
    setShowMemberForm(true);
  };

  const timeLabel = { morning: "🌅早", noon: "☀️中", evening: "🌙晚" };

  return (
    <div className="body-panel">

      {/* 成员编辑弹层 */}
      {showMemberForm && (
        <div className="member-form-overlay" onClick={() => setShowMemberForm(false)}>
          <div className="member-form-card" onClick={(e) => e.stopPropagation()}>
            <h3>{editingMemberId ? "编辑成员" : "添加成员"}</h3>
            <div className="member-form-body">
              <input placeholder="姓名 *" value={memberForm.name}
                onChange={(e) => setMemberForm({ ...memberForm, name: e.target.value })} autoFocus />
              <input placeholder="年龄" type="number" value={memberForm.age}
                onChange={(e) => setMemberForm({ ...memberForm, age: e.target.value })} />
              <select value={memberForm.gender} onChange={(e) => setMemberForm({ ...memberForm, gender: e.target.value })}>
                <option value="">性别（选填）</option>
                <option value="男">男</option>
                <option value="女">女</option>
              </select>
              <input placeholder="微信 wxid（用于定时推送）" value={memberForm.wxid}
                onChange={(e) => setMemberForm({ ...memberForm, wxid: e.target.value })} />
            </div>
            <div className="member-form-actions">
              <button className="btn-cancel" onClick={() => setShowMemberForm(false)}>取消</button>
              {editingMemberId && (
                <button className="btn-delete-member" onClick={() => { handleDeleteMember(editingMemberId); setShowMemberForm(false); }}>🗑 删除</button>
              )}
              <button className="btn-save-member" onClick={editingMemberId ? handleUpdateMember : handleAddMember}>
                {editingMemberId ? "保存修改" : "创建成员"}
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="body-inline-header">
        <h2>📋 {activeMember.name}的身体档案</h2>
        <div className="body-tabs">
          <button className={`body-tab${bodyTab === "measure" ? " active" : ""}`} onClick={() => setBodyTab("measure")}>📐 围度</button>
          {isFemale && <button className={`body-tab${bodyTab === "period" ? " active" : ""}`} onClick={() => setBodyTab("period")}>🌸 生理期</button>}
          <button className={`body-tab${bodyTab === "blood" ? " active" : ""}`} onClick={() => setBodyTab("blood")}>🩸 血压血糖</button>
        </div>
        <button className="body-panel-close" onClick={onClose}>✕</button>
      </div>

      <div className="body-panel-content">
        {/* ═══ 围度测量 ═══ */}
        {bodyTab === "measure" && (
          <>
            <div className="body-quick-stats">
              <div className="body-stat-card"><span className="body-stat-icon">⚖️</span><span className="body-stat-val">{latestRecord.weight || "--"}<small>kg</small></span><span className="body-stat-label">体重</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">📏</span><span className="body-stat-val">{latestRecord.height || "--"}<small>cm</small></span><span className="body-stat-label">身高</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">📊</span><span className="body-stat-val">{bmi || "--"}</span><span className="body-stat-label">BMI</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">🔄</span><span className="body-stat-val">{whr || "--"}</span><span className="body-stat-label">腰臀比</span></div>
            </div>
            <div className="body-form">
              <div className="body-form-row">
                <label>📅 日期</label>
                <input type="date" value={editingRecord?.date || ""} onChange={(e) => setEditingRecord((prev) => ({ ...prev, date: e.target.value }))} />
              </div>
              {Object.entries(groupFields()).map(([cat, fields]) => (
                <div key={cat} className="body-category">
                  <div className="body-cat-title">{cat}</div>
                  <div className="body-fields-grid">
                    {fields.map((f) => (
                      <div key={f.key} className="body-field">
                        <label>{f.icon} {f.label}<small>（{f.unit}）</small></label>
                        <input type="number" step="0.1" min="0" placeholder="--"
                          value={editingRecord?.[f.key] ?? ""}
                          onChange={(e) => updateEditField(f.key, e.target.value)} />
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
            <div className="body-form-actions">
              <button className="body-save-btn" onClick={saveBodyRecord}>💾 保存记录</button>
            </div>
            {bodyRecords.length > 0 && (
              <div className="body-history">
                <div className="body-cat-title">📅 历史记录</div>
                {bodyRecords.map((rec) => (
                  <div key={rec.date} className="body-history-item">
                    <span className="body-history-date">{rec.date}</span>
                    <span className="body-history-summary">
                      {[rec.weight && `⚖️${rec.weight}kg`, rec.waist && `腰${rec.waist}`, rec.hip && `臀${rec.hip}`, rec.bodyFat && `脂${rec.bodyFat}%`].filter(Boolean).join(" · ")}
                    </span>
                    <button className="body-history-load" onClick={() => setEditingRecord({ ...rec })}>✏️</button>
                    <button className="body-history-del" onClick={() => deleteBodyRecord(rec.date)}>🗑️</button>
                  </div>
                ))}
              </div>
            )}
          </>
        )}

        {/* ═══ 生理期 ═══ */}
        {bodyTab === "period" && isFemale && (
          <>
            <div className="body-quick-stats">
              <div className="body-stat-card"><span className="body-stat-icon">🩸</span><span className="body-stat-val">{periodData.cycleDays || 28}<small>天</small></span><span className="body-stat-label">周期</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">📅</span><span className="body-stat-val">{periodData.periodDays || 5}<small>天</small></span><span className="body-stat-label">经期</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">🔮</span><span className="body-stat-val">{predictNextPeriod() || "--"}</span><span className="body-stat-label">预估下次</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">⏳</span><span className="body-stat-val">{daysSincePeriod ?? "--"}<small>天前</small></span><span className="body-stat-label">距上次</span></div>
            </div>
            <div className="body-form">
              <div className="body-form-row">
                <label>📅 最近一次开始</label>
                <input type="date" value={periodData.lastStart || ""} onChange={(e) => setPeriodData((p) => ({ ...p, lastStart: e.target.value }))} />
              </div>
              <div className="body-fields-grid">
                <div className="body-field"><label>🔄 周期天数</label><input type="number" min="15" max="60" value={periodData.cycleDays || ""} onChange={(e) => setPeriodData((p) => ({ ...p, cycleDays: parseInt(e.target.value) || 28 }))} /></div>
                <div className="body-field"><label>🩸 经期天数</label><input type="number" min="1" max="15" value={periodData.periodDays || ""} onChange={(e) => setPeriodData((p) => ({ ...p, periodDays: parseInt(e.target.value) || 5 }))} /></div>
              </div>
              <div className="body-cat-title" style={{ marginTop: 12 }}>🤒 症状记录（逗号分隔）</div>
              <input style={{ width: "100%", padding: "8px 12px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13, fontFamily: "inherit" }}
                placeholder="如：腹痛、腰酸、头痛、疲劳..."
                value={(periodData.symptoms || []).join("，")}
                onChange={(e) => setPeriodData((p) => ({ ...p, symptoms: e.target.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean) }))} />
            </div>
            <div className="body-form-actions"><button className="body-save-btn" onClick={savePeriodData}>💾 保存设置</button></div>
            {predictNextPeriod() && (
              <div className="body-history">
                <div className="body-cat-title">📊 周期概览</div>
                <div className="body-history-item"><span className="body-history-date">上次</span><span className="body-history-summary">{periodData.lastStart} 起，持续 {periodData.periodDays} 天</span></div>
                <div className="body-history-item"><span className="body-history-date">预估下次</span><span className="body-history-summary">{predictNextPeriod()} 左右（周期 {periodData.cycleDays} 天）</span></div>
                {periodData.symptoms?.length > 0 && <div className="body-history-item"><span className="body-history-date">症状</span><span className="body-history-summary">{periodData.symptoms.join("、")}</span></div>}
              </div>
            )}
          </>
        )}

        {/* ═══ 血压血糖 ═══ */}
        {bodyTab === "blood" && (
          <>
            <div className="body-quick-stats">
              <div className="body-stat-card"><span className="body-stat-icon">💓</span><span className="body-stat-val">{editingBlood?.sbp || "--"}<small>/mmHg</small></span><span className="body-stat-label">收缩压</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">💗</span><span className="body-stat-val">{editingBlood?.dbp || "--"}<small>/mmHg</small></span><span className="body-stat-label">舒张压</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">🍬</span><span className="body-stat-val">{editingBlood?.glucose || "--"}<small>mmol/L</small></span><span className="body-stat-label">血糖</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">💓</span><span className="body-stat-val">{editingBlood?.heartRate || "--"}<small>bpm</small></span><span className="body-stat-label">心率</span></div>
            </div>
            <div className="body-form">
              <div className="body-form-row"><label>📅 日期</label><input type="date" value={editingBlood?.date || ""} onChange={(e) => setEditingBlood((p) => ({ ...p, date: e.target.value }))} /></div>
              <div className="body-fields-grid">
                <div className="body-field"><label>🕐 时段</label><select value={editingBlood?.time || "morning"} onChange={(e) => setEditingBlood((p) => ({ ...p, time: e.target.value }))} style={{ padding: "7px 10px", border: "1px solid var(--border)", borderRadius: 7, fontSize: 13 }}><option value="morning">🌅 早晨</option><option value="noon">☀️ 中午</option><option value="evening">🌙 晚上</option></select></div>
                <div className="body-field"><label>💓 收缩压 (mmHg)</label><input type="number" min="0" placeholder="120" value={editingBlood?.sbp ?? ""} onChange={(e) => setEditingBlood((p) => ({ ...p, sbp: parseFloat(e.target.value) || "" }))} /></div>
                <div className="body-field"><label>💗 舒张压 (mmHg)</label><input type="number" min="0" placeholder="80" value={editingBlood?.dbp ?? ""} onChange={(e) => setEditingBlood((p) => ({ ...p, dbp: parseFloat(e.target.value) || "" }))} /></div>
                <div className="body-field"><label>🍬 血糖 (mmol/L)</label><input type="number" step="0.1" min="0" placeholder="5.6" value={editingBlood?.glucose ?? ""} onChange={(e) => setEditingBlood((p) => ({ ...p, glucose: parseFloat(e.target.value) || "" }))} /></div>
                <div className="body-field"><label>💓 心率 (bpm)</label><input type="number" min="0" placeholder="72" value={editingBlood?.heartRate ?? ""} onChange={(e) => setEditingBlood((p) => ({ ...p, heartRate: parseInt(e.target.value) || "" }))} /></div>
              </div>
            </div>
            <div className="body-form-actions"><button className="body-save-btn" onClick={saveBloodRecord}>💾 保存记录</button></div>
            {bloodRecords.length > 0 && (
              <div className="body-history">
                <div className="body-cat-title">📅 历史记录</div>
                {bloodRecords.map((rec, i) => {
                  const bpLabel = rec.sbp && rec.dbp ? `${rec.sbp}/${rec.dbp}mmHg` : "";
                  return (
                    <div key={i} className="body-history-item">
                      <span className="body-history-date">{rec.date} {timeLabel[rec.time] || ""}</span>
                      <span className="body-history-summary">{[bpLabel, rec.glucose && `🍬${rec.glucose}`, rec.heartRate && `💓${rec.heartRate}`].filter(Boolean).join(" · ")}</span>
                      <button className="body-history-load" onClick={() => setEditingBlood({ ...rec })}>✏️</button>
                      <button className="body-history-del" onClick={() => deleteBloodRecord(i)}>🗑️</button>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
