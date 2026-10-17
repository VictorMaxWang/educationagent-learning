import { useEffect, useState } from 'react';
import { classroomRequest } from '../classroom/api';
import './TechnologyOverview.css';

interface Decision {
  id: string; task: string; technology: string; rationale: string; alternative: string;
  failureBehavior: string; sourceIds: string[]; codeAnchors: string[];
}
interface TechnologyManifest {
  version: string; title: string; summary: string; architecture: string[];
  decisions: Decision[]; boundaries: string[];
  sources: { id: string; title: string; adoption: string; locator: string }[];
  runtime: {
    textModel: { configured: boolean; requestedModel: string; liveVerified: boolean };
    embedding: { provider: string; model: string; dimension: number; liveVerified: boolean };
    algorithmVersion: string; notice: string;
  };
}

/** 技术详情独立于学生学习路径；配置身份的展示不冒充在线模型健康检查。 */
export default function TechnologyOverview() {
  const [manifest, setManifest] = useState<TechnologyManifest>();
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void classroomRequest<TechnologyManifest>('/technology', { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setManifest(value);
    }).catch(() => { if (!controller.signal.aborted) setError('技术说明暂时没有读取成功，请稍后重试。'); });
    return () => controller.abort();
  }, [reload]);
  return <section className="technology-overview" aria-label="技术与参考" tabIndex={0}>
    <header className="technology-hero"><span className="technology-eyebrow">晓陶学伴 · 教学做合一</span><h1>技术与参考</h1><p>{manifest?.summary || '了解课堂如何选择工具、核对来源，以及团队成果怎样进入真实的学习流程。'}</p></header>
    {error && <div className="technology-status" role="alert">{error}<button onClick={() => setReload(value => value + 1)}>重新读取</button></div>}
    {!manifest && !error && <p className="technology-status" role="status">正在读取技术说明…</p>}
    {manifest && <>
      <section aria-labelledby="technology-flow"><h2 id="technology-flow">从学习目标到有依据的帮助</h2><ol className="technology-flow">{manifest.architecture.map((step, index) => <li key={step}><span>{String(index + 1).padStart(2, '0')}</span><strong>{step}</strong></li>)}</ol><p className="technology-note">课堂业务层连接模型、课程引擎和学习记录。视频提供补充资料；表达、互动和自检继续留在课堂中。</p></section>
      <section aria-labelledby="technology-models"><div className="technology-section-heading"><h2 id="technology-models">当前配置身份</h2><span>配置快照 · 未发起在线探测</span></div><div className="technology-models"><article><span>文字语义任务</span><strong>{manifest.runtime.textModel.requestedModel || '未公开'}</strong><p>{manifest.runtime.textModel.configured ? '已读取调用配置' : '当前未配置'}，是否完成具体任务须查看该次真实结果。</p></article><article><span>资源语义表示</span><strong>{manifest.runtime.embedding.model || '未公开'}</strong><p>适配模式 {manifest.runtime.embedding.provider} · {manifest.runtime.embedding.dimension} 维，仅展示模型空间身份。</p></article><article><span>组合选择</span><strong>{manifest.runtime.algorithmVersion}</strong><p>确定性求解、独立校验与差额复用。</p></article></div><p className="technology-note">{manifest.runtime.notice}</p></section>
      <section aria-labelledby="technology-decisions"><h2 id="technology-decisions">每项技术为什么这样选</h2><div className="technology-decisions">{manifest.decisions.map(decision => <article key={decision.id}><span className="technology-eyebrow">{decision.task}</span><h3>{decision.technology}</h3><p>{decision.rationale}</p><div className="technology-sources-inline">{decision.sourceIds.map(id => <a key={id} href={`#technology-source-${id}`} onClick={event => { event.preventDefault(); document.getElementById(`technology-source-${id}`)?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'center' }); }}>{id}</a>)}</div><details><summary>对比方案、失败行为与代码位置</summary><dl><dt>对比方案</dt><dd>{decision.alternative}</dd><dt>失败行为</dt><dd>{decision.failureBehavior}</dd><dt>代码锚点</dt><dd><ul>{decision.codeAnchors.map(anchor => <li key={anchor}><code>{anchor}</code></li>)}</ul></dd></dl></details></article>)}</div></section>
      <section className="technology-boundaries" aria-labelledby="technology-boundaries"><h2 id="technology-boundaries">方法的适用边界</h2><ul>{manifest.boundaries.map(item => <li key={item}>{item}</li>)}</ul></section>
      <section aria-labelledby="technology-references"><h2 id="technology-references">团队成果与采用方式</h2><p className="technology-note">原文、段落截图、具体代码及评测对应关系保存在项目的团队成果参考库。这里展示来源摘要与方法边界。</p><div className="technology-references">{manifest.sources.map(source => <article id={`technology-source-${source.id}`} key={source.id}><div><span>{source.id}</span><small>{source.adoption}</small></div><h3>{source.title}</h3><p>{source.locator}</p></article>)}</div></section>
      <footer className="technology-note">技术清单版本：{manifest.version}</footer>
    </>}
  </section>;
}
