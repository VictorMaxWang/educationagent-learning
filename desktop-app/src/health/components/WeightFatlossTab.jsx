import { useState, useEffect, useMemo } from "react";
import { loadBodyRecords, saveBodyRecords } from "../utils/members.js";

const GOAL_KEY_PREFIX = "newbot_fatloss_goal_";
function loadGoal(memberId) { try { return JSON.parse(localStorage.getItem(GOAL_KEY_PREFIX + memberId)) || null; } catch { return null; } }
function saveGoal(memberId, goal) { try { localStorage.setItem(GOAL_KEY_PREFIX + memberId, JSON.stringify(goal)); } catch {} }

// 图表常量
const DAY_W = 84;        // 每天像素宽度
const VIS_DAYS = 10;     // 可视窗口 10 天
const CHART_H = 380;
const PAD_L = 46, PAD_R = 22, PAD_T = 20, PAD_B = 34;
const PLOT_H = CHART_H - PAD_T - PAD_B;

export default function WeightFatlossTab({ activeMemberId }) {
  const [records, setRecords] = useState(() => loadBodyRecords(activeMemberId));
  const [goal, setGoal] = useState(() => loadGoal(activeMemberId));
  const [goalForm, setGoalForm] = useState({ targetWeight: "", periodDays: "" });
  const [todayWeight, setTodayWeight] = useState("");
  const [tooltip, setTooltip] = useState(null);
  const [testMode, setTestMode] = useState(false);
  const [testDate, setTestDate] = useState(new Date().toISOString().slice(0, 10));
  const [testWeight, setTestWeight] = useState("");

  useEffect(() => {
    setRecords(loadBodyRecords(activeMemberId));
    setGoal(loadGoal(activeMemberId));
    setTodayWeight(""); setGoalForm({ targetWeight: "", periodDays: "" });
  }, [activeMemberId]);

  // ── 全部体重数据 ──
  const allWeightData = useMemo(() =>
    [...records].filter(r => r.weight).map(r => ({ date: r.date, weight: parseFloat(r.weight) }))
      .sort((a, b) => a.date.localeCompare(b.date)), [records]);

  const weightData = allWeightData;

  // ── 图表元数据 ──
  const chartMeta = useMemo(() => {
    if (weightData.length === 0) return null;
    const vals = weightData.map(d => d.weight);
    const minW = Math.floor(Math.min(...vals) - 1);
    const maxW = Math.ceil(Math.max(...vals) + 1);
    const range = maxW - minW || 1;
    const yTicks = []; for (let v = minW; v <= maxW; v++) yTicks.push(v);

    const firstDate = new Date(weightData[0].date + "T12:00:00");
    const lastDate = new Date(weightData[weightData.length - 1].date + "T12:00:00");
    const daySpan = Math.max(1, (lastDate - firstDate) / 86400000);
    const totalDays = Math.ceil(daySpan);

    // ≤10 天：自适应宽度；>10 天：固定日宽+滚动
    const compact = totalDays <= VIS_DAYS;
    const contentW = compact ? (PAD_L + VIS_DAYS * DAY_W + PAD_R) : PAD_L + totalDays * DAY_W + PAD_R;
    const viewW = PAD_L + VIS_DAYS * DAY_W + PAD_R;

    return { minW, maxW, range, yTicks, axisStart: firstDate, daySpan, totalDays, compact, contentW, viewW };
  }, [weightData]);

  // ── 打卡 ──
  const saveTodayWeight = () => {
    const w = parseFloat(todayWeight); if (!w || w <= 0) return;
    const today = new Date().toISOString().slice(0, 10);
    const existing = records.filter(r => r.date !== today);
    setRecords([...existing, { ...records.find(r => r.date === today) || {}, date: today, weight: w }].sort((a, b) => b.date.localeCompare(a.date)));
    saveBodyRecords([...existing, { date: today, weight: w }], activeMemberId);
    setTodayWeight("");
  };

  // ── 减脂目标 ──
  const handleSetGoal = () => {
    const tw = parseFloat(goalForm.targetWeight), pd = parseInt(goalForm.periodDays);
    if (!tw || !pd || tw <= 0 || pd <= 0) return;
    const startWeight = allWeightData.length > 0 ? allWeightData[allWeightData.length - 1].weight : null;
    const g = { targetWeight: tw, periodDays: pd, startWeight, startDate: new Date().toISOString().slice(0, 10), checkins: [] };
    setGoal(g); saveGoal(activeMemberId, g); setGoalForm({ targetWeight: "", periodDays: "" });
  };
  const handleCheckin = () => {
    if (!goal) return;
    const today = new Date().toISOString().slice(0, 10);
    const updated = { ...goal, checkins: [...goal.checkins.filter(c => c.date !== today), { date: today, weight: allWeightData.length > 0 ? allWeightData[allWeightData.length - 1].weight : null }] };
    setGoal(updated); saveGoal(activeMemberId, updated);
  };
  const progress = goal && goal.startWeight ? Math.min(100, Math.max(0, Math.round((goal.startWeight - (allWeightData.length > 0 ? allWeightData[allWeightData.length - 1].weight : goal.startWeight)) / (goal.startWeight - goal.targetWeight) * 100))) : 0;

  // ═══════════ 渲染折线图 ═══════════
  const renderChart = () => {
    if (!chartMeta) return null;
    const { minW, maxW, range, yTicks, axisStart, daySpan, totalDays, compact, contentW, viewW } = chartMeta;
    const hasMultiple = weightData.length >= 2;
    const startMs = axisStart.getTime();

    // 点坐标：紧凑模式按比例，滚动模式按固定日宽
    const points = weightData.map(d => {
      const dayOffset = (new Date(d.date + "T12:00:00").getTime() - startMs) / 86400000;
      const x = compact
        ? PAD_L + (dayOffset / daySpan) * (contentW - PAD_L - PAD_R)
        : PAD_L + dayOffset * DAY_W;
      return { x, y: PAD_T + ((maxW - d.weight) / range) * PLOT_H, date: d.date, weight: d.weight };
    });
    const pathD = hasMultiple ? points.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ") : "";

    // X 轴标签
    let labelInterval = 1;
    if (totalDays > 60) labelInterval = Math.ceil(totalDays / 8);
    else if (totalDays > 14) labelInterval = Math.ceil(totalDays / 7);
    const xLabels = [];
    for (let i = 0; i <= totalDays; i += labelInterval) {
      const d = new Date(axisStart.getTime() + i * 86400000);
      const lx = compact ? PAD_L + (i / daySpan) * (contentW - PAD_L - PAD_R) : PAD_L + i * DAY_W;
      xLabels.push({ x: lx, label: `${d.getMonth() + 1}/${d.getDate()}` });
    }

    const w = compact ? contentW : viewW;
    const svgEl = (
      <svg width={compact ? "100%" : contentW} height={CHART_H}
        viewBox={compact ? `0 0 ${w} ${CHART_H}` : `0 0 ${contentW} ${CHART_H}`}
        style={{ display: "block", ...(compact ? {} : { minWidth: w }) }}>
        {yTicks.map((v, i) => {
          const y = PAD_T + ((maxW - v) / range) * PLOT_H;
          return (
            <g key={i}>
              <line x1={PAD_L} y1={y} x2={contentW - PAD_R} y2={y}
                stroke={v === yTicks[0] || v === yTicks[yTicks.length - 1] ? "#e8dcd4" : "#f4eeea"} strokeWidth="0.8" />
              <text x={PAD_L - 5} y={y + 5} textAnchor="end" fontSize="12" fill="#aaa">{v}</text>
            </g>
          );
        })}
        {hasMultiple && <path d={pathD} fill="none" stroke="#7bc89c" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />}
        {points.map((p, i) => (
          <g key={i}>
            <circle cx={p.x.toFixed(1)} cy={p.y.toFixed(1)} r={hasMultiple ? 4 : 5}
              fill="#fff" stroke="#7bc89c" strokeWidth="2.2"
              style={{ cursor: "pointer" }}
              onMouseEnter={(e) => setTooltip({ x: e.clientX, y: e.clientY, date: p.date, weight: p.weight })}
              onMouseLeave={() => setTooltip(null)} />
            {!hasMultiple && <text x={p.x.toFixed(1)} y={p.y - 8} textAnchor="middle" fontSize="9" fill="#999" fontWeight="600">{p.weight}kg</text>}
          </g>
        ))}
        {/* X 轴底线 */}
        <line x1={PAD_L} y1={CHART_H - PAD_B + 14} x2={contentW - PAD_R} y2={CHART_H - PAD_B + 14} stroke="#e0d8d0" strokeWidth="1" />
        {xLabels.map((xl, i) => (
          <text key={i} x={xl.x} y={CHART_H - 5} textAnchor="middle" fontSize="11" fill="#aaa">{xl.label}</text>
        ))}
      </svg>
    );

    return (
      <div style={{ position: "relative" }}>
        {compact ? svgEl : (
          <div style={{ overflowX: "auto", overflowY: "hidden", borderRadius: 10, width: w, maxWidth: "100%" }}>
            {svgEl}
          </div>
        )}
        {tooltip && (
          <div style={{
            position: "fixed", left: tooltip.x + 12, top: tooltip.y - 36,
            background: "#fff", border: "1px solid #e0d8d0", borderRadius: 8,
            padding: "4px 10px", fontSize: 11, boxShadow: "0 2px 8px rgba(0,0,0,0.08)",
            pointerEvents: "none", zIndex: 200, whiteSpace: "nowrap",
          }}>
            📅 {tooltip.date} · ⚖️ {tooltip.weight}kg
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="feature-content" style={{ padding: 0 }}>
      {/* ═══ 体重趋势 ═══ */}
      <div className="section">
        <h3 className="section-title">📈 体重趋势</h3>
        <div className="body-quick-stats" style={{ marginBottom: 8 }}>
          <div className="body-stat-card">
            <span className="body-stat-icon">⚖️</span>
            <span className="body-stat-val">{allWeightData.length > 0 ? allWeightData[allWeightData.length - 1].weight : "--"}<small>kg</small></span>
            <span className="body-stat-label">最新体重</span>
          </div>
          <div className="body-stat-card">
            <span className="body-stat-icon">📊</span>
            <span className="body-stat-val">{allWeightData.length}<small>条</small></span>
            <span className="body-stat-label">全部记录</span>
          </div>
        </div>

        {/* 测试模式 */}
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
          <label style={{ fontSize: 11, color: "#aaa", cursor: "pointer", display: "flex", alignItems: "center", gap: 4, userSelect: "none" }}
            onClick={() => setTestMode(!testMode)}>
            <span style={{ width: 14, height: 14, borderRadius: 4, border: "1.5px solid #ccc", background: testMode ? "#7bc89c" : "#fff",
              display: "inline-flex", alignItems: "center", justifyContent: "center", fontSize: 10, color: "#fff", transition: "all 0.15s" }}>
              {testMode ? "✓" : ""}
            </span>测试模式
          </label>
        </div>
        {testMode && (
          <div style={{ display: "flex", gap: 8, alignItems: "flex-end", flexWrap: "wrap", marginBottom: 10 }}>
            <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
              <label style={{ fontSize: 11, color: "#999" }}>日期</label>
              <input type="date" value={testDate} onChange={e => setTestDate(e.target.value)}
                style={{ padding: "6px 10px", border: "1px solid #e0d8d0", borderRadius: 8, fontSize: 12, fontFamily: "inherit", background: "#fffdf9" }} />
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
              <label style={{ fontSize: 11, color: "#999" }}>体重 (kg)</label>
              <input type="number" step="0.1" min="0" placeholder="65.0" value={testWeight}
                onChange={e => setTestWeight(e.target.value)}
                style={{ padding: "6px 10px", border: "1px solid #e0d8d0", borderRadius: 8, fontSize: 12, fontFamily: "inherit", width: 90, background: "#fffdf9" }} />
            </div>
            <button onClick={() => {
              const w = parseFloat(testWeight); if (!w || w <= 0) return;
              const existing = records.filter(r => r.date !== testDate);
              const updated = [...existing, { ...records.find(r => r.date === testDate) || {}, date: testDate, weight: w }].sort((a, b) => b.date.localeCompare(a.date));
              setRecords(updated); saveBodyRecords(updated, activeMemberId); setTestWeight("");
            }} style={{ padding: "6px 14px", borderRadius: 8, border: "1px solid #7bc89c", background: "#7bc89c", color: "#fff", fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" }}>+ 录入</button>
          </div>
        )}

        {weightData.length === 0 ? (
          <p className="empty-hint">暂无体重记录，打卡后自动生成趋势图</p>
        ) : weightData.length === 1 ? (
          <>{renderChart()}<p style={{ textAlign: "center", fontSize: 12, color: "#aaa", marginTop: 4 }}>已有1条体重记录，新增记录后展示曲线变化</p></>
        ) : (
          renderChart()
        )}

        {chartMeta && chartMeta.totalDays > VIS_DAYS && (
          <p style={{ textAlign: "center", fontSize: 10, color: "#ccc", marginTop: 2 }}>← 左右滑动查看更多 →</p>
        )}

        {/* 打卡 */}
        <div className="alarm-form" style={{ marginTop: 12 }}>
          <div className="alarm-form-row-split">
            <div className="alarm-form-row" style={{ flex: 1 }}>
              <label>📅 今日体重打卡 (kg)</label>
              <input type="number" step="0.1" min="0" placeholder="如：65.0" value={todayWeight} onChange={e => setTodayWeight(e.target.value)} />
            </div>
            <button className="save-btn-sm" style={{ alignSelf: "flex-end" }} onClick={saveTodayWeight}>💾 打卡</button>
          </div>
        </div>
      </div>

      {/* ═══ 减脂计划 ═══ */}
      <div className="section">
        <h3 className="section-title">🎯 减脂目标计划</h3>
        {!goal ? (
          <div className="alarm-form">
            <div className="alarm-form-row-split">
              <div className="alarm-form-row" style={{ flex: 1 }}>
                <label>🎯 目标体重 (kg)</label>
                <input type="number" step="0.1" min="0" placeholder="如：55" value={goalForm.targetWeight} onChange={e => setGoalForm({ ...goalForm, targetWeight: e.target.value })} />
              </div>
              <div className="alarm-form-row" style={{ flex: 1 }}>
                <label>📅 计划天数</label>
                <input type="number" min="1" placeholder="如：90" value={goalForm.periodDays} onChange={e => setGoalForm({ ...goalForm, periodDays: e.target.value })} />
              </div>
              <button className="save-btn-sm" style={{ alignSelf: "flex-end" }} onClick={handleSetGoal}>✅ 设定目标</button>
            </div>
          </div>
        ) : (
          <div>
            <div className="body-quick-stats" style={{ marginBottom: 10 }}>
              <div className="body-stat-card"><span className="body-stat-icon">🎯</span><span className="body-stat-val">{goal.targetWeight}<small>kg</small></span><span className="body-stat-label">目标体重</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">📅</span><span className="body-stat-val">{goal.periodDays}<small>天</small></span><span className="body-stat-label">计划周期</span></div>
              <div className="body-stat-card"><span className="body-stat-icon">🔥</span><span className="body-stat-val">{progress}<small>%</small></span><span className="body-stat-label">完成进度</span></div>
            </div>
            <div style={{ background: "#f5f0eb", borderRadius: 14, height: 10, overflow: "hidden", marginBottom: 8 }}>
              <div style={{ width: `${progress}%`, height: "100%", background: "linear-gradient(90deg, #f8a4b8, #f08ca0)", borderRadius: 14, transition: "width 0.3s" }} />
            </div>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <span style={{ fontSize: 12, color: "var(--text-muted)" }}>开始: {goal.startDate} · 起始体重: {goal.startWeight || "--"}kg · 打卡 {goal.checkins.length} 次</span>
              <button className="save-btn-sm" onClick={handleCheckin}>📝 今日打卡</button>
            </div>
            {goal.checkins.length > 0 && (
              <div className="body-history" style={{ marginTop: 10 }}>
                <div className="body-cat-title">打卡记录</div>
                {goal.checkins.slice(-10).reverse().map((c, i) => (
                  <div key={i} className="body-history-item"><span className="body-history-date">{c.date}</span><span className="body-history-summary">{c.weight ? `⚖️${c.weight}kg` : "已打卡"}</span></div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
