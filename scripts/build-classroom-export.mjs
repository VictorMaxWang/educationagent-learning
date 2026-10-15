/** 构建一次离线播放器；导出时只读本地静态资源，不执行运行时打包或远端 CDN。 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(path.join(root, 'desktop-app', 'package.json'));
const { build } = require('esbuild');
const output = path.join(root, 'desktop-app', 'public', 'educationagent-export');
await mkdir(output, { recursive: true });
const result = await build({
  absWorkingDir: path.join(root, 'desktop-app'), entryPoints: ['src/classroom/export/player.tsx'],
  outdir: output, entryNames: 'player', bundle: true, write: false, platform: 'browser',
  target: ['es2020'], format: 'iife', minify: true, jsx: 'automatic', charset: 'utf8',
  define: { 'process.env.NODE_ENV': '"production"' },
  loader: { '.woff2': 'dataurl', '.woff': 'dataurl', '.ttf': 'dataurl' },
  legalComments: 'eof',
  plugins: [{ name: 'offline-code-text', setup(builder) {
    // vendor 工作区可能有自己的 React 路径，独立包仍须共享一个实例，避免离线页出现 hook 错误。
    builder.onResolve({ filter: /^react(?:-dom)?(?:\/.*)?$/ }, args => ({ path: require.resolve(args.path) }));
    // 保留代码原文，避免把在线语法/WASM加载器带进声称无网络的课包。
    builder.onResolve({ filter: /^shiki$/ }, () => ({ path: 'shiki', namespace: 'offline-code-text' }));
    builder.onLoad({ filter: /.*/, namespace: 'offline-code-text' }, () => ({ contents: 'export function createHighlighter(){return new Promise(function(){});}', loader: 'js' }));
  } }],
});
for (const file of result.outputFiles) {
  const content = file.text;
  if (file.path.endsWith('.js') && /<\/script\s*>/i.test(content)) throw new Error('播放器脚本包含未转义的结束标签，不能安全嵌入 HTML');
  if (file.path.endsWith('.css') && /<\/style\s*>/i.test(content)) throw new Error('播放器样式包含未转义的结束标签');
  await writeFile(file.path, content, 'utf8');
}
// 构建时立即验证两个入口都存在，防止生产页面仅在第一次导出时才暴露漏构建。
for (const name of ['player.js', 'player.css']) {
  const contents = await readFile(path.join(output, name), 'utf8');
  if (!contents) throw new Error(`离线播放器资源为空：${name}`);
}
console.log('离线课堂播放器已生成（React、画布、图表与公式字体均随文件打包）。');
