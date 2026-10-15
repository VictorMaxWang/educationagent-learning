import { useEffect, useMemo, useRef, useState } from 'react';
import { getJson, num, pct } from './opsApi';
import { createMetricsCollector } from './metricsCollector';
import './ops.css';

/**
 * 指标监控面板。
 *
 * 图表决策：
 *   · LLM 耗时 / token / 调用次数 → **统计磁贴**（单值不画图）。
 *   · 工具调用排行 → **同色横向条**（标称类别，条长已编码数值）。
 *   · 浏览器 FPS / 堆内存 / 事件循环延迟 → **迷你折线**，各是单序列，
 *     所以不需要图例（标题即命名）。
 *
 * 关于 CPU：浏览器沙箱读不到进程 CPU，这里用事件循环延迟作为代理，
 * 并在界面上如实说明，而不是编一个百分比出来。
 */
export default function MetricsView() {
  const [snap, setSnap] = useState(null);
  const [msg, setMsg] = useState('');
  const [asTable, setAsTable] = useState(false);
  const collectorRef = useRef(null);
  const [browser, setBrowser] = useState(null);
  const [browserSeries, setBrowserSeries] = useState([]);

  async function load() {
    const res = await getJson('/api/metrics/snapshot');
    if (res.ok) {
      setSnap(res.data);
      setMsg('');
    } else {
      setMsg(`⚠️ ${res.msg}`);
    }
  }

  useEffect(() => {
    load();
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    const collector = createMetricsCollector();
    collectorRef.current = collector;
    const unsub = collector.subscribe(() => {
      setBrowserSeries(collector.getSamples());
      setBrowser(collector.summarize());
    });
    collector.start();
    return () => {
      unsub();
      collector.stop();
    };
  }, []);

  const byTool = useMemo(() => {
    const raw = snap?.tools?.byTool || {};
    return Object.entries(raw).sort((a, b) => Number(b[1]) - Number(a[1]));
  }, [snap]);
  const maxTool = byTool.length ? Math.max(...byTool.map((p) => Number(p[1]))) : 1;

  const llm = snap?.llm || {};
  const router = snap?.router || {};
  const memory = snap?.memory || {};
  const store = snap?.memoryStore || {};
  const jvm = snap?.jvm || {};

  return (
    <div className="quiz-container">
      <div className="ops-root">
        <div className="ops-panel">
          <div className="ops-head">
            <h2 className="ops-title">📊 指标监控</h2>
            <span className="ops-sub">后端 /api/metrics/snapshot ＋ 浏览器本地采集（1s）</span>
          </div>
          {msg && <div className="ops-msg is-error">{msg}</div>}

          <div className="ops-tiles">
            <Tile label="LLM 调用" value={num(llm.calls)} hint={`失败 ${num(llm.failures)}`} />
            <Tile label="累计 token" value={num(llm.tokensTotal)} hint="输入＋输出" />
            <Tile label="LLM 平均耗时" value={num(llm.avgLatencyMs)} unit="ms" hint={`P95 ${num(llm.p95LatencyMs)} ms`} />
            <Tile label="工具调用" value={num(snap?.tools?.calls)} hint={`失败 ${num(snap?.tools?.failures)}`} />
            <Tile
              label="路由命中率"
              value={pct(router.hitRate)}
              tone={router.hitRate >= 0.85 ? 'good' : router.hitRate < 0.6 ? 'bad' : null}
              hint={`${num(router.hits)} 命中 / ${num(router.fallbacks)} 兜底`}
            />
          </div>

          <div className="ops-tiles">
            <Tile label="记忆检索" value={num(memory.searches)} hint={`命中 ${num(memory.hits)} 次`} />
            <Tile label="记忆条数" value={num(store.totalMemories)} hint={`${num(store.totalUsers)} 个用户`} />
            <Tile label="记忆库可用" value={store.usable ? '正常' : '不可用'} tone={store.usable ? 'good' : 'bad'} hint={store.indexAvailable ? '索引已加载' : '索引未加载'} />
            <Tile label="堆内存" value={num(jvm.heapUsedMb)} unit="MB" hint={`上限 ${num(jvm.heapMaxMb)} MB`} />
            <Tile label="进程运行" value={num(snap?.process?.uptimeMinutes)} unit="分钟" hint={`线程 ${num(jvm.threadCount)}`} />
          </div>
        </div>

        <div className="ops-panel">
          <div className="ops-head">
            <h3 className="ops-title">工具调用排行</h3>
            <button className="ops-toggle" onClick={() => setAsTable((v) => !v)}>
              {asTable ? '看图表' : '看表格'}
            </button>
          </div>
          {byTool.length === 0 && <div className="ops-msg">还没有工具调用记录。</div>}

          {!asTable && byTool.length > 0 && (
            <div className="ops-bars">
              {byTool.slice(0, 12).map(([name, count]) => (
                <div className="ops-bar-row" key={name}>
                  <span className="ops-bar-name" title={name}>
                    {name}
                  </span>
                  <span className="ops-bar-track">
                    <span
                      className="ops-bar-fill"
                      style={{ width: `${Math.max(2, (Number(count) / maxTool) * 100)}%` }}
                    />
                  </span>
                  <span className="ops-bar-value">{count}</span>
                </div>
              ))}
            </div>
          )}

          {asTable && byTool.length > 0 && (
            <table className="ops-table">
              <thead>
                <tr>
                  <th>工具</th>
                  <th style={{ textAlign: 'right' }}>调用次数</th>
                </tr>
              </thead>
              <tbody>
                {byTool.map(([name, count]) => (
                  <tr key={name}>
                    <td>{name}</td>
                    <td className="num">{count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* ---------- 浏览器侧采集 ---------- */}
        <div className="ops-panel">
          <div className="ops-head">
            <h3 className="ops-title">浏览器运行时</h3>
            <span className="ops-sub">采集于本机 WebView，1s 一次，保留最近 60 个采样</span>
          </div>

          <div className="ops-tiles">
            <Tile label="FPS" value={num(browser?.fps)} hint={`均值 ${num(browser?.fpsAvg)}`} />
            <Tile
              label="JS 堆内存"
              value={browser?.heapMb == null ? '不支持' : num(browser?.heapMb)}
              unit={browser?.heapMb == null ? undefined : 'MB'}
              hint={browser?.heapMb == null ? '当前内核不暴露 performance.memory' : 'WebView 堆占用'}
            />
            <Tile
              label="事件循环延迟"
              value={num(browser?.lagMs)}
              unit="ms"
              tone={browser?.lagMs != null && browser.lagMs > 100 ? 'bad' : null}
              hint="主线程阻塞程度（CPU 代理指标）"
            />
            <Tile label="采样点" value={num(browser?.samples)} hint="不足 2 点时画不出线" />
          </div>

          {browserSeries.length >= 2 ? (
            <>
              <MiniLine
                label="FPS"
                unit=""
                points={browserSeries.map((s) => s.fps)}
                hint="越高越流畅；60 为满帧"
              />
              {browser?.heapMb != null && (
                <MiniLine
                  label="JS 堆内存"
                  unit="MB"
                  points={browserSeries.map((s) => s.heapMb)}
                  hint="持续上升且不回落，可能存在泄漏"
                />
              )}
              <MiniLine
                label="事件循环延迟"
                unit="ms"
                points={browserSeries.map((s) => s.lagMs)}
                hint="接近 0 表示主线程空闲"
              />
            </>
          ) : (
            <div className="ops-msg">正在采集，稍候…</div>
          )}

          <div className="ops-msg">
            说明：<strong>CPU 占用无法从浏览器沙箱读取</strong>（没有对应 API，WebView2 同样如此），
            因此这里采集「事件循环延迟」作为代理——主线程被占满时定时器回调会被推迟，
            延迟直接升高。它衡量的是"界面卡不卡"，与 FPS 互补（FPS 反映渲染，延迟反映计算）。
          </div>
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

/** 单序列迷你折线（无图例——标题已命名该序列）。 */
function MiniLine({ label, unit, points, hint }) {
  const W = 900;
  const H = 70;
  const PAD = 10;
  const min = Math.min(...points);
  const max = Math.max(...points);
  const span = max - min || 1;
  const sx = (i) => PAD + (i / Math.max(1, points.length - 1)) * (W - PAD * 2);
  const sy = (v) => PAD + (1 - (v - min) / span) * (H - PAD * 2);
  const d = points.map((v, i) => `${i === 0 ? 'M' : 'L'}${sx(i)},${sy(v)}`).join(' ');
  const last = points[points.length - 1];

  return (
    <div>
      <div className="ops-head" style={{ marginBottom: 2 }}>
        <span className="ops-sub">
          {label}：<strong style={{ color: 'var(--text)' }}>{last}</strong>
          {unit ? ` ${unit}` : ''} · 区间 {Math.round(min)}–{Math.round(max)}
          {unit ? ` ${unit}` : ''}
        </span>
        {hint && <span className="ops-sub">{hint}</span>}
      </div>
      <svg
        className="ops-chart-svg"
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`${label} 折线，最近值 ${last}`}
      >
        <line className="ops-baseline" x1={PAD} x2={W - PAD} y1={H - PAD} y2={H - PAD} />
        <path className="ops-line" d={d} />
        <circle className="ops-dot" cx={sx(points.length - 1)} cy={sy(last)} r={3} />
      </svg>
    </div>
  );
}
