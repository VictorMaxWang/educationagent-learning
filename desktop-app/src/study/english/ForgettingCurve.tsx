import { useEffect, useState } from "react";
import { maimemoReviewStats } from "../../request";

type Stats = {
  code?: number;
  total?: number;
  mastered?: number;
  byLevel?: number[];
  due?: number[];
  dueLabels?: string[];
};

/** 理论遗忘曲线：记忆保持率随天数的指数衰减（τ=20，约 30 天剩 22%） */
const TAU = 20;
const MAX_DAYS = 60;
/** 累计复习日（间隔 1,2,4,7,15,30 → 累计 1,3,7,14,29） */
const REVIEW_POINTS = [1, 3, 7, 14, 29];

/**
 * 📉 艾宾浩斯遗忘曲线展示
 *  左：理论曲线（指数衰减 + 复习点加固标记）
 *  右：我的复习排期（各时间窗到期词数，来自 /review-stats）
 */
export default function ForgettingCurve() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    maimemoReviewStats()
      .then((res) => {
        if (res?.code === 200) setStats(res);
      })
      .catch(() => {});
  }, []);

  // SVG 坐标映射
  const W = 560;
  const H = 190;
  const PAD = 34;
  const sx = (d: number) => PAD + (d / MAX_DAYS) * (W - PAD * 2);
  const sy = (r: number) => H - PAD - (r / 100) * (H - PAD * 2.2);

  const pts: { d: number; r: number }[] = [];
  for (let d = 0; d <= MAX_DAYS; d += 1) {
    pts.push({ d, r: 100 * Math.exp(-d / TAU) });
  }
  const path = pts.map((p, i) => `${i === 0 ? "M" : "L"}${sx(p.d).toFixed(1)},${sy(p.r).toFixed(1)}`).join(" ");

  const due = stats?.due ?? [];
  const labels = stats?.dueLabels ?? [];
  const maxDue = Math.max(1, ...due);

  return (
    <div className="fc-wrap">
      <div className="fc-header" onClick={() => setOpen((v) => !v)}>
        <span>📉 艾宾浩斯遗忘曲线</span>
        <span className="fc-toggle">{open ? "收起 ▴" : "展开 ▾"}</span>
      </div>

      {open && (
        <div className="fc-body">
          {/* 理论遗忘曲线 */}
          <div className="fc-panel">
            <div className="fc-panel-title">📈 理论遗忘曲线（不复习会怎样）</div>
            <svg viewBox={`0 0 ${W} ${H}`} className="fc-svg">
              {/* Y 轴网格 */}
              {[0, 25, 50, 75, 100].map((r) => (
                <g key={r}>
                  <line x1={PAD} y1={sy(r)} x2={W - PAD} y2={sy(r)} stroke="var(--border)" strokeWidth="1" />
                  <text x={PAD - 6} y={sy(r) + 3} textAnchor="end" fontSize="10" fill="var(--text-faint)">
                    {r}%
                  </text>
                </g>
              ))}
              {/* 复习点：虚线 + 标注 */}
              {REVIEW_POINTS.map((d) => (
                <g key={d}>
                  <line
                    x1={sx(d)}
                    y1={PAD}
                    x2={sx(d)}
                    y2={H - PAD}
                    stroke="var(--primary)"
                    strokeWidth="1"
                    strokeDasharray="4 3"
                    opacity="0.6"
                  />
                  <text x={sx(d)} y={PAD - 6} textAnchor="middle" fontSize="10" fill="var(--primary)">
                    {d}天
                  </text>
                </g>
              ))}
              {/* 衰减曲线 */}
              <path d={path} fill="none" stroke="var(--text)" strokeWidth="2.5" />
              <text
                x={sx(50)}
                y={sy(100 * Math.exp(-50 / TAU)) - 8}
                fontSize="10"
                fill="var(--text-muted)"
              >
                不复习：记忆快速衰减
              </text>
            </svg>
            <div className="fc-tip">
              复习间隔 [1,2,4,7,15,30,60,90] 天：每次复习后记忆被重新加固，衰减变缓 → 间隔越拉越长
            </div>
          </div>

          {/* 我的复习排期 */}
          <div className="fc-panel">
            <div className="fc-panel-title">🗓 我的复习排期（当前词库）</div>
            {stats ? (
              <>
                <div className="fc-stats">
                  <span>
                    总词数 <b>{stats.total ?? 0}</b>
                  </span>
                  <span>
                    已熟练 <b className="fc-ok">{stats.mastered ?? 0}</b>
                  </span>
                  <span>
                    待复习 <b>{(stats.total ?? 0) - (stats.mastered ?? 0)}</b>
                  </span>
                </div>
                <div className="fc-bars">
                  {due.map((c, i) => (
                    <div key={i} className="fc-bar-row">
                      <span className="fc-bar-label">{labels[i] ?? i}</span>
                      <div className="fc-bar-track">
                        <div className="fc-bar-fill" style={{ width: `${(c / maxDue) * 100}%` }} />
                      </div>
                      <span className="fc-bar-count">{c}</span>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <div className="fc-empty">加载排期中…</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
