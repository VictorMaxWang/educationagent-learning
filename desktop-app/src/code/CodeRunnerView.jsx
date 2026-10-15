import { useRef, useState } from 'react';
import { fetchRequest } from '../api/request';

const LANGS = [
  { key: 'python', label: '🐍 Python' },
  { key: 'c', label: '🔧 C 语言' },
  { key: 'html', label: '🌐 HTML' },
  { key: 'javascript', label: '⚡ JavaScript' },
];

const PYTHON_DEMO = `# 简单的 Python 示例：斐波那契
def fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a

for i in range(10):
    print(f"fib({i}) = {fib(i)}")`;

const HTML_DEMO = `<!DOCTYPE html>
<html>
<head>
<style>
  body { font-family: sans-serif; background: #EEF2FF; padding: 24px; }
  h1 { color: #6366f1; }
  .card { background: #fff; border-radius: 12px; padding: 16px; box-shadow: 0 2px 8px rgba(0,0,0,.08); }
  button { background: #6366f1; color: #fff; border: none; padding: 8px 16px; border-radius: 8px; cursor: pointer; }
</style>
</head>
<body>
  <h1>Hello 高德 🚀</h1>
  <div class="card">
    <p>这是一个 HTML 预览。点击按钮试试：</p>
    <button onclick="document.getElementById('t').textContent = '你点了按钮！时间：' + new Date().toLocaleTimeString()">点我</button>
    <p id="t"></p>
  </div>
</body>
</html>`;

const JS_DEMO = `// 浏览器 JavaScript：直接操作页面
const items = ['🚀 火箭', '🐍 Python', '⚡ 闪电', '🌐 HTML'];
document.body.innerHTML = '<h1>JavaScript 运行 ✅</h1><p>' + new Date().toLocaleString() + '</p><ul>' + items.map(x => '<li>' + x + '</li>').join('') + '</ul>';`;

const C_DEMO = `#include <stdio.h>

// 算法示例：二分查找（数组需有序）
int binary_search(int arr[], int n, int target) {
    int lo = 0, hi = n - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (arr[mid] == target) return mid;
        if (arr[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

int main() {
    int arr[] = {1, 3, 5, 7, 9, 11, 13};
    int n = sizeof(arr) / sizeof(arr[0]);
    int idx = binary_search(arr, n, 7);
    printf("7 的位置是: %d\\n", idx);
    printf("目标不存在返回: %d\\n", binary_search(arr, n, 8));
    return 0;
}`;

/** 把 JS 代码包进独立文档，用 iframe 渲染（sandbox 隔离，不污染主页面） */
function buildJsDoc(code) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body><script>${code}</script></body></html>`;
}

/**
 * 代码运行视图
 * - Python：POST /api/code/run（后端 ProcessBuilder 执行）
 * - HTML / JS：前端 iframe 渲染预览
 */
export default function CodeRunnerView() {
  const [lang, setLang] = useState('python');
  const [code, setCode] = useState(PYTHON_DEMO);
  const [running, setRunning] = useState(false);
  const [output, setOutput] = useState('');
  const [error, setError] = useState('');
  const [runId, setRunId] = useState(0); // 切换 iframe 内容时 key 变化触发重新渲染
  const gutterRef = useRef(null);
  const editorRef = useRef(null);

  // 行号：按代码换行数生成 1..N
  const lineNumbers = Array.from({ length: code.split('\n').length }, (_, i) => i + 1).join('\n');

  /** 行号栏与编辑区垂直滚动同步 */
  const syncScroll = () => {
    if (gutterRef.current && editorRef.current) {
      gutterRef.current.scrollTop = editorRef.current.scrollTop;
    }
  };

  const switchLang = (key) => {
    setLang(key);
    if (key === 'python') setCode(PYTHON_DEMO);
    else if (key === 'c') setCode(C_DEMO);
    else if (key === 'html') setCode(HTML_DEMO);
    else setCode(JS_DEMO);
    setOutput('');
    setError('');
  };

  const run = async () => {
    if (lang === 'python' || lang === 'c') {
      setRunning(true);
      setError('');
      try {
        const res = await fetchRequest('/api/code/run', {
          method: 'POST',
          body: JSON.stringify({ language: lang, code }),
        });
        if (res.code === 200) {
          setOutput(res.output || '（无输出）');
        } else {
          setError(res.msg || '运行失败');
        }
      } catch (e) {
        setError('请求失败：' + e.message);
      }
      setRunning(false);
    } else {
      // HTML / JS → iframe 渲染
      setRunId((id) => id + 1);
      setOutput('');
      setError('');
    }
  };

  const iframeDoc = lang === 'html' ? code : lang === 'javascript' ? buildJsDoc(code) : '';

  return (
    <div className="feature-panel code-runner">
      <div className="feature-header">
        <h2>💻 代码练习</h2>
        <span className="feature-slogan">Python 执行 · HTML/JS 预览</span>
      </div>
      <div className="feature-content">
        {/* 语言选择 */}
        <div className="code-langs">
          {LANGS.map((l) => (
            <button
              key={l.key}
              className={`code-lang${lang === l.key ? ' active' : ''}`}
              onClick={() => switchLang(l.key)}
            >
              {l.label}
            </button>
          ))}
        </div>

        {/* 代码输入（带行号栏） */}
        <div className="code-editor-wrap">
          <div className="code-gutter" ref={gutterRef}>
            {lineNumbers}
          </div>
          <textarea
            ref={editorRef}
            className="code-editor"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            onScroll={syncScroll}
            spellCheck={false}
            placeholder="在这里输入代码…"
          />
        </div>

        {/* 运行按钮 */}
        <div style={{ textAlign: 'right', margin: '8px 0' }}>
          <button className="save-btn-sm code-run-btn" onClick={run} disabled={running}>
            {running ? '⏳ 运行中…' : '▶ 运行'}
          </button>
        </div>

        {/* 输出 */}
        {error && <div className="error-banner">❌ {error}</div>}
        {lang === 'python' || lang === 'c' ? (
          <pre className="code-output">
            {output || (running ? '运行中…' : '点击「运行」查看输出')}
          </pre>
        ) : (
          <iframe
            key={runId}
            title="code-preview"
            className="code-preview"
            sandbox="allow-scripts"
            srcDoc={iframeDoc}
          />
        )}
      </div>
    </div>
  );
}
