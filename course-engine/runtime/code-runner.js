/**
 * 学生 JavaScript 在可终止的独立 Worker 中由浏览器解析执行。
 * 不使用 eval/new Function，不接服务器命令，不把 Python 文本伪装为能运行的代码。
 */
(function installEducationAgentCodeRunner() {
  let active = null;
  let serial = 0;
  const MAX_SOURCE = 20_000;
  const MAX_OUTPUT = 16_000;
  const now = () => typeof performance !== 'undefined' ? performance.now() : Date.now();

  function stop() {
    if (active) active.finish({ status: 'cancelled', stdout: '', error: '本次运行已停止' });
  }

  function run(source, options = {}) {
    stop();
    if (typeof source !== 'string' || !source.trim() || source.length > MAX_SOURCE) {
      return Promise.resolve({ status: 'error', stdout: '', error: '请输入不超过 20000 字的 JavaScript 代码', durationMs: 0 });
    }
    const timeout = Math.max(100, Math.min(3000, Number(options?.timeoutMs) || 1500));
    const runId = ++serial;
    const started = now();
    // 用户源码直接放在 Worker 脚本中，由浏览器编译；主线程永远不解释或执行学生代码。
    const prefix = `"use strict";\n(function(){\nconst send=self.postMessage.bind(self);\nconst format=(value)=>{try{return typeof value==='string'?value:JSON.stringify(value);}catch{return String(value);}};\nconst emit=(kind,payload)=>send({kind,runId:${runId},...payload});\nself.console=Object.freeze({log:(...args)=>emit('log',{text:args.map(format).join(' ')}),info:(...args)=>emit('log',{text:args.map(format).join(' ')}),warn:(...args)=>emit('log',{text:args.map(format).join(' ')}),error:(...args)=>emit('log',{text:args.map(format).join(' ')})});\nconst denied=()=>{throw new Error('练习代码不能访问网络或导入外部脚本');};\nfor(const name of ['fetch','XMLHttpRequest','WebSocket','EventSource','importScripts']){try{Object.defineProperty(self,name,{value:denied,writable:false,configurable:false});}catch{}}\n(async()=>{\n`;
    const suffix = `\n})().then(()=>emit('done',{}),error=>emit('failure',{message:String(error&&error.message||error).slice(0,1000)}));\n})();`;
    return new Promise(resolve => {
      let worker = null;
      let url = '';
      let timer;
      let output = '';
      let ended = false;
      const finish = result => {
        if (ended) return;
        ended = true;
        clearTimeout(timer);
        if (worker) { worker.onmessage = null; worker.onerror = null; worker.terminate(); }
        if (url) URL.revokeObjectURL(url);
        if (active?.runId === runId) active = null;
        resolve({ ...result, durationMs: Math.max(0, Math.round(now() - started)) });
      };
      active = { runId, finish };
      try {
        url = URL.createObjectURL(new Blob([prefix, source, suffix], { type: 'text/javascript' }));
        worker = new Worker(url, { name: 'educationagent-js-exercise' });
        worker.onmessage = event => {
          const message = event.data;
          if (!message || message.runId !== runId || ended) return;
          if (message.kind === 'log' && typeof message.text === 'string') {
            output += message.text + '\n';
            if (output.length > MAX_OUTPUT) finish({ status: 'error', stdout: output.slice(0, MAX_OUTPUT), error: '输出过多，运行已停止，请检查循环或减少日志' });
          } else if (message.kind === 'done') finish({ status: 'success', stdout: output.trimEnd(), error: '' });
          else if (message.kind === 'failure') finish({ status: 'error', stdout: output.trimEnd(), error: String(message.message || '运行失败').slice(0, 1000) });
        };
        worker.onerror = event => { event.preventDefault(); finish({ status: 'error', stdout: output.trimEnd(), error: String(event.message || '代码语法或运行错误').slice(0, 1000) }); };
        timer = setTimeout(() => finish({ status: 'timeout', stdout: output.trimEnd(), error: '运行时间过长，已停止。请检查循环是否能结束。' }), timeout);
      } catch {
        finish({ status: 'error', stdout: '', error: '当前浏览器暂不能运行这段练习，请更换支持 Worker 的浏览器或先阅读代码。' });
      }
    });
  }
  Object.defineProperty(window, 'EducationAgentCode', { value: Object.freeze({ run, stop, language: 'javascript' }), configurable: false });
  // 在捕获阶段接管两个固定按钮，阻止模板残留的主线程执行逻辑运行学生源码。
  window.addEventListener('click', event => {
    const target = event.target instanceof Element ? event.target.closest('#run-code,#stop-code') : null;
    if (!target) return;
    event.preventDefault(); event.stopImmediatePropagation();
    const editor = document.getElementById('code-input');
    const output = document.getElementById('code-output');
    const status = document.getElementById('code-status');
    if (!editor || !output || !status) return;
    if (target.id === 'stop-code') { stop(); status.textContent = '已停止，可以修改代码再试'; return; }
    const button = document.getElementById('run-code');
    const expected = button.getAttribute('data-expected-output');
    button.disabled = true; output.textContent = ''; status.textContent = '正在运行…';
    void run(editor.value, { timeoutMs: 1500 }).then(result => {
      button.disabled = false;
      output.textContent = result.stdout || (result.status === 'success' ? '运行完成，没有 console.log 输出' : '');
      status.textContent = result.status === 'success'
        ? expected === null ? '运行完成' : result.stdout.trim() === expected.trim() ? '结果符合目标，再试着改变一个条件吧' : '结果与目标不同，再检查一下思路'
        : result.error;
    });
  }, true);
  window.addEventListener('pagehide', stop);
})();
