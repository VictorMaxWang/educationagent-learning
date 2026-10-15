import { useEffect, useMemo, useState } from 'react';
import { getJson, postJson, num, pct } from './opsApi';
import './ops.css';

/**
 * 路由评测 Dashboard。
 *
 * 图表决策（按数据各自的"职责"选形，不是凭好看）：
 *   · 命中率 / FALLBACK 率 / 平均耗时 → **统计磁贴**，不是图表。单个数字画成图
 *     只会让读数变难。
 *   · 各决策的分布 → **同色横向条**。类别是标称的（换顺序不改变含义），
 *     而条长已经编码了数值，再按数值上色等于把"身份"这条通道浪费掉。
 *   · 命中率随时间 → **单序列折线** + 一条参考线。单序列不需要图例（标题即命名）。
 * 两个图表都提供"表格"切换：数值必须能不看颜色读到。
 */

const REFRESH_MS = 10000;
const TARGET_HIT_RATE = 0.85; // 方案书验收标准：综合命中率目标

export default function RouterEvalView() {
  const [health, setHealth] = useState(null);
  const [stats, setStats] = useState(null);
  const [recent, setRecent] = useState([]);
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [asTable, setAsTable] = useState(false);

  const [probe, setProbe] = useState('今天北京天气怎么样');
  const [probeResult, setProbeResult] = useState(null);

  async function load() {
    const [h, s, r] = await Promise.all([
      getJson('/api/router/health'),
      getJson('/api/router/stats'),
      getJson('/api/router/recent?limit=60'),
    ]);
    if (h.ok) setHealth(h.data);
    if (s.ok) setStats(s.data);
    if (r.ok && Array.isArray(r.data)) setRecent(r.data);
    const firstErr = [h, s, r].find((x) => !x.ok);
    setMsg(firstErr ? `⚠️ ${firstErr.msg}` : '');
  }

  useEffect(() => {
    load();
    const id = setInterval(load, REFRESH_MS);
    return () => clearInterval(id);
  }, []);

  async function runProbe() {
    if (!probe.trim()) return;
    setBusy(true);
    const res = await postJson('/api/router/route', { message: probe, channel: 'DESKTOP' });
    setProbeResult(res.ok ? res.data : null);
    if (!res.ok) setMsg(`⚠️ ${res.msg}`);
    setBusy(false);
  }

  const byDecision = useMemo(() => toSortedPairs(stats?.byDecision), [stats]);
  const byLayer = useMemo(() => toSortedPairs(stats?.byLayer), [stats]);

  /** 时间序列：把 recent（时间倒序）翻成正序，累计出滚动命中率。 */
  const series = useMemo(() => {
    const chrono = recent.slice().reverse();
    let seen = 0;
    let hit = 0;
    return chrono.map((rec, i) => {
      seen += 1;
      if (rec.decision && rec.decision !== 'FALLBACK') hit += 1;
      return { i, rate: hit / seen, decision: rec.decision };
    });
  }, [recent]);

  const maxDecision = byDecision.length ? Math.max(...byDecision.map((p) => p[1])) : 1;

  return (
    <div className="quiz-container">
      <div className="ops-root">
        <div className="ops-panel">
          <div className="ops-head">
            <h2 className="ops-title">🧭 路由评测</h2>
            <span className="ops-sub">
              {health
                ? `mode=${health.mode} · L1=${health.l1Enabled ? 'on' : 'off'} · L2=${health.l2Enabled ? 'on' : 'off'} · L3=${health.l3Enabled ? 'on' : 'off'}`
                : '加载中…'}
            </span>
          </div>

          {msg && <div className="ops-msg is-error">{msg}</div>}

          <div className="ops-tiles">
            <Tile label="样本量" value={num(stats?.sampleSize)} hint="内存缓冲区最近记录" />
            <Tile
              label="命中率"
              value={pct(stats?.hitRate)}
              tone={rateTone(stats?.hitRate)}
              hint={`目标 ≥ ${pct(TARGET_HIT_RATE, 0)}`}
            />
            <Tile
              label="FALLBACK 率"
              value={pct(stats?.fallbackRate)}
              tone={stats && stats.fallbackRate > 1 - TARGET_HIT_RATE ? 'bad' : null}
              hint="兜底：发全量工具"
            />
            <Tile label="平均耗时" value={num(stats?.avgCostMs, 2)} unit="ms" />
            <Tile label="平均省下工具" value={num(stats?.avgToolsSaved, 1)} unit="个" />
          </div>
        </div>

        <div className="ops-panel">
          <div className="ops-head">
            <h3 className="ops-title">决策分布</h3>
            <button className="ops-toggle" onClick={() => setAsTable((v) => !v)}>
              {asTable ? '看图表' : '看表格'}
            </button>
          </div>

          {byDecision.length === 0 && <div className="ops-msg">还没有路由记录。</div>}

          {!asTable && byDecision.length > 0 && (
            <div className="ops-bars">
              {byDecision.map(([name, count]) => (
                <div className="ops-bar-row" key={name}>
                  <span className="ops-bar-name" title={name}>
                    {name}
                  </span>
                  <span className="ops-bar-track">
                    <span
                      className={`ops-bar-fill${name === 'FALLBACK' ? ' is-status' : ''}`}
                      style={{ width: `${Math.max(2, (count / maxDecision) * 100)}%` }}
                    />
                  </span>
                  <span className="ops-bar-value">{count}</span>
                </div>
              ))}
            </div>
          )}

          {asTable && byDecision.length > 0 && (
            <PairTable
              head={['决策', '次数', '占比']}
              rows={byDecision.map(([name, count]) => [
                name,
                count,
                pct(count / Math.max(1, stats?.sampleSize)),
              ])}
            />
          )}
          <div className="ops-msg">
            同色横条：类别是标称的，条长已编码数值，因此不再按数值上色。
            唯一的例外是 <strong>FALLBACK</strong>——它表示路由未命中，属状态而非类别，故用状态色并配文字标签。
          </div>
        </div>

        <div className="ops-panel">
          <div className="ops-head">
            <h3 className="ops-title">滚动命中率（最近 {series.length} 次判定）</h3>
            <span className="ops-sub">绿虚线 = 目标 {pct(TARGET_HIT_RATE, 0)}</span>
          </div>
          {series.length < 2 ? (
            <div className="ops-msg">样本不足，先跑几条消息再看。</div>
          ) : (
            <HitRateChart series={series} />
          )}
        </div>

        <div className="ops-panel">
          <div className="ops-head">
            <h3 className="ops-title">分意图命中</h3>
            <span className="ops-sub">{byLayer.map(([k, v]) => `${k}:${v}`).join(' · ') || '—'}</span>
          </div>
          <PairTable
            head={['意图', '次数']}
            rows={toSortedPairs(stats?.byIntent).map(([k, v]) => [k || '—', v])}
            empty="还没有识别到意图。"
          />
        </div>

        <div className="ops-panel">
          <div className="ops-head">
            <h3 className="ops-title">试跑一条消息</h3>
            <span className="ops-sub">干跑，不写入评测日志</span>
          </div>
          <div className="ops-toolbar">
            <input
              className="ops-input"
              style={{
                flex: 1,
                minWidth: 240,
                padding: '7px 10px',
                borderRadius: 8,
                border: '1px solid var(--border)',
                background: 'var(--surface)',
                color: 'var(--text)',
                fontSize: 13,
              }}
              value={probe}
              onChange={(e) => setProbe(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && runProbe()}
              placeholder="例如：正当防卫怎么判"
            />
            <button className="btn send-btn" onClick={runProbe} disabled={busy}>
              {busy ? '判定中…' : '试跑'}
            </button>
          </div>

          {probeResult && (
            <PairTable
              head={['字段', '值']}
              rows={[
                ['决策', probeResult.decision],
                ['意图', probeResult.intent || '—'],
                ['命中层', probeResult.layer],
                ['命中的规则', probeResult.matchedRule || '—'],
                ['相似度', num(probeResult.vectorSim, 4)],
                ['耗时', `${num(probeResult.costMs)} ms`],
                [
                  '工具',
                  probeResult.toolsOmitted
                    ? '不过滤（全量）'
                    : (probeResult.tools || []).join(', ') || '0 个（零工具）',
                ],
              ]}
            />
          )}
        </div>
      </div>
    </div>
  );
}

// ======================== 局部组件 ========================

function Tile({ label, value, unit, hint, tone }) {
  const cls = tone === 'good' ? ' is-good' : tone === 'bad' ? ' is-bad' : '';
  return (
    <div className="ops-tile">
      <span className="ops-tile-label">{label}</span>
      <span className={`ops-tile-value${cls}`}>
        {value}
        {unit && <span className="ops-tile-unit">{unit}</span>}
      </span>
      {hint && <span className="ops-tile-hint">{hint}</span>}
    </div>
  );
}

function PairTable({ head, rows, empty }) {
  if (!rows || rows.length === 0) {
    return <div className="ops-msg">{empty || '暂无数据。'}</div>;
  }
  return (
    <table className="ops-table">
      <thead>
        <tr>
          {head.map((h, i) => (
            <th key={h} style={i > 0 ? { textAlign: 'right' } : undefined}>
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r, ri) => (
          <tr key={ri}>
            {r.map((cell, ci) => (
              <td key={ci} className={ci > 0 ? 'num' : undefined}>
                {cell}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** 单序列折线：2px 线、10% 面积、悬停十字准星。 */
function HitRateChart({ series }) {
  const W = 900;
  const H = 180;
  const PAD = { l: 38, r: 12, t: 12, b: 22 };
  const [hover, setHover] = useState(null);

  const innerW = W - PAD.l - PAD.r;
  const innerH = H - PAD.t - PAD.b;
  const sx = (i) => PAD.l + (series.length <= 1 ? innerW / 2 : (i / (series.length - 1)) * innerW);
  const sy = (rate) => PAD.t + (1 - rate) * innerH;

  const path = series.map((p, i) => `${i === 0 ? 'M' : 'L'}${sx(i)},${sy(p.rate)}`).join(' ');
  const area = `${path} L${sx(series.length - 1)},${PAD.t + innerH} L${sx(0)},${PAD.t + innerH} Z`;
  const hovered = hover != null ? series[hover] : null;

  return (
    <>
      <svg
        className="ops-chart-svg"
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`滚动命中率折线，共 ${series.length} 个采样点`}
        onMouseLeave={() => setHover(null)}
        onMouseMove={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          const x = ((e.clientX - rect.left) / rect.width) * W;
          const ratio = (x - PAD.l) / innerW;
          const idx = Math.round(ratio * (series.length - 1));
          setHover(Math.max(0, Math.min(series.length - 1, idx)));
        }}
      >
        {[0, 0.25, 0.5, 0.75, 1].map((t) => (
          <g key={t}>
            <line className="ops-gridline" x1={PAD.l} x2={W - PAD.r} y1={sy(t)} y2={sy(t)} />
            <text className="ops-axis-text" x={PAD.l - 6} y={sy(t) + 3} textAnchor="end">
              {Math.round(t * 100)}%
            </text>
          </g>
        ))}

        <line
          className="ops-ref-line"
          x1={PAD.l}
          x2={W - PAD.r}
          y1={sy(TARGET_HIT_RATE)}
          y2={sy(TARGET_HIT_RATE)}
        />

        <path className="ops-area" d={area} />
        <path className="ops-line" d={path} />

        {hovered && (
          <g>
            <line
              className="ops-gridline"
              x1={sx(hover)}
              x2={sx(hover)}
              y1={PAD.t}
              y2={PAD.t + innerH}
            />
            <circle className="ops-dot" cx={sx(hover)} cy={sy(hovered.rate)} r={4} />
          </g>
        )}
      </svg>
      <div className="ops-msg">
        {hovered
          ? `第 ${hover + 1} 次判定：命中率 ${pct(hovered.rate)}（该次决策 ${hovered.decision}）`
          : '鼠标移到图上查看逐次判定'}
      </div>
    </>
  );
}

// ======================== 工具 ========================

function toSortedPairs(obj) {
  if (!obj || typeof obj !== 'object') return [];
  return Object.entries(obj)
    .filter(([k]) => k && k !== 'null' && k !== 'undefined' && k !== '-')
    .sort((a, b) => Number(b[1]) - Number(a[1]));
}

function rateTone(rate) {
  if (rate === null || rate === undefined) return null;
  return rate >= TARGET_HIT_RATE ? 'good' : rate < TARGET_HIT_RATE * 0.7 ? 'bad' : null;
}
