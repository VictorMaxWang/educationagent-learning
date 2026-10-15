import { useEffect, useMemo, useState } from 'react';
import { getJson, getDesktopUserId, num } from './opsApi';
import './ops.css';

/**
 * 记忆脑图：自由网络布局 + 编号节点 + 分代配色。
 *
 * ── 布局为什么是"一次性力导向"而不是动画模拟 ──────────────
 * 力导向图最容易踩的坑是：物理模拟 + ResizeObserver 会陷入
 * "容器变动 → 重排 → 容器变动"的死循环。这里的做法是把它**算死**：
 *   · 初始位置由节点 id 的哈希决定（不随机），所以每次渲染结果完全一致；
 *   · Fruchterman-Reingold 迭代在 useMemo 里同步跑完，算完就冻结，不做动画；
 *   · SVG 用 viewBox 自适应，不需要测量容器尺寸 —— 因此**没有 ResizeObserver**。
 * 结果既是参考图那种自由散开的网络，又不会抖动。
 *
 * ── 颜色 ──────────────────────────────────────────────
 * NEW / SURVIVOR / OLD 是一条**生命周期序列**，所以用单一色相的序数色阶
 * （浅 → 深 = 年轻 → 稳固），而不是三个任意颜色；PENDING_GC 是状态色。
 * 全部经对比度/CVD 校验，且**永远配文字标签**——身份不靠颜色单独承载。
 */

/** 分代 → 颜色变量。 */
const GEN_COLOR_VAR = {
  OLD: 'var(--ops-gen-old)',
  SURVIVOR: 'var(--ops-gen-survivor)',
  NEW: 'var(--ops-gen-new)',
  PENDING_GC: 'var(--ops-status-critical)',
};

/** 分代 → 图例文案（JVM 分代的说法，参考图口径）。 */
const GEN_LABEL = {
  OLD: '老年代（长期记忆）',
  SURVIVOR: '幸存区（中度记忆）',
  NEW: '新生代（新记忆）',
  PENDING_GC: '即将回收（PENDING_GC）',
};

const LEGEND_ORDER = ['OLD', 'SURVIVOR', 'NEW', 'PENDING_GC'];

const ROOT_COLOR = 'var(--text-muted)';

const VIEW_W = 1000;
const VIEW_H = 760;
const REFRESH_MS = 15000;

/** 力导向迭代轮数。节点多时减少轮数——O(n²) 每轮，101 个节点约 3M 次运算。 */
function iterationsFor(nodeCount) {
  return nodeCount > 150 ? 120 : 320;
}

/**
 * 理想边长 k 的上限。
 *
 * FR 的力平衡半径约等于 {@code 0.79k}，而 {@code k = √(面积/节点数)}——**节点越少 k 越大**。
 * 画布固定 1000×760 时，3 个节点算出 k≈503 → 平衡半径 400，已经超出画布，
 * 于是节点被边界夹住、永远贴在角上（实测 x 只剩 56）。
 * 封顶后 3 节点半径约 318、20 节点铺满画布，两种极端都正常。
 */
const K_MAX = 320;

/** 向心引力系数：防止孤立簇被斥力推到画布外缘。 */
const GRAVITY = 0.02;

/**
 * 斥力系数（标准 FR 是 1.0）。**必须小于 1**。
 *
 * 标准 FR 假设图是稠密的，斥力和引力大致均衡。但记忆脑图是<b>星形</b>拓扑——
 * 根节点连到每一条记忆，记忆之间只有零星几条语义边。20 个节点意味着
 * 190 对斥力 vs 每个节点 1~2 条引力，斥力直接碾压：
 * 实测 REP=1.0 时所有节点被均匀推到画布最外圈，中间空荡，成了"蜘蛛网"；
 * 调到 0.25 才是参考图那种带簇的自由网络。
 */
const REPULSION = 0.25;

/**
 * 一次性力导向布局（Fruchterman-Reingold）。
 *
 * @returns {Map<string, {x:number,y:number,r:number}>} 节点 id → 坐标与绘制半径
 */
function computeForceLayout(nodes, links) {
  const CX = VIEW_W / 2;
  const CY = VIEW_H / 2;
  const n = nodes.length;
  const pos = new Map();
  if (n === 0) return pos;

  // ── 确定性初始位置：按 id 哈希撒在一个圆环上，不随机
  const ring = Math.min(VIEW_W, VIEW_H) * 0.28;
  nodes.forEach((node, i) => {
    if (node.root) {
      pos.set(node.id, { x: CX, y: CY, r: 30, fixed: true });
      return;
    }
    let h = 0;
    const key = String(node.id);
    for (let c = 0; c < key.length; c++) {
      h = (h * 31 + key.charCodeAt(c)) >>> 0;
    }
    const angle = (i / n) * Math.PI * 2 + (h % 100) / 100;
    const radius = ring * (0.75 + (h % 50) / 100);
    pos.set(node.id, {
      x: CX + Math.cos(angle) * radius,
      y: CY + Math.sin(angle) * radius,
      // 下限 10 而不是 6：后端对"从未被检索过"的记忆算出 size=10 → r=5，
      // 那个尺寸既塞不下编号徽章、也太小不好点。下限抬到 10 之后每个节点
      // 都能同时显示编号和关键词标签（尺寸编码在高端依然有效：越常被检索越大）。
      r: Math.max(10, Math.min(30, node.size / 2)),
      fixed: false,
    });
  });

  const ids = nodes.map((x) => x.id);
  const edges = links
    .map((l) => [l.source, l.target])
    .filter(([a, b]) => pos.has(a) && pos.has(b));

  const k = Math.min(Math.sqrt((VIEW_W * VIEW_H) / Math.max(1, n)), K_MAX);
  let temp = Math.min(VIEW_W, VIEW_H) / 8;

  for (let it = 0, rounds = iterationsFor(n); it < rounds; it++) {
    const disp = new Map(ids.map((id) => [id, { x: 0, y: 0 }]));

    // 斥力：所有节点两两相斥（乘 REPULSION，星形拓扑必须调弱，见常量注释）
    for (let i = 0; i < ids.length; i++) {
      for (let j = i + 1; j < ids.length; j++) {
        const a = pos.get(ids[i]);
        const b = pos.get(ids[j]);
        const dx = a.x - b.x;
        const dy = a.y - b.y;
        const d = Math.hypot(dx, dy) || 0.01;
        const f = (REPULSION * k * k) / d;
        const ux = dx / d;
        const uy = dy / d;
        disp.get(ids[i]).x += ux * f;
        disp.get(ids[i]).y += uy * f;
        disp.get(ids[j]).x -= ux * f;
        disp.get(ids[j]).y -= uy * f;
      }
    }

    // 引力：有边的节点互相拉近
    for (const [a, b] of edges) {
      const pa = pos.get(a);
      const pb = pos.get(b);
      const dx = pa.x - pb.x;
      const dy = pa.y - pb.y;
      const d = Math.hypot(dx, dy) || 0.01;
      const f = (d * d) / k;
      const ux = dx / d;
      const uy = dy / d;
      disp.get(a).x -= ux * f;
      disp.get(a).y -= uy * f;
      disp.get(b).x += ux * f;
      disp.get(b).y += uy * f;
    }

    // 按当前温度限幅移动（退火），并夹在画布内
    for (const id of ids) {
      const p = pos.get(id);
      if (p.fixed) continue;
      const d = disp.get(id);
      // 向心引力：没有它的话，孤立簇会被斥力一路推到画布外缘然后被夹住
      d.x += (CX - p.x) * GRAVITY;
      d.y += (CY - p.y) * GRAVITY;
      const dl = Math.hypot(d.x, d.y) || 0.01;
      const lim = Math.min(dl, temp);
      p.x += (d.x / dl) * lim;
      p.y += (d.y / dl) * lim;
      p.x = Math.max(56, Math.min(VIEW_W - 56, p.x));
      p.y = Math.max(56, Math.min(VIEW_H - 56, p.y));
    }
    temp *= 0.98;
  }
  return pos;
}

export default function MemoryMapView() {
  const defaultUserId = getDesktopUserId();
  const [inputUserId, setInputUserId] = useState(defaultUserId);
  const [userId, setUserId] = useState(defaultUserId);
  const [graph, setGraph] = useState(null);
  const [msg, setMsg] = useState('');
  const [picked, setPicked] = useState(null);
  const [asTable, setAsTable] = useState(false);

  async function load(targetUserId = userId) {
    if (!targetUserId) {
      setMsg('⚠️ 请先填用户 ID');
      return;
    }
    const res = await getJson(`/api/memory/graph?userId=${encodeURIComponent(targetUserId)}`);
    if (res.ok) {
      setGraph(res.data);
      setMsg('');
    } else {
      setMsg(`⚠️ ${res.msg}`);
    }
  }

  useEffect(() => {
    load(userId);
    const id = setInterval(() => load(userId), REFRESH_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  const nodes = graph?.nodes || [];
  const links = graph?.links || [];
  const memories = useMemo(() => nodes.filter((n) => !n.root), [nodes]);

  // 编号稳定：按 id 排序后编号，避免每次轮询编号乱跳
  const ordered = useMemo(
    () => [...memories].sort((a, b) => String(a.id).localeCompare(String(b.id))),
    [memories],
  );

  const layout = useMemo(() => computeForceLayout(nodes, links), [nodes, links]);

  const genCounts = useMemo(() => {
    const out = {};
    for (const g of LEGEND_ORDER) out[g] = 0;
    for (const n of memories) {
      if (out[n.gen] !== undefined) out[n.gen] += 1;
    }
    return out;
  }, [memories]);

  const indexOf = useMemo(() => {
    const m = new Map();
    ordered.forEach((n, i) => m.set(n.id, i + 1));
    return m;
  }, [ordered]);

  return (
    <div className="quiz-container">
      <div className="ops-root">
        <div className="ops-panel">
          <div className="ops-head">
            <h2 className="ops-title">🧠 记忆脑图</h2>
            <span className="ops-sub">
              节点大小 = 访问频率 × 重要度；输入用户 ID 加载脑图
            </span>
          </div>

          <div className="ops-row">
            <input
              className="ops-input"
              value={inputUserId}
              placeholder="输入用户 ID（如 user_xxxxxxxx）"
              onChange={(e) => setInputUserId(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') setUserId(inputUserId.trim());
              }}
            />
            <button className="ops-btn is-primary" onClick={() => setUserId(inputUserId.trim())}>
              加载脑图
            </button>
            <button className="ops-toggle" onClick={() => load()}>
              刷新
            </button>
            <button className="ops-toggle" onClick={() => setAsTable((v) => !v)}>
              {asTable ? '看脑图' : '看表格'}
            </button>
          </div>

          {msg && <div className="ops-msg is-error">{msg}</div>}
        </div>

        <div className="ops-panel">
          <div className="ops-row">
            <span className="ops-pill">
              {num(memories.length)} 节点 · {num(links.length)} 边 · 记忆{' '}
              {num(graph?.stats?.totalCount ?? memories.length)} 条
            </span>
            {graph?.truncated && <span className="ops-pill is-warn">已按节点上限截断</span>}
            <span className="ops-msg">节点越大＝访问越频繁；越靠外环＝越接近被回收</span>
          </div>

          {memories.length === 0 && (
            <div className="ops-msg">
              还没有记忆。先到对话页说一句话——规则认得出的（「我叫张三」）会存成干净事实，
              认不出的会按<b>埋点全量捕获</b>存成原句。
              <br />
              注意本地记忆库需要 <code>memory.integration.enabled</code> 打开才会写入。
            </div>
          )}

          {!asTable && memories.length > 0 && (
            <div className="ops-graph-wrap">
              <svg
                className="ops-graph-svg"
                viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                preserveAspectRatio="xMidYMid meet"
                role="img"
                aria-label={`记忆脑图，共 ${memories.length} 个记忆节点`}
              >
                {/* 连线：归属边实线中性色，语义边虚线序列色 */}
                <g>
                  {links.map((l, i) => {
                    const a = layout.get(l.source);
                    const b = layout.get(l.target);
                    if (!a || !b) return null;
                    return (
                      <line
                        key={i}
                        className={
                          l.type === 'SIMILARITY'
                            ? 'ops-link is-similarity'
                            : 'ops-link is-hierarchy'
                        }
                        x1={a.x}
                        y1={a.y}
                        x2={b.x}
                        y2={b.y}
                        strokeWidth={l.weight > 0.85 ? 1.6 : 1}
                      />
                    );
                  })}
                </g>

                {/* 节点 */}
                <g>
                  {nodes.map((n) => {
                    const p = layout.get(n.id);
                    if (!p) return null;
                    const color = n.root
                      ? ROOT_COLOR
                      : GEN_COLOR_VAR[n.gen] || 'var(--ops-gen-new)';
                    const active = picked?.id === n.id;
                    const idx = indexOf.get(n.id);
                    return (
                      <g
                        key={n.id}
                        className="ops-node"
                        opacity={picked && !active ? 0.35 : 1}
                        onClick={() => setPicked(n)}
                      >
                        {/* ⚠️ 填充必须用 style 而不是 fill 属性。
                            SVG 里 CSS 规则的优先级**高于表现属性**（表现属性相当于特异性 0），
                            所以只要样式表里任何一条规则碰到过这个 class，
                            `fill="..."` 就会被静默覆盖成 fill:none，圆在白底上直接隐形 ——
                            现象是"连线和标签都在，就是看不见圆"。样式走 style 就必胜
                            （实测即使类里强行写 fill:none 也照样生效）。 */}
                        <circle
                          cx={p.x}
                          cy={p.y}
                          r={p.r + (active ? 3 : 0)}
                          style={{ fill: color }}
                          className="ops-node-ring"
                        />
                        <title>
                          {n.fullContent || n.label}
                          {n.keywords ? `\n关键词：${n.keywords.replace(/,/g, ' · ')}` : ''}
                          {`\n分代：${n.gen}｜类型：${n.type}｜重要度：${n.importance}｜访问：${n.accessCount} 次｜年龄：${n.ageDays} 天`}
                        </title>
                        {idx && p.r >= 10 && (
                          <text className="ops-node-idx" x={p.x} y={p.y + 3.5} textAnchor="middle">
                            {idx}
                          </text>
                        )}
                        {/* 标签**无条件**画出来。
                            之前加了 `p.r >= 11` 的门槛，但后端算出的节点半径普遍是 6，
                            结果所有记忆的标签都被吞掉、只能点开才看得到——与"扫一眼就知道
                            自己记了些什么"的目的正好相反。重叠靠下面 CSS 的白色描边压住。 */}
                        <text
                          className="ops-node-label"
                          x={p.x}
                          y={p.y + p.r + 12}
                          textAnchor="middle"
                        >
                          {n.root ? n.label || '用户画像' : truncate(n.label, 10)}
                        </text>
                      </g>
                    );
                  })}
                </g>
              </svg>

              {/* 图例浮层：身份不能只靠颜色，色块必须配文字 */}
              <div className="ops-legend-float">
                <div className="ops-legend-item">
                  <span className="ops-swatch" style={{ background: ROOT_COLOR }} />
                  用户画像（根）
                </div>
                {LEGEND_ORDER.map((g) => (
                  <div className="ops-legend-item" key={g}>
                    <span className="ops-swatch" style={{ background: GEN_COLOR_VAR[g] }} />
                    {GEN_LABEL[g]}（{num(genCounts[g])}）
                  </div>
                ))}
                <div className="ops-legend-item">
                  <span className="ops-swatch is-line" />
                  归属边
                </div>
                <div className="ops-legend-item">
                  <span className="ops-swatch is-line is-similarity" />
                  语义近邻
                </div>
              </div>

              {picked && (
                <div className="ops-graph-tip">
                  <div>
                    <strong>
                      {picked.root ? `${picked.label || '用户画像'}（根节点）` : picked.label}
                    </strong>
                  </div>
                  {!picked.root && (
                    <>
                      <div className="ops-tip-meta">{picked.fullContent}</div>
                      {picked.keywords && (
                        <div className="ops-tip-meta ops-kw">
                          关键词 {(picked.keywords || '').replace(/,/g, ' · ')}
                        </div>
                      )}
                      <div className="ops-tip-meta">
                        分代 {picked.gen} · 类型 {picked.type} · 重要度 {picked.importance}/10
                      </div>
                      <div className="ops-tip-meta">
                        被检索 {picked.accessCount} 次 · 创建于 {picked.ageDays} 天前
                      </div>
                    </>
                  )}
                  <button
                    className="ops-toggle"
                    style={{ marginTop: 6 }}
                    onClick={() => setPicked(null)}
                  >
                    关闭
                  </button>
                </div>
              )}
            </div>
          )}

          {asTable && memories.length > 0 && (
            <table className="ops-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>内容</th>
                  <th>关键词</th>
                  <th>分代</th>
                  <th>类型</th>
                  <th style={{ textAlign: 'right' }}>重要度</th>
                  <th style={{ textAlign: 'right' }}>访问</th>
                  <th style={{ textAlign: 'right' }}>年龄(天)</th>
                </tr>
              </thead>
              <tbody>
                {ordered.map((n, i) => (
                  <tr key={n.id}>
                    <td className="num">{i + 1}</td>
                    <td>{n.fullContent || n.label}</td>
                    <td className="ops-kw">
                      {(n.keywords || '').replace(/,/g, ' · ') || '—'}
                    </td>
                    <td>
                      <span
                        className="ops-swatch"
                        style={{ background: GEN_COLOR_VAR[n.gen], marginRight: 5 }}
                      />
                      {n.gen}
                    </td>
                    <td>{n.type}</td>
                    <td className="num">{n.importance}</td>
                    <td className="num">{n.accessCount}</td>
                    <td className="num">{n.ageDays}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

function truncate(s, n) {
  if (!s) return '';
  return s.length <= n ? s : s.slice(0, n) + '…';
}
