import { useRef, useState } from 'react';
import ArrayWaveLab from './ArrayWaveLab';
import ImageFilterLab from './ImageFilterLab';
import ResourceMatchLab from './ResourceMatchLab';
import './TeamResearchLabs.css';

const LABS = [
  { id: 'array', label: '阵列波与失效', number: '01', subtitle: '从相位到方向图' },
  { id: 'image', label: '图像滤波', number: '02', subtitle: '噪声、细节与颜色' },
  { id: 'resources', label: '资源搭配评价', number: '03', subtitle: '在五个目标间权衡' },
] as const;
type LabId = typeof LABS[number]['id'];

/** 三个独立浏览器实验；不读取学习档案，不保存用户画像，不请求外部 API。 */
export default function TeamResearchLabs() {
  const [active, setActive] = useState<LabId>('array');
  const tabs = useRef<Record<LabId, HTMLButtonElement | null>>({ array: null, image: null, resources: null });
  return <section className="tr-labs" aria-label="团队成果实验室">
    <div className="tr-labs-inner">
      <header className="tr-labs-header"><div><p className="tr-eyebrow"><span>禾</span> 团队成果实验室</p><h1>动手改变，亲眼发现</h1><p>从一条公式、一张图和一组资料开始，把研究问题变成可以尝试的小实验。</p></div><span className="tr-local-badge">浏览器本地计算</span></header>
      <div className="tr-lab-tabs" role="tablist" aria-label="选择实验">{LABS.map((lab, index) => <button key={lab.id} id={`research-tab-${lab.id}`} role="tab"
        ref={node => { tabs.current[lab.id] = node; }} tabIndex={active === lab.id ? 0 : -1}
        aria-selected={active === lab.id} aria-controls={`research-panel-${lab.id}`} className={active === lab.id ? 'is-active' : ''}
        onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault();
          const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? LABS.length - 1
            : (index + (event.key === 'ArrowRight' ? 1 : -1) + LABS.length) % LABS.length;
          const next = LABS[nextIndex].id;
          setActive(next); tabs.current[next]?.focus();
        }}
        onClick={() => setActive(lab.id)}><span>{lab.number}</span><div><strong>{lab.label}</strong><small>{lab.subtitle}</small></div></button>)}</div>
      <div id="research-panel-array" role="tabpanel" aria-labelledby="research-tab-array" hidden={active !== 'array'}><ArrayWaveLab /></div>
      <div id="research-panel-image" role="tabpanel" aria-labelledby="research-tab-image" hidden={active !== 'image'}><ImageFilterLab /></div>
      <div id="research-panel-resources" role="tabpanel" aria-labelledby="research-tab-resources" hidden={active !== 'resources'}><ResourceMatchLab /></div>
      <footer className="tr-labs-footer">先提出一个猜想，再改变一个条件。把你看见的差异说出来，比只记住结论更有意思。</footer>
    </div>
  </section>;
}
