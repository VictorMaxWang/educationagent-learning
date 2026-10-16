import { useEffect, useMemo, useRef, useState } from 'react';
import './practice-activities.css';

type Mode = 'diagram' | 'simulation' | 'visualization3d' | 'game';
interface Props { mode: Mode; onContextChange?(context: string): void }
const WATER = ['蒸发', '凝结', '降水', '汇流'];
const WATER_HELP = ['太阳让地表的水变成水蒸气，进入空气。', '水蒸气遇冷凝结，形成小水滴或冰晶。', '云中的水滴长大，以雨或雪的形式落下。', '水流经地面和河流回到湖泊与海洋，也有一部分渗入地下。'];

/** 每一步由同一份状态驱动画面和问答上下文，避免操作后仍向学伴发送旧结果。 */
export default function PracticeActivities({ mode, onContextChange }: Props) {
  const [example, setExample] = useState(0);
  const [step, setStep] = useState(0);
  const [parts, setParts] = useState(4);
  const [selected, setSelected] = useState(3);
  const [length, setLength] = useState(5);
  const [width, setWidth] = useState(3);
  const [height, setHeight] = useState(2);
  const [rotation, setRotation] = useState({ x: -.4, y: .6 });
  const [zoom, setZoom] = useState(1);
  const [round, setRound] = useState(0);
  const [score, setScore] = useState(0);
  const [choice, setChoice] = useState<number | null>(null);
  const drag = useRef<{ x: number; y: number } | null>(null);
  useEffect(() => { setExample(0); setStep(0); }, [mode]);
  const rounds = useMemo(() => example === 0 ? [
    { question: '找出与 1/2 相等的分数', options: ['2/4', '1/4', '3/4'], correct: 0, why: '分子、分母同时乘 2，分数的大小不变。' },
    { question: '找出与 2/3 相等的分数', options: ['2/6', '4/6', '5/6'], correct: 1, why: '2/3 的分子、分母同时乘 2，得到 4/6。' },
    { question: '同样大的整体中，哪个分数最大？', options: ['1/4', '1/2', '3/4'], correct: 2, why: '通分为 1/4、2/4、3/4 后，可以直接比较。' },
  ] : [
    { question: '1 米等于多少厘米？', options: ['10 厘米', '100 厘米', '1000 厘米'], correct: 1, why: '米和厘米之间的进率是 100。' },
    { question: '长 4 米、宽 3 米的菜地面积是多少？', options: ['7 平方米', '14 平方米', '12 平方米'], correct: 2, why: '长方形面积＝长×宽，4×3＝12 平方米。' },
    { question: '2 平方米等于多少平方厘米？', options: ['20000 平方厘米', '200 平方厘米', '2000 平方厘米'], correct: 0, why: '1 平方米＝100×100＝10000 平方厘米。' },
  ], [example]);
  const question = rounds[Math.min(round, 2)];
  const summary = mode === 'diagram' ? example === 0 ? `通分图解第${step + 1}步：1/2=2/4，3/4不变，同整体中2/4<3/4。` : `水循环当前环节：${WATER[step % 4]}。${WATER_HELP[step % 4]}`
    : mode === 'simulation' ? example === 0 ? `分数仿真：同一个整体等分${parts}份，选中${selected}份，分数${selected}/${parts}，占比${(100 * selected / parts).toFixed(1)}%。` : `面积仿真：长${length}米，宽${width}米，面积${length * width}平方米。`
    : mode === 'visualization3d' ? `长方体空间探索：长${length}、宽${width}、高${height}，体积${length * width * height}立方单位，旋转角${rotation.x.toFixed(2)}/${rotation.y.toFixed(2)}。`
    : round >= 3 ? `闯关小游戏：${example === 0 ? '分数配对' : '面积与单位'}，已完成3轮，最终得分${score}/3。可以回顾答题理由或重新闯关。`
    : `闯关小游戏：${example === 0 ? '分数配对' : '面积与单位'}，第${round + 1}轮，已得${score}分。当前题：${question.question}。${choice === null ? '尚未作答。' : `本次选择${question.options[choice]}，${choice === question.correct ? '正确' : '需要再想想'}。`}`;
  useEffect(() => { onContextChange?.(summary); }, [summary, onContextChange]);
  const resetGame = () => { setRound(0); setScore(0); setChoice(null); };
  const changeExample = (value: number) => { setExample(value); setStep(0); resetGame(); if (mode === 'visualization3d') { setLength(value ? 3 : 5); setWidth(3); setHeight(value ? 3 : 2); } };
  const names: Record<Mode, string[]> = { diagram: ['通分步骤图', '水循环关系图'], simulation: ['等分与取份', '菜地面积'], visualization3d: ['长方体', '正方体'], game: ['分数配对', '面积与单位'] };
  // 以八个空间顶点投影并按深度画面，不用平面缩放冒充可旋转的三维对象。
  const faces = useMemo(() => {
    const size = Math.max(length, width, height, 1), scale = 180 / size * zoom;
    const points = [[-1,-1,-1],[1,-1,-1],[1,1,-1],[-1,1,-1],[-1,-1,1],[1,-1,1],[1,1,1],[-1,1,1]].map(([a,b,c]) => {
      const x = a * length * scale / 2, y = b * height * scale / 2, z = c * width * scale / 2;
      const x1 = x * Math.cos(rotation.y) + z * Math.sin(rotation.y), z1 = -x * Math.sin(rotation.y) + z * Math.cos(rotation.y);
      const y1 = y * Math.cos(rotation.x) - z1 * Math.sin(rotation.x), z2 = y * Math.sin(rotation.x) + z1 * Math.cos(rotation.x);
      const p = 650 / (650 + z2); return { x: 280 + x1 * p, y: 175 + y1 * p, z: z2 };
    });
    return [[0,1,2,3],[4,5,6,7],[0,4,7,3],[1,5,6,2],[0,1,5,4],[3,2,6,7]].map((indices, i) => ({ points: indices.map(j => `${points[j].x},${points[j].y}`).join(' '), depth: indices.reduce((n,j) => n + points[j].z, 0), color: ['#bdd0ab','#759b7c','#e1c99e','#8ab7a6','#d3e1bd','#a2bb91'][i] })).sort((a,b) => b.depth - a.depth);
  }, [length, width, height, rotation, zoom]);
  const slider = (label: string, value: number, change: (n: number) => void, max = 12) => <label className="pa-slider">{label}<strong>{value}</strong><input aria-label={label} type="range" min={1} max={max} value={value} onChange={e => change(Number(e.target.value))} /></label>;
  return <section className="pa-activity" aria-label={names[mode][example]}>
    <header><span className="pa-kicker">动手改变，亲眼发现</span><h2>{names[mode][example]}</h2><p>先猜一猜，再改变一个条件，最后把观察到的变化说给小陶听。</p></header>
    <div className="pa-example-tabs" aria-label="选择活动">{names[mode].map((name, i) => <button key={name} aria-pressed={example === i} onClick={() => changeExample(i)}>{name}</button>)}</div>
    {mode === 'diagram' && <div className="pa-panel">
      {example === 0 ? <><div className="pa-step-tabs">{['看看原来的分数', '找到共同分母', '改写并比较'].map((label,i) => <button key={label} aria-pressed={step === i} onClick={() => setStep(i)}><span>{i + 1}</span>{label}</button>)}</div>
        <div className="pa-fraction-comparison">{[{p:step ? 4 : 2,n:step ? 2 : 1,label:step ? '1/2＝2/4' : '1/2'},{p:4,n:3,label:'3/4'}].map((f,i) => <div key={i}><strong>{f.label}</strong><div className="pa-parts" style={{gridTemplateColumns:`repeat(${f.p},1fr)`}}>{Array.from({length:f.p},(_,j)=><span key={j} className={j<f.n?'is-filled':''}/>)}</div></div>)}</div>
        <p className="pa-explanation">{['两块整体同样大，等分的份数不同，先不要只比较分子。','2 和 4 的最小公倍数是 4。把每半份再分成两份，原来的一半并没有变大。','1/2＝2/4，2/4＜3/4。因此在同样大的整体中，1/2＜3/4。'][step]}</p><button className="pa-primary" onClick={()=>setStep((step+1)%3)}>{step===2?'重新观察':'看下一步 →'}</button></>
        : <><div className="pa-water-cycle">{WATER.map((name,i)=><button key={name} className={step===i?'is-active':''} onClick={()=>setStep(i)}><span>{['☀','☁','雨','水'][i]}</span><strong>{name}</strong><small>→</small></button>)}</div><p className="pa-explanation">{WATER_HELP[step%4]}</p><button className="pa-primary" onClick={()=>setStep((step+1)%4)}>沿着水的旅程走一步 →</button></>}
    </div>}
    {mode === 'simulation' && <div className="pa-panel">{example===0 ? <><div className="pa-controls">{slider('等分份数',parts,n=>{setParts(n);setSelected(old=>Math.min(old,n));})}<label className="pa-slider">选中份数<strong>{selected}</strong><input aria-label="选中份数" type="range" min={0} max={parts} value={selected} onChange={e=>setSelected(Number(e.target.value))}/></label></div><div className="pa-parts pa-large-parts" style={{gridTemplateColumns:`repeat(${parts},1fr)`}}>{Array.from({length:parts},(_,i)=><button key={i} aria-label={`取前${i+1}份`} className={i<selected?'is-filled':''} onClick={()=>setSelected(i+1)}/>)}</div><div className="pa-result">取了 <strong>{selected}/{parts}</strong> 个整体 <small>约占 {(selected/parts*100).toFixed(1)}%</small></div><p>这块整体始终同样大。改变份数时，要一起观察每一份的大小。</p></>
      : <><div className="pa-controls">{slider('菜地长（米）',length,setLength,12)}{slider('菜地宽（米）',width,setWidth,12)}</div><svg viewBox="0 0 560 280" role="img" aria-label={`长${length}米宽${width}米的菜地`}><defs><pattern id="pa-area-grid" x="60" y="35" width="30" height="16" patternUnits="userSpaceOnUse"><path d="M30 0H0V16" fill="none" stroke="#fcf9ef" strokeWidth="1"/></pattern></defs><rect x="60" y="35" width={length*30} height={width*16} fill="#81a17e"/><rect x="60" y="35" width={length*30} height={width*16} fill="url(#pa-area-grid)"/><text x="60" y="255">每格 1 平方米</text></svg><div className="pa-result">面积＝<strong>{length}×{width}＝{length*width}</strong> 平方米</div></>}</div>}
    {mode === 'visualization3d' && <div className="pa-panel"><div className="pa-controls">{slider('长',length,n=>{setLength(n);if(example){setWidth(n);setHeight(n);}})}{example===0&&<>{slider('宽',width,setWidth)}{slider('高',height,setHeight)}</>}</div>
      <svg className="pa-space" viewBox="0 0 560 350" tabIndex={0} role="img" aria-label="可以拖动或使用方向键旋转的长方体" onPointerDown={e=>{drag.current={x:e.clientX,y:e.clientY};e.currentTarget.setPointerCapture(e.pointerId);}} onPointerMove={e=>{if(!drag.current)return;const dx=e.clientX-drag.current.x,dy=e.clientY-drag.current.y;drag.current={x:e.clientX,y:e.clientY};setRotation(v=>({x:v.x+dy*.01,y:v.y+dx*.01}));}} onPointerUp={()=>{drag.current=null;}} onPointerCancel={()=>{drag.current=null;}} onKeyDown={e=>{if(!e.key.startsWith('Arrow'))return;e.preventDefault();setRotation(v=>({x:v.x+(e.key==='ArrowUp'?-.1:e.key==='ArrowDown'?.1:0),y:v.y+(e.key==='ArrowLeft'?-.1:e.key==='ArrowRight'?.1:0)}));}}>{faces.map((face,i)=><polygon key={i} points={face.points} fill={face.color} stroke="#fffdf4" strokeWidth="2"/>)}<text x="20" y="330">拖动旋转 · 方向键也能观察</text></svg>
      <div className="pa-space-tools"><button onClick={()=>setZoom(v=>Math.min(1.5,v+.1))}>放大 ＋</button><button onClick={()=>setZoom(v=>Math.max(.6,v-.1))}>缩小 −</button><button onClick={()=>{setRotation({x:-.4,y:.6});setZoom(1);}}>复位视角</button></div><div className="pa-result">体积＝<strong>{length}×{width}×{height}＝{length*width*height}</strong> 立方单位</div></div>}
    {mode === 'game' && <div className="pa-panel pa-game"><div className="pa-game-progress"><span>目标：完成 3 轮，每轮先独立判断</span><strong>得分 {score} / 3</strong></div>{round<3 ? <><p className="pa-kicker">第 {round+1} 轮 / 3</p><h3>{question.question}</h3><div className="pa-game-choices">{question.options.map((option,i)=><button key={option} disabled={choice!==null} aria-pressed={choice===i} onClick={()=>{setChoice(i);if(i===question.correct)setScore(n=>n+1);}}>{option}</button>)}</div>{choice!==null&&<div className="pa-explanation" role="status"><strong>{choice===question.correct?'判断正确。':'再想一想，正确选项是：'+question.options[question.correct]+'。'}</strong><p>{question.why}</p><button className="pa-primary" onClick={()=>{setRound(n=>n+1);setChoice(null);}}>{round===2?'查看本轮结果':'进入下一轮 →'}</button></div>}</> : <div className="pa-game-finish"><h3>完成三轮探索！</h3><p>本轮答对 {score} 题。你能把一道题的理由讲给小陶听吗？</p></div>}<button className="pa-reset" onClick={resetGame}>重新闯关 ↻</button></div>}
    <footer className="pa-context-note">{summary}</footer>
  </section>;
}
