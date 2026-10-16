import { useEffect, useMemo, useRef, useState } from 'react';
import {
  cancelCodeJob, codeContext, compareCodeOutput, createCodeJob, displayTraceValue, getCodeJob,
  isCodeJobActive, numericTraceArray, numericTraceSeries, resolveTraceValue, shouldPollCodeJob, traceReference, traceVariables,
  type CodeJob, type CodeLanguage, type TraceStep, type TraceValue,
} from './code-api';
import { CODE_EXAMPLES, CODE_LANGUAGES } from './code-examples';
import './code-visualization.css';

export interface CodeVisualizationProps { onContextChange?: (context: string) => void }
const STATUS_LABELS: Record<CodeJob['status'], string> = {
  queued: '等待运行', running: '正在执行', completed: '执行完成', error: '需要修改代码', timeout: '运行已到时限', cancelled: '已停止',
};

function ArrayChart({ values }: { values: number[] }) {
  const shown = values.slice(0, 48);
  const low = Math.min(0, ...shown), high = Math.max(1, ...shown);
  const y = (number: number) => 175 - (number - low) / (high - low) * 140;
  const zero = y(0), width = 440 / Math.max(1, shown.length);
  return <div className="cvw-chart">
    <svg viewBox="0 0 500 220" role="img" aria-label={`当前数组：${shown.join('、')}`}>
      <line x1="34" y1={zero} x2="480" y2={zero} className="cvw-axis" />
      {shown.map((number, index) => <g key={index}>
        <rect x={38 + index * width} y={Math.min(y(number), zero)} width={Math.max(2, width - 7)} height={Math.max(2, Math.abs(zero - y(number)))} rx="3" className="cvw-bar" />
        <text x={38 + index * width + Math.max(2, width - 7) / 2} y={number >= 0 ? y(number) - 7 : y(number) + 14} textAnchor="middle">{number}</text>
        <text x={38 + index * width + Math.max(2, width - 7) / 2} y="205" textAnchor="middle" className="cvw-axis-label">{index}</text>
      </g>)}
    </svg>
    {!shown.length && <p>当前数组为空。</p>}
    {values.length > shown.length && <p>图中显示前 {shown.length} 项；完整值可在变量区查看。</p>}
  </div>;
}

function NumberChart({ trace, variableKey, position }: { trace: TraceStep[]; variableKey: string; position: number }) {
  const series = numericTraceSeries(trace, variableKey, position);
  const low = Math.min(0, ...series.map(item => item.value)), high = Math.max(1, ...series.map(item => item.value));
  const x = (step: number) => 42 + step / Math.max(1, position) * 420;
  const y = (number: number) => 175 - (number - low) / (high - low) * 145;
  const points = series.map(item => `${x(item.step)},${y(item.value)}`).join(' ');
  const last = series[series.length - 1];
  return <div className="cvw-chart">
    <svg viewBox="0 0 500 220" role="img" aria-label={`数值变化，共 ${series.length} 个观测点${last ? `，当前值 ${last.value}` : ''}`}>
      <line x1="42" y1="175" x2="475" y2="175" className="cvw-axis" />
      <line x1="42" y1="25" x2="42" y2="175" className="cvw-axis" />
      <text x="31" y="35" textAnchor="end" className="cvw-axis-label">{high}</text>
      <text x="31" y="178" textAnchor="end" className="cvw-axis-label">{low}</text>
      <text x="250" y="207" textAnchor="middle" className="cvw-axis-label">执行步骤</text>
      <polyline points={points} fill="none" className="cvw-number-line" />
      {last && <><circle cx={x(last.step)} cy={y(last.value)} r="5" className="cvw-point" /><text x={Math.min(450, x(last.step))} y={y(last.value) - 10} textAnchor="middle">{last.value}</text></>}
    </svg>
    {!series.length && <p>此变量在当前步骤没有可绘制的数值。</p>}
  </div>;
}

function ReferenceGraph({ value, step, name }: { value: TraceValue; step: TraceStep; name: string }) {
  const rootId = traceReference(value);
  const resolved = resolveTraceValue(value, step);
  const objectFields = resolved && !Array.isArray(resolved) && typeof resolved === 'object' ? resolved.properties ?? resolved.items ?? resolved : resolved;
  const fields = objectFields && typeof objectFields === 'object' ? Object.entries(objectFields).slice(0, 5) : [];
  return <div className="cvw-reference">
    <svg viewBox="0 0 540 260" role="img" aria-label={`${name} 的对象引用与字段`}>
      {fields.map(([, child], index) => <line key={index} x1="190" y1="130" x2="310" y2={30 + index * 48} className="cvw-reference-edge" data-reference={traceReference(child)} />)}
      <rect x="15" y="88" width="175" height="84" rx="14" className="cvw-reference-root" />
      <text x="102" y="119" textAnchor="middle">{name.slice(0, 19)}</text>
      <text x="102" y="145" textAnchor="middle" className="cvw-axis-label">{rootId ? `引用 ${rootId.slice(0, 16)}` : Array.isArray(resolved) ? '数组对象' : '对象'}</text>
      {fields.map(([key, child], index) => <g key={key}>
        <rect x="310" y={10 + index * 48} width="210" height="39" rx="9" className="cvw-reference-node" />
        <text x="322" y={34 + index * 48}>{key.slice(0, 14)}: {displayTraceValue(child, 22)}</text>
      </g>)}
    </svg>
    {rootId && resolved === undefined && <p>此步骤未返回该引用的对象内容。</p>}
    {!fields.length && resolved !== undefined && <p>当前对象没有可展示的字段。</p>}
    <details><summary>查看对象内容</summary><pre>{displayTraceValue(resolved, 6000)}</pre></details>
  </div>;
}

/** 每次运行只播放服务端返回的真实轨迹；编辑时使旧请求失效，避免旧结果覆盖新代码。 */
export default function CodeVisualization({ onContextChange }: CodeVisualizationProps) {
  const [language, setLanguage] = useState<CodeLanguage>('javascript');
  const [exampleId, setExampleId] = useState('area');
  const [code, setCode] = useState(CODE_EXAMPLES.javascript[0].code);
  const [stdin, setStdin] = useState(CODE_EXAMPLES.javascript[0].stdin);
  const [job, setJob] = useState<CodeJob | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState('');
  const [pollPaused, setPollPaused] = useState(false);
  const [position, setPosition] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [view, setView] = useState<'graph' | 'trace'>('graph');
  const [variableKey, setVariableKey] = useState('');
  const [graphView, setGraphView] = useState<'auto' | 'array' | 'number' | 'reference'>('auto');
  const [hintCount, setHintCount] = useState(0);
  const epoch = useRef(0);
  const mounted = useRef(true);
  const currentJob = useRef<CodeJob | null>(null);
  const pollRequest = useRef<AbortController | null>(null);
  const contextCallback = useRef(onContextChange);
  const submittedInput = useRef('');
  const editorNode = useRef<HTMLTextAreaElement | null>(null);
  const lineNodes = useRef(new Map<number, HTMLLIElement>());
  const examples = CODE_EXAMPLES[language];
  const exercise = examples.find(item => item.id === exampleId) ?? examples[0];
  const trace = job?.trace ?? [];
  const step = trace[Math.min(position, Math.max(0, trace.length - 1))];
  const variables = useMemo(() => traceVariables(step), [step]);
  const chosenVariable = variables.find(item => item.key === variableKey) ?? variables.find(item => numericTraceArray(item.value, step) !== undefined) ?? variables.find(item => typeof item.value === 'number') ?? variables[0];
  const selectedKey = chosenVariable?.key ?? '';
  const array = numericTraceArray(chosenVariable?.value, step);
  const resolved = resolveTraceValue(chosenVariable?.value, step);
  const selectedGraph = graphView === 'auto' ? array !== undefined ? 'array' : typeof resolved === 'number' ? 'number' : 'reference' : graphView;
  const active = submitting || isCodeJobActive(job);
  const baselineInput = submittedInput.current.replace(/\r\n/g, '\n').trim() === exercise.stdin.replace(/\r\n/g, '\n').trim();

  useEffect(() => { currentJob.current = job; }, [job]);
  useEffect(() => { contextCallback.current = onContextChange; });
  useEffect(() => { contextCallback.current?.(codeContext({ language, code, stdin }, step)); }, [language, code, stdin, step]);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false; epoch.current++; pollRequest.current?.abort();
      const id = currentJob.current?.id;
      if (id && isCodeJobActive(currentJob.current)) void cancelCodeJob(id).catch(() => {});
      contextCallback.current?.('');
    };
  }, []);
  useEffect(() => {
    if (!job?.id || !shouldPollCodeJob(job) || pollPaused || stopping) return;
    const token = epoch.current, id = job.id;
    let ended = false, pending = false;
    const refresh = async () => {
      if (pending || ended) return;
      pending = true;
      const controller = new AbortController(); pollRequest.current = controller;
      try {
        const result = await getCodeJob(id, controller.signal);
        if (!ended && mounted.current && epoch.current === token) { setJob(result); setError(''); }
      } catch (problem) {
        if (!ended && !controller.signal.aborted && mounted.current && epoch.current === token) {
          setError(problem instanceof Error ? problem.message : '暂时无法读取运行进度。'); setPollPaused(true);
        }
      } finally { pending = false; }
    };
    const timer = window.setInterval(() => void refresh(), 650);
    void refresh();
    return () => { ended = true; window.clearInterval(timer); pollRequest.current?.abort(); };
  }, [job?.id, job?.status, job?.tracePending, pollPaused, stopping]);
  useEffect(() => {
    if (!playing || trace.length < 2) return;
    const timer = window.setInterval(() => setPosition(current => {
      if (current >= trace.length - 1) { setPlaying(false); return current; }
      return current + 1;
    }), 650 / speed);
    return () => window.clearInterval(timer);
  }, [playing, trace.length, speed]);
  useEffect(() => {
    const node = step ? lineNodes.current.get(step.line) : undefined;
    const list = node?.parentElement;
    if (!node || !list) return;
    // 播放时只滚动代码区域，避免逐步执行把整个课堂页面带离问答区。
    const row = node.getBoundingClientRect(), viewport = list.getBoundingClientRect();
    if (row.top < viewport.top) list.scrollTop -= viewport.top - row.top;
    else if (row.bottom > viewport.bottom) list.scrollTop += row.bottom - viewport.bottom;
  }, [step]);

  const invalidate = () => {
    epoch.current++; pollRequest.current?.abort();
    const prior = currentJob.current;
    if (prior?.id && isCodeJobActive(prior)) void cancelCodeJob(prior.id).catch(() => {});
    currentJob.current = null;
    setJob(null); setSubmitting(false); setStopping(false); setPlaying(false); setPosition(0); setError(''); setPollPaused(false); setVariableKey('');
  };
  const changeExercise = (nextLanguage: CodeLanguage, nextId: string) => {
    invalidate();
    const next = CODE_EXAMPLES[nextLanguage].find(item => item.id === nextId) ?? CODE_EXAMPLES[nextLanguage][0];
    setLanguage(nextLanguage); setExampleId(next.id); setCode(next.code); setStdin(next.stdin); setHintCount(0); setGraphView('auto');
  };
  const run = async () => {
    if (!code.trim() || active) return;
    invalidate(); const token = epoch.current; setSubmitting(true);
    submittedInput.current = stdin;
    try {
      // POST 保持接收响应，编辑或卸载后的迟到任务一旦取得 id 便立即取消。
      const result = await createCodeJob({ language, code, stdin });
      if (!mounted.current || token !== epoch.current) { if (isCodeJobActive(result)) void cancelCodeJob(result.id).catch(() => {}); return; }
      currentJob.current = result; setJob(result); setSubmitting(false);
    } catch (problem) {
      if (mounted.current && token === epoch.current) { setError(problem instanceof Error ? problem.message : '本次运行未能开始。'); setSubmitting(false); }
    }
  };
  const stop = async () => {
    const prior = currentJob.current;
    const token = ++epoch.current; pollRequest.current?.abort(); setPlaying(false); setPollPaused(true);
    if (!prior?.id) { setSubmitting(false); setError('本次运行已停止。'); return; }
    setStopping(true);
    try {
      const result = await cancelCodeJob(prior.id);
      if (mounted.current && epoch.current === token) { currentJob.current = result; setJob(result); setError(''); }
    } catch (problem) {
      if (mounted.current && epoch.current === token) setError(`${problem instanceof Error ? problem.message : '暂时无法停止。'} 可重新读取进度后再试。`);
    } finally { if (mounted.current && epoch.current === token) { setStopping(false); setPollPaused(false); } }
  };
  const move = (next: number) => { setPlaying(false); setPosition(Math.max(0, Math.min(trace.length - 1, next))); };
  const locateDiagnostic = (line: number) => {
    const match = trace.findIndex(entry => entry.line === line);
    if (match >= 0) { move(match); return; }
    const editor = editorNode.current;
    if (!editor) return;
    const lines = code.split('\n');
    const safeLine = Math.max(0, Math.min(line - 1, lines.length - 1));
    const start = lines.slice(0, safeLine).reduce((count, text) => count + text.length + 1, 0);
    editor.focus(); editor.setSelectionRange(start, start + lines[safeLine].length);
    editor.scrollTop = Math.max(0, safeLine * 22 - editor.clientHeight / 2);
  };

  return <section className="code-visualization" aria-label="代码可视化课堂">
    <header className="cvw-header"><div><span className="cvw-eyebrow">动手写 · 看见过程</span><h2>代码可视化</h2><p>改一行代码，观察变量、图形与结果怎样一起变化。</p></div><span className="cvw-mark" aria-hidden="true">{'{ }'}</span></header>
    <div className="cvw-controls">
      <label>编程语言<select value={language} onChange={event => changeExercise(event.target.value as CodeLanguage, exampleId)}>{CODE_LANGUAGES.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
      <label>探索任务<select value={exampleId} onChange={event => changeExercise(language, event.target.value)}>{examples.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></label>
      <p>{exercise.goal}</p>
    </div>
    <div className="cvw-workbench">
      <div className="cvw-editor-panel">
        <div className="cvw-panel-heading"><strong>{CODE_LANGUAGES.find(item => item.id === language)?.fileName}</strong><button type="button" onClick={() => changeExercise(language, exampleId)}>恢复例题</button></div>
        <label className="cvw-sr-only" htmlFor="cvw-code-editor">编辑代码</label>
        <textarea ref={editorNode} id="cvw-code-editor" className="cvw-editor" value={code} spellCheck={false} autoCapitalize="off" autoCorrect="off" onChange={event => { invalidate(); setCode(event.target.value); }} onKeyDown={event => { if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); void run(); } }} />
        <div className="cvw-input-row"><label htmlFor="cvw-standard-input">标准输入 <small>运行时读取的内容</small></label><textarea id="cvw-standard-input" value={stdin} spellCheck={false} rows={2} onChange={event => { invalidate(); setStdin(event.target.value); }} /></div>
        <div className="cvw-run-row"><button type="button" className="cvw-primary" onClick={() => void run()} disabled={!code.trim() || active}>▶ {submitting ? '正在提交…' : '运行代码'}</button><button type="button" onClick={() => void stop()} disabled={!active || stopping}>{stopping ? '正在停止…' : '停止'}</button><span>Ctrl / ⌘ + Enter</span></div>
        <div className="cvw-hints"><div><strong>先想一步</strong><button type="button" onClick={() => setHintCount(count => Math.min(exercise.hints.length, count + 1))} disabled={hintCount >= exercise.hints.length}>{hintCount ? '再给一点提示' : '查看提示'}</button></div>{hintCount ? <ol>{exercise.hints.slice(0, hintCount).map(hint => <li key={hint}>{hint}</li>)}</ol> : <p>需要时逐步展开提示，也可以把当前代码拿去问小陶。</p>}</div>
      </div>
      <div className="cvw-result-panel">
        <div className="cvw-panel-heading"><strong>运行结果</strong><span className={`cvw-status cvw-status-${job?.status ?? 'idle'}`} role="status">{submitting ? '正在提交' : job ? STATUS_LABELS[job.status] : '等待你的第一次运行'}</span></div>
        {error && <div className="cvw-error" role="alert">{error}{pollPaused && job && <button type="button" onClick={() => { setError(''); setPollPaused(false); }}>重新读取进度</button>}</div>}
        {job?.reason && <p className="cvw-notice">{job.reason}</p>}
        {job?.status === 'cancelled' && job.tracePending && <p className="cvw-notice">已停止，正在整理本次已经执行的步骤…</p>}
        {job?.truncated && <p className="cvw-notice">已保留本次运行的部分步骤，可以继续逐步查看。</p>}
        <pre className="cvw-output" aria-label="程序标准输出">{job ? job.stdout || (isCodeJobActive(job) ? '程序正在执行…' : '（没有输出）') : '运行后，这里显示代码的真实输出。'}</pre>
        {job?.stderr && <details className="cvw-stderr" open={job.status === 'error'}><summary>错误信息</summary><pre>{job.stderr}</pre></details>}
        {!!job?.diagnostics?.length && <ul className="cvw-diagnostics">{job.diagnostics.map((item, index) => <li key={index}>{item.line ? <button type="button" onClick={() => locateDiagnostic(item.line!)}>第 {item.line} 行</button> : null}{item.message}</li>)}</ul>}
        {job?.status === 'completed' && <div className={`cvw-test-feedback ${baselineInput && compareCodeOutput(job.stdout, exercise.expectedStdout) ? 'cvw-test-pass' : ''}`}>
          <strong>{baselineInput ? compareCodeOutput(job.stdout, exercise.expectedStdout) ? '✓ 本例输入的输出检查通过' : '输出与本例预期不同，再检查一下' : '你修改了输入，可以对照规律判断结果'}</strong><span>例题输入：<code>{exercise.stdin}</code></span><span>对应预期：<code>{exercise.expectedStdout}</code></span>
        </div>}
        <div className="cvw-result-note"><span aria-hidden="true">◌</span><p>每次运行最多记录 1000 步。输入、代码或语言改变后，需要重新运行。</p></div>
      </div>
    </div>

    <section className="cvw-observer" aria-label="执行过程与图形">
      <div className="cvw-observer-heading"><div><h3>看见代码怎样执行</h3><p>{trace.length ? `本次已记录 ${trace.length} 个真实执行步骤` : '运行后，可以从第一行开始慢慢看。'}</p></div><div className="cvw-view-tabs" role="group" aria-label="观察视图"><button type="button" aria-pressed={view === 'graph'} onClick={() => setView('graph')}>代码与图形联动</button><button type="button" aria-pressed={view === 'trace'} onClick={() => setView('trace')}>执行过程追踪</button></div></div>
      <div className="cvw-playback">
        <div className="cvw-step-buttons"><button type="button" aria-label="回到开头" title="回到开头" disabled={!trace.length} onClick={() => move(0)}>↤</button><button type="button" aria-label="上一步" disabled={!trace.length || position === 0} onClick={() => move(position - 1)}>←</button><button type="button" className="cvw-play" disabled={trace.length < 2} onClick={() => { if (position >= trace.length - 1) setPosition(0); setPlaying(value => !value); }}>{playing ? 'Ⅱ 暂停' : '▶ 播放'}</button><button type="button" aria-label="下一步" disabled={!trace.length || position >= trace.length - 1} onClick={() => move(position + 1)}>→</button></div>
        <label className="cvw-speed">速度<select value={speed} onChange={event => setSpeed(Number(event.target.value))}><option value="0.5">0.5×</option><option value="1">1×</option><option value="2">2×</option><option value="4">4×</option></select></label>
        <label className="cvw-timeline"><span className="cvw-sr-only">当前执行步骤</span><input type="range" min="0" max={Math.max(0, trace.length - 1)} value={Math.min(position, Math.max(0, trace.length - 1))} disabled={!trace.length} onChange={event => move(Number(event.target.value))} /><span>{trace.length ? position + 1 : 0} / {trace.length}</span></label>
      </div>
      {step ? <div className="cvw-observer-grid">
        <div className="cvw-source-view"><div className="cvw-panel-heading"><strong>第 {step.line} 行</strong><span>{step.event}</span></div><ol aria-label="当前执行行">{code.split('\n').map((line, index) => <li ref={node => { if (node) lineNodes.current.set(index + 1, node); else lineNodes.current.delete(index + 1); }} className={index + 1 === step.line ? 'cvw-current-line' : ''} aria-current={index + 1 === step.line ? 'step' : undefined} key={index}><code>{line || ' '}</code></li>)}</ol></div>
        {view === 'graph' ? <div className="cvw-graph-panel"><div className="cvw-graph-controls"><label>观察变量<select value={selectedKey} disabled={!variables.length} onChange={event => { setVariableKey(event.target.value); setGraphView('auto'); }}>{variables.length ? variables.map(item => <option key={item.key} value={item.key}>{item.name} · {item.frame}</option>) : <option value="">此步骤暂无可读变量</option>}</select></label><label>呈现方式<select value={graphView} onChange={event => setGraphView(event.target.value as typeof graphView)}><option value="auto">自动匹配</option><option value="array">数组柱形</option><option value="number">数值曲线</option><option value="reference">对象引用</option></select></label></div>
          {chosenVariable ? <><p className="cvw-selected-value"><strong>{chosenVariable.name}</strong><code>{displayTraceValue(chosenVariable.value, 350)}</code></p>{selectedGraph === 'array' ? array ? <ArrayChart values={array} /> : <div className="cvw-graph-empty">当前变量不是数值数组，试试选择数组变量。</div> : selectedGraph === 'number' ? <NumberChart trace={trace} variableKey={selectedKey} position={position} /> : <ReferenceGraph value={chosenVariable.value} step={step} name={chosenVariable.name} />}</> : <div className="cvw-graph-empty">代码执行到变量创建后，图形会在这里出现。</div>}
        </div> : <div className="cvw-trace-panel"><h4>变量与调用栈</h4>{step.frames.length ? step.frames.map((frame, index) => <section className="cvw-frame" key={`${frame.name}:${index}`}><h5><span>{index === 0 ? '当前' : `上层 ${index}`}</span>{frame.name}</h5>{Object.keys(frame.locals).length ? <dl>{Object.entries(frame.locals).map(([name, value]) => <div key={name}><dt>{name}</dt><dd><code>{displayTraceValue(value, 700)}</code></dd></div>)}</dl> : <p>此栈帧暂没有可读取的局部变量。</p>}</section>) : <p>此步骤没有返回可读调用栈。</p>}<h4>到这一步的输出</h4><pre className="cvw-step-output">{step.stdout || '（尚未输出）'}</pre></div>}
      </div> : <div className="cvw-awaiting"><span aria-hidden="true">⌘</span><strong>从一个小问题出发</strong><p>先运行“菜园面积”，再改改输入。真实执行轨迹、变量和图形将在这里一起展开。</p></div>}
    </section>
  </section>;
}
