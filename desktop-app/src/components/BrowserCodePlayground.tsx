import { useEffect, useMemo, useRef, useState } from 'react';

/** 代码仅在无同源/无网络的 iframe Worker 中执行，超时终止，不调用服务器执行接口。 */
export default function BrowserCodePlayground() {
  const [code, setCode] = useState('const names = ["稻田", "小河", "校园"];\nnames.forEach((name, index) => console.log(index + 1, name));');
  const [output, setOutput] = useState('点击“运行代码”，看看输出。');
  const [running, setRunning] = useState(false);
  const frame = useRef<HTMLIFrameElement | null>(null);
  const active = useRef('');
  const timeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const nonce = useMemo(() => Array.from(crypto.getRandomValues(new Uint8Array(12)), value => value.toString(16).padStart(2, '0')).join(''), []);
  const html = useMemo(() => `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; worker-src blob:; connect-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><script>let worker;let url;const key=${JSON.stringify(nonce)};window.addEventListener('message',e=>{if(e.source!==parent||e.data?.key!==key)return;worker?.terminate();if(url)URL.revokeObjectURL(url);if(e.data.type==='stop')return;const id=e.data.id;const source='const console={log:(...v)=>postMessage({line:v.map(x=>{try{return typeof x===\"string\"?x:JSON.stringify(x)}catch{return String(x)}}).join(\" \").slice(0,2000)})};try{\\n'+String(e.data.code)+'\\n;postMessage({done:true})}catch(e){postMessage({error:String(e.message||e)})}';url=URL.createObjectURL(new Blob([source],{type:'text/javascript'}));worker=new Worker(url);let count=0;worker.onmessage=m=>{if(++count>100){worker.terminate();parent.postMessage({key,id,error:'输出超过100条，已停止运行。'},'*');return;}parent.postMessage({key,id,...m.data},'*');if(m.data?.done||m.data?.error)worker.terminate();};worker.onerror=err=>parent.postMessage({key,id,error:err.message},'*');});</script>`, [nonce]);
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow || event.data?.key !== nonce || event.data.id !== active.current) return;
      if (typeof event.data.line === 'string') setOutput(previous => (previous + event.data.line + '\n').slice(0, 12000));
      if (event.data.done || event.data.error) { if (timeout.current) clearTimeout(timeout.current); setRunning(false); if (event.data.error) setOutput(previous => previous + '\n' + String(event.data.error).slice(0, 1000)); }
    };
    window.addEventListener('message', onMessage);
    return () => { window.removeEventListener('message', onMessage); if (timeout.current) clearTimeout(timeout.current); };
  }, [nonce]);
  const run = () => {
    const id = String(Date.now()); active.current = id; setOutput(''); setRunning(true);
    frame.current?.contentWindow?.postMessage({ key: nonce, id, type: 'run', code }, '*');
    if (timeout.current) clearTimeout(timeout.current);
    timeout.current = setTimeout(() => { frame.current?.contentWindow?.postMessage({ key: nonce, type: 'stop' }, '*'); setRunning(false); setOutput(previous => previous + '\n运行达到 2 秒上限，已停止。'); }, 2000);
  };
  return <section className="browser-code-playground"><header><span>在浏览器里试一试</span><h2>JavaScript 代码练习</h2><p>用 console.log 查看结果；运行不访问网络和服务器文件。</p></header><label>代码<textarea value={code} maxLength={20000} spellCheck={false} onChange={event => setCode(event.target.value)} /></label><button disabled={running} onClick={run}>{running ? '运行中…' : '运行代码 ▶'}</button><pre aria-live="polite">{output || '运行完成，没有输出。'}</pre><iframe ref={frame} title="隔离的代码运行环境" sandbox="allow-scripts" srcDoc={html} className="browser-code-sandbox" /></section>;
}
