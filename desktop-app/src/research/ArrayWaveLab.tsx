import { useId, useMemo, useState } from 'react';
import { amplitudeDb, arrayFactor, gratingLobeAngles, sampleArrayPattern, type ArraySetup } from './models/arrayLab';
import { LabQuestion, LabSource, RangeControl } from './LabParts';

export default function ArrayWaveLab() {
  const [count, setCount] = useState(8);
  const [spacing, setSpacing] = useState(0.5);
  const [steer, setSteer] = useState(0);
  const [observe, setObserve] = useState(30);
  const [enabled, setEnabled] = useState<boolean[]>(Array(8).fill(true));
  const titleId = useId();
  const setup: ArraySetup = { count, spacingRatio: spacing, steerDeg: steer, enabled };
  const normal = useMemo(() => sampleArrayPattern({ count, spacingRatio: spacing, steerDeg: steer, enabled: Array(count).fill(true) }), [count, spacing, steer]);
  const current = useMemo(() => sampleArrayPattern({ count, spacingRatio: spacing, steerDeg: steer, enabled }), [count, spacing, steer, enabled]);
  const active = enabled.filter(Boolean).length;
  const observed = arrayFactor(observe, setup);
  const observedDb = amplitudeDb(observed.magnitude, count);
  const targetDb = amplitudeDb(arrayFactor(steer, setup).magnitude, count);
  const extraPeaks = gratingLobeAngles(setup);
  const x = (angle: number) => 54 + (angle + 90) / 180 * 642;
  const y = (db: number) => 28 + -db / 60 * 224;
  const points = (pattern: typeof normal) => pattern.map(point => `${x(point.angleDeg).toFixed(2)},${y(point.db).toFixed(2)}`).join(' ');
  const preset = (mode: 'normal' | 'middle' | 'edges' | 'off') => setEnabled(Array.from({ length: count }, (_, index) => {
    if (mode === 'off') return false;
    if (mode === 'middle') return index !== Math.floor(count / 2) - 1 && index !== Math.floor(count / 2);
    if (mode === 'edges') return index !== 0 && index !== count - 1;
    return true;
  }));

  return <article className="tr-experiment">
    <LabQuestion number="01" title="一些波会增强，一些波会抵消吗？">改变阵元间距和指向，再关闭一两个阵元，观察同一方向上的波怎样叠加。</LabQuestion>
    <div className="tr-controls-grid">
      <RangeControl label="阵元数量" min={4} max={16} value={count} display={`${count} 个`} onChange={value => { setCount(value); setEnabled(Array(value).fill(true)); }} />
      <RangeControl label="阵元间距 d" min={0.25} max={0.75} step={0.05} value={spacing} display={`${spacing.toFixed(2)} λ`} onChange={setSpacing} />
      <RangeControl label="目标指向 θ₀" min={-60} max={60} step={5} value={steer} display={`${steer}°`} onChange={setSteer} />
      <RangeControl label="观察方向 θ" min={-90} max={90} value={observe} display={`${observe}°`} onChange={setObserve} />
    </div>
    <div className="tr-panel tr-array-panel">
      <div className="tr-panel-heading"><h3>轻点一个阵元，模拟开关或失效</h3><span>{active} / {count} 正在工作</span></div>
      <div className="tr-elements">{enabled.map((on, index) => <button key={index} type="button" aria-pressed={on}
        aria-label={`阵元 ${index + 1}，${on ? '开启，点击关闭' : '关闭，点击开启'}`} className={on ? 'is-on' : 'is-off'}
        onClick={() => setEnabled(previous => previous.map((value, at) => at === index ? !value : value))}>
        <span className="tr-element-symbol" aria-hidden="true">{on ? '●' : '○'}</span><span>{index + 1}</span><small>{on ? '开' : '关'}</small>
      </button>)}</div>
      <div className="tr-actions"><button onClick={() => preset('normal')}>全部正常</button><button onClick={() => preset('middle')}>中间两个失效</button><button onClick={() => preset('edges')}>两端失效</button><button onClick={() => preset('off')}>全部关闭</button></div>
    </div>
    <div className="tr-panel tr-plot-panel">
      <div className="tr-panel-heading"><h3>方向图对照</h3><div className="tr-legend"><span className="is-baseline">完整阵列</span><span className="is-current">当前阵列</span></div></div>
      <svg className="tr-pattern" viewBox="0 0 720 300" role="img" aria-labelledby={titleId}>
        <title id={titleId}>完整阵列与当前阵列的归一化幅度方向图，横轴为角度，纵轴为相对完整阵列的分贝值</title>
        {[-60, -40, -20, 0].map(db => <g key={db}><line x1={54} x2={696} y1={y(db)} y2={y(db)} className="tr-gridline" /><text x={43} y={y(db) + 4} textAnchor="end">{db}</text></g>)}
        {[-90, -60, -30, 0, 30, 60, 90].map(angle => <g key={angle}><line x1={x(angle)} x2={x(angle)} y1={28} y2={252} className="tr-gridline" /><text x={x(angle)} y={272} textAnchor="middle">{angle}°</text></g>)}
        <text x={10} y={14}>dB</text><text x={696} y={295} textAnchor="end">观察角度 θ</text>
        <line x1={x(steer)} x2={x(steer)} y1={28} y2={252} className="tr-target-line" />
        <text x={x(steer)} y={18} textAnchor="middle" className="tr-target-label">目标 {steer}°</text>
        <polyline points={points(normal)} className="tr-baseline-path" />
        <polyline points={points(current)} className="tr-current-path" />
        <line x1={x(observe)} x2={x(observe)} y1={28} y2={252} className="tr-observe-line" />
        <circle cx={x(observe)} cy={y(Math.min(0, observedDb))} r={4.5} className="tr-observe-dot" />
      </svg>
      <p className="tr-plot-note">两条曲线都以完整阵列的最大幅度为 0 dB；绘图下限为 −60 dB。</p>
    </div>
    <div className="tr-stat-grid" aria-live="polite">
      <div><span>在目标方向</span><strong>{active ? `${targetDb.toFixed(1)} dB` : '没有辐射场'}</strong><small>相对于完整 {count} 阵元</small></div>
      <div><span>在观察方向 {observe}°</span><strong>{active ? `${observedDb <= -60 ? '≤ ' : ''}${observedDb.toFixed(1)} dB` : '没有辐射场'}</strong><small>合成幅度 / N = {(observed.magnitude / count).toFixed(3)}</small></div>
      <div><span>试着解释</span><p>{active === 0 ? '全关时合成幅度为0。图上的下限只是显示约定。' : active < count ? '失效后，目标方向变弱；其他方向的相消关系也会改变。' : '先关掉中间两个阵元，看看主峰和旁边的小峰各有什么变化。'}</p></div>
    </div>
    {extraPeaks.length > 0 && <p className="tr-observation">这组间距与扫描角允许在约 {extraPeaks.map(angle => `${angle.toFixed(1)}°`).join('、')} 出现额外强峰。对照完整阵列，区分间距带来的重复峰与阵元失效的影响。</p>}
    <LabSource><p><strong>P013</strong>《Synthesis of Linear and Planar Arrays via Sequential Convex Optimizations》，PDF 第 2 页式 (1)；<strong>CN104901734A</strong> 的幅度、相位与方向图问题提供研究背景。</p>
      <p className="tr-formula">F(θ) = Σ aₙ exp{'{'}j[2π(d/λ)n sinθ + φₙ]{'}'}<br />φₙ = −2π(d/λ)n sinθ₀；n = 0, …, N−1</p>
      <p>本页使用理想、等幅、各向同性线阵，开关对应 aₙ = 1 或 0，忽略互耦。方向图每 0.5° 取样；关闭阵元不会重新优化其他阵元。团队论文与专利中的凸优化、粒子群或 FFT 优化器不在本教学模型内。</p>
    </LabSource>
  </article>;
}
