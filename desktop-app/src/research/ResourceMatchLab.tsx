import { useMemo, useState } from 'react';
import { DEFAULT_RESOURCE_WEIGHTS, EXAMPLE_RESOURCES, KNOWLEDGE_TOPICS, evaluateResources } from './models/resourceLab';
import { LabQuestion, LabSource, RangeControl } from './LabParts';

const LABELS = ['知识点差异', '难度差异', '超出时间', '覆盖缺失惩罚', '分布不均'] as const;
const UNITS = ['个 / 资源', '刻度', '分钟', '缺失数 × 100', '平方差和'] as const;

export default function ResourceMatchLab() {
  const [selected, setSelected] = useState([true, false, true, false, false, false]);
  const [goals, setGoals] = useState<boolean[]>(KNOWLEDGE_TOPICS.map(() => true));
  const [ability, setAbility] = useState(2.5);
  const [budget, setBudget] = useState(25);
  const [weights, setWeights] = useState<number[]>([...DEFAULT_RESOURCE_WEIGHTS]);
  const evaluation = useMemo(() => evaluateResources({ resources: EXAMPLE_RESOURCES, selected, goals,
    ability, timeBudgetMinutes: budget, weights }), [selected, goals, ability, budget, weights]);
  const maximumCount = Math.max(1, ...evaluation.counts);
  const largestContribution = evaluation.feasible ? Math.max(1, ...evaluation.weighted) : 1;
  const updateWeight = (index: number, value: string) => {
    const number = Number(value);
    if (!Number.isFinite(number)) return;
    setWeights(previous => previous.map((weight, at) => at === index ? Math.max(0, Math.min(10, number)) : weight));
  };

  return <article className="tr-experiment">
    <LabQuestion number="03" title="学习资料越多，就一定越合适吗？">自己设定能力刻度、时间和学习目标，再选择资料，看看五项代价怎样相互影响。</LabQuestion>
    <p className="tr-data-note"><span aria-hidden="true">◇</span> 全部资料和数值都是人工教学示例；你的设置只用于本页计算，不是学生画像或能力测评。</p>
    <div className="tr-controls-grid tr-resource-settings">
      <RangeControl label="自设能力刻度" min={1} max={5} step={0.5} value={ability} display={`${ability.toFixed(1)} / 5`} onChange={setAbility} />
      <RangeControl label="可接受学习时间" min={5} max={70} step={5} value={budget} display={`${budget} 分钟`} onChange={setBudget} />
      <fieldset className="tr-goals"><legend>我想练习的知识点</legend><div>{KNOWLEDGE_TOPICS.map((topic, index) => <label key={topic}>
        <input type="checkbox" checked={goals[index]} onChange={() => setGoals(previous => previous.map((value, at) => at === index ? !value : value))} />{topic}
      </label>)}</div></fieldset>
    </div>
    <div className="tr-panel">
      <div className="tr-panel-heading"><h3>搭配你的学习资料</h3><span>已选 {evaluation.selectedCount} 项 · 共 {evaluation.totalMinutes} 分钟</span></div>
      <div className="tr-resource-grid">{EXAMPLE_RESOURCES.map((resource, index) => <label key={resource.id} className={`tr-resource-card ${selected[index] ? 'is-selected' : ''}`}>
        <span className="tr-resource-title"><input type="checkbox" checked={selected[index]} onChange={() => setSelected(previous => previous.map((value, at) => at === index ? !value : value))} /><strong>{resource.title}</strong><span>{resource.minutes} 分钟</span></span>
        <span className="tr-resource-difficulty">难度 {resource.difficulty} / 5</span>
        <span className="tr-resource-topics">{KNOWLEDGE_TOPICS.map((topic, at) => <span key={topic} className={resource.knowledge[at] ? 'is-covered' : ''}>{resource.knowledge[at] ? '✓ ' : ''}{topic}</span>)}</span>
      </label>)}</div>
      <div className="tr-actions"><button onClick={() => setSelected(EXAMPLE_RESOURCES.map(() => true))}>全选比较一下</button><button onClick={() => setSelected(EXAMPLE_RESOURCES.map(() => false))}>清空选择</button><button onClick={() => { setSelected([true, false, true, false, false, false]); setWeights([...DEFAULT_RESOURCE_WEIGHTS]); }}>恢复示例选择与权重</button></div>
    </div>
    <div className="tr-resource-results">
      <div className="tr-panel">
        <div className="tr-panel-heading"><h3>五项代价与权重</h3><span>权重不会自动归一化</span></div>
        <div className="tr-score-header"><span>评价项</span><span>原始值</span><span>权重</span><span>加权后</span></div>
        {LABELS.map((label, index) => <div className="tr-score-row" key={label}>
          <div><strong>f{index + 1} · {label}</strong><small>{UNITS[index]}</small></div>
          <output>{evaluation.feasible ? evaluation.values[index].toFixed(index === 3 ? 0 : 2) : '—'}</output>
          <input type="number" min={0} max={10} step={0.01} value={weights[index]} aria-label={`${label}的权重`} onChange={event => updateWeight(index, event.currentTarget.value)} />
          <output>{evaluation.feasible ? evaluation.weighted[index].toFixed(2) : '—'}</output>
          <span className="tr-contribution" aria-hidden="true"><i style={{ width: `${evaluation.feasible ? evaluation.weighted[index] / largestContribution * 100 : 0}%` }} /></span>
        </div>)}
        <div className="tr-total" aria-live="polite"><div><span>加权总评价值</span><small>在同一套规则下，值越小表示代价越小</small></div><strong>{evaluation.feasible ? evaluation.total.toFixed(2) : '—'}</strong></div>
        {!evaluation.feasible && <p className="tr-observation" role="status">{evaluation.reason}</p>}
        {weights.every(weight => weight === 0) && <p className="tr-muted">五个权重都为0时，合计无法区分不同选择。</p>}
      </div>
      <div className="tr-panel tr-coverage">
        <div className="tr-panel-heading"><h3>知识点出现了几次？</h3><span>{evaluation.coveredCount} / {goals.length} 已覆盖</span></div>
        {KNOWLEDGE_TOPICS.map((topic, index) => <div className="tr-coverage-row" key={topic}><span>{topic}</span><div><i style={{ width: `${evaluation.counts[index] / maximumCount * 100}%` }} /></div><strong>{evaluation.counts[index]}</strong></div>)}
        <p>目标勾选影响 f1。按论文原式，f4 与 f5 仍统计这里展示的全部 {goals.length} 个知识点。</p>
        <div className="tr-observation"><strong>试着权衡</strong><p>把所有资料都选上，覆盖可能更完整，但学习时间也会增加。提高“超出时间”的权重，再观察总评价值。</p></div>
      </div>
    </div>
    <LabSource><p><strong>P017</strong>《一种基于改进鸽群算法的个性化学习资源推荐方法》，PDF 第 2–3 页式 (1)–(6)。本页只计算五项评价与加权和，资源选择由你完成。</p>
      <p>令 K 为所选资源数，M 为全部知识点数，cₘ 为知识点 m 在所选资源中的出现次数，C 为 cₘ &gt; 0 的知识点数；b、g 为资源覆盖与目标的 0/1 值。</p>
      <div className="tr-formula"><p>f1 = Σ所选资源 Σ知识点 |b − g| / K</p><p>f2 = Σ所选资源 |能力 − 难度| / K</p><p>f3 = max(所选分钟之和 − 时间上限, 0)</p><p>f4 = 100 × (M − C)</p><p>f5 = Σ全部知识点 (cₘ − Σcₘ / C)²</p><p>合计 = w1·f1 + w2·f2 + w3·f3 + w4·f4 + w5·f5</p></div>
      <p>f4 按原式显示“缺失个数乘100”；f5 的中心除以已覆盖个数 C，但平方差包含未覆盖点。K 或 C 为0时不计算，也不自动补选资源。能力/难度使用相同的 1–5 人工刻度，时间均为分钟；没有引入论文的 MPIO 优化过程或真实学生数据。</p>
    </LabSource>
  </article>;
}
