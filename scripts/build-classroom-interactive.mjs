#!/usr/bin/env node
/** 将明确批准的浏览器依赖构建为本地 IIFE，生成器只内联产物，不访问 CDN。 */
import { build } from 'esbuild';
import { mkdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'course-engine/dist/runtime');
await mkdir(out, { recursive: true });
const license = await readFile(path.join(root, 'node_modules/three/LICENSE'), 'utf8');
for (const [name, banner, limit] of [['three', `/*! Three.js and OrbitControls\n${license.replace(/\*\//g, '* /')}\n*/`, 800_000], ['code-runner', '', 20_000]]) {
  const outfile = path.join(out, `${name}.js`);
  await build({ entryPoints: [path.join(root, `course-engine/runtime/${name === 'three' ? 'three-entry' : name}.js`)], outfile,
    bundle: true, format: 'iife', platform: 'browser', target: 'es2020', minify: true, sourcemap: false,
    legalComments: 'none', ...(banner ? { banner: { js: banner } } : {}) });
  const bytes = (await stat(outfile)).size;
  if (bytes > limit) throw new Error(`${name} runtime exceeds the inline budget (${bytes} > ${limit})`);
  console.log(`classroom runtime ${name}: ${bytes} bytes`);
}
