# @educationagent/renderer

基于 React 的只读 SlideCanvas，布局采用内联样式及限定范围的 prose/effects CSS，不需要 Tailwind 或全局 preflight。

```tsx
import { SlideCanvas } from '@educationagent/renderer';
import '@educationagent/renderer/styles.css';

<div style={{ width: '100%', aspectRatio: '16 / 9' }}>
  <SlideCanvas slide={scene.content.canvas} />
</div>
```

`styles.css` 加载本地 KaTeX 样式与字体；其他字体使用操作系统字体。Vite 宿主应对 `react` / `react-dom` 启用 `resolve.dedupe`，避免 workspace 与前端分别安装造成两份 React。

