import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Worker as NodeWorker } from 'node:worker_threads';
import vm from 'node:vm';
import { generateOutline, generateScene, normalizeOutline, supportedSceneActions } from '../dist/engine.js';
import { interactiveTargets, isApprovedSlideImage, restrictInteractiveHtml, sanitizeSlidePayload } from '../dist/content-policy.js';

const course = { id: 'course-advanced', topic: '分数与程序', grade: 7, subject: '数学', outlines: [] };
const outline = (widgetType) => ({ id: 'scene-1', order: 0, type: 'interactive', title: '动手观察', description: '比较相同整体的分数', keyPoints: ['相同单位'], widgetType, widgetOutline: { concept: '分数', language: 'python' } });
const codeHtml = '<!DOCTYPE html><html><head></head><body><h1>试着计算两倍</h1><textarea id="code-input">console.log(2 * 3);</textarea><button id="run-code" data-expected-output="6">运行</button><button id="stop-code">停止</button><pre id="code-output"></pre><span id="code-status"></span></body></html>';
const gameScript = 'let score=0;document.getElementById("game-answer").onclick=function(){score+=10;document.getElementById("game-score").textContent=String(score);document.getElementById("game-feedback").textContent=score>=20?"达到目标":"继续努力";};document.getElementById("game-retry").onclick=function(){score=0;document.getElementById("game-score").textContent="0";document.getElementById("game-feedback").textContent="再试一次";};';
const gameHtml = `<!DOCTYPE html><html><head></head><body><h2 id="game-goal">答对两题，得到20分</h2><output id="game-score">0</output><button id="game-answer">选择同样大小的整体</button><p id="game-feedback">请选择</p><button id="game-retry">重试</button><script>${gameScript}</script></body></html>`;
const threeHtml = '<!DOCTYPE html><html><head></head><body><div id="space"></div><button id="zoom-in">放大</button><button id="reset-camera">复位</button><script>const scene=new THREE.Scene();const box=new THREE.Mesh(new THREE.BoxGeometry(1,1,1),new THREE.MeshStandardMaterial({color:0x73a268}));scene.add(box);window.sampleScene=scene;</script></body></html>';
const model = (...responses) => { const calls = []; const call = async (system, user) => { calls.push({ system, user }); const next = responses.shift(); if (next === undefined) throw Error('unexpected model call'); return typeof next === 'string' ? next : JSON.stringify(next); }; return { call, calls }; };

test('new interactive widget kinds survive outline normalization and use their SDK prompt families', async () => {
  for (const [kind, html] of [['code', codeHtml], ['game', gameHtml], ['visualization3d', threeHtml]]) {
    const ai = model(html, [{ type: 'text', content: '先看清楚目标，再试一试。' }]);
    const scene = await generateScene(course, outline(kind), ai.call);
    assert.equal(scene.type, 'interactive'); assert.equal(scene.content.widgetType, kind);
    assert.match(ai.calls[0].system, kind === 'code' ? /Code Playground/ : kind === 'game' ? /Game/i : /3D Visualization/);
    assert.match(ai.calls[0].system, /分数比较必须使用同样大小的整体/);
    assert.match(ai.calls[0].system, /不显示 HTML\/CSP\/Worker/);
    if (kind === 'code') {
      assert.match(ai.calls[0].user, /javascript/); assert.match(scene.content.html, /data-educationagent-runtime="code-runner"/);
      assert.match(scene.content.html, /worker-src blob:/); assert.doesNotMatch(scene.content.html, /unsafe-eval/);
    }
    if (kind === 'code' || kind === 'game') {
      assert.match(ai.calls[0].system, /10000字符以内/);
      assert.match(ai.calls[0].system, kind === 'code' ? /不实现语法高亮/ : /不做大Canvas物理引擎/);
      assert.doesNotMatch(ai.calls[1].system, /## 本页精简/);
    }
  }
  assert.equal(normalizeOutline(outline('code'), 'x', 0).widgetOutline.language, 'javascript');
});

test('3D runtime is genuine bundled Three plus OrbitControls and precedes generated scene code', async () => {
  const bundled = await readFile(new URL('../dist/runtime/three.js', import.meta.url), 'utf8');
  const window = {}; vm.runInNewContext(bundled, { window, console }, { timeout: 5000 });
  const scene = new window.THREE.Scene(); const vector = new window.THREE.Vector3(3, 4, 0);
  assert.equal(scene.type, 'Scene'); assert.equal(vector.length(), 5); assert.equal(typeof window.THREE.OrbitControls, 'function');
  const html = restrictInteractiveHtml(threeHtml, { widgetType: 'visualization3d' });
  assert.ok(html.indexOf('data-educationagent-runtime="three"') < html.indexOf('const scene=new THREE.Scene'));
  assert.doesNotMatch(html, /<script[^>]+src=|<link\b/);
  assert.ok(html.length <= 1_200_000); assert.ok(Buffer.byteLength(html) < 1_700_000);
  const combined = restrictInteractiveHtml(threeHtml.replace('<div id="space">', '<p>\\(x^2+1\\)</p><div id="space">'), { widgetType: 'visualization3d' });
  assert.match(combined, /data:font\/woff2/); assert.ok(combined.length < 1_200_000);
});

test('game controls retain executable scoring and reset behavior through content policy', () => {
  const html = restrictInteractiveHtml(gameHtml, { widgetType: 'game' });
  assert.ok(html.includes(gameScript));
  const nodes = new Map(['game-score', 'game-answer', 'game-feedback', 'game-retry'].map(id => [id, { textContent: id === 'game-score' ? '0' : '', onclick: null }]));
  vm.runInNewContext(gameScript, { document: { getElementById: id => nodes.get(id) } });
  nodes.get('game-answer').onclick(); nodes.get('game-answer').onclick();
  assert.equal(nodes.get('game-score').textContent, '20'); assert.equal(nodes.get('game-feedback').textContent, '达到目标');
  nodes.get('game-retry').onclick(); assert.equal(nodes.get('game-score').textContent, '0');
  assert.throws(() => restrictInteractiveHtml('<html><body>仅有游戏介绍</body></html>', { widgetType: 'game' }), /操作与反馈控件/);
  assert.throws(() => restrictInteractiveHtml('<html><body>仅有代码示例</body></html>', { widgetType: 'code' }), /操作与反馈控件/);
});

test('real generated volume game accepts its existing score-value and retry-btn without renaming or inventing controls', async () => {
  // 2026-10-15真实模型回复的离线回归。测试只回放固定HTML，不请求模型、不执行该游戏脚本。
  const generated = await readFile(new URL('./fixtures/generated-volume-game.html', import.meta.url), 'utf8');
  assert.ok(generated.length > 20_000);
  const html = restrictInteractiveHtml(generated, { widgetType: 'game' });
  assert.match(html, /id="score-value"/); assert.match(html, /<button[^>]*id="retry-btn"/);
  assert.match(html, /getElementById\(['"]score-value['"]\)/);
  assert.match(html, /onclick="resetGame\(\)"/);
  assert.doesNotMatch(html, /id="game-score"|id="game-retry"/);
  assert.doesNotMatch(html, /<script[^>]*\ssrc=/);
  assert.match(html, /connect-src 'none'/);
});

test('game role aliases still require unique real elements and an actual retry button', () => {
  const wrap = (score, retry) => `<html><body><h2 id="game-goal">答对两次</h2>${score}<p id="game-feedback">请选择</p>${retry}</body></html>`;
  assert.doesNotThrow(() => restrictInteractiveHtml(wrap('<output id="score-value">0</output>', '<button id="retry-btn">重试</button>'), { widgetType: 'game' }));
  assert.throws(() => restrictInteractiveHtml(wrap('', '<button id="retry-btn">重试</button>'), { widgetType: 'game' }), /操作与反馈控件/);
  assert.throws(() => restrictInteractiveHtml(wrap('<output id="score-value">0</output><span id="score-value">假重复</span>', '<button id="retry-btn">重试</button>'), { widgetType: 'game' }), /操作与反馈控件/);
  assert.throws(() => restrictInteractiveHtml(wrap('<output id="score-value">0</output>', '<span id="retry-btn">仅文字不是按钮</span>'), { widgetType: 'game' }), /必须是真实唯一的按钮/);
  assert.throws(() => restrictInteractiveHtml(wrap('<output id="score-value">0</output>', '<button id="retry-btn">A</button><button id="retry-btn">B</button>'), { widgetType: 'game' }), /操作与反馈控件/);
  assert.throws(() => restrictInteractiveHtml(wrap('<script>const markup=\'<output id="score-value">0</output>\';</script>', '<button id="retry-btn">重试</button>'), { widgetType: 'game' }), /操作与反馈控件/);
});

test('widget actions reference unique real DOM targets and whiteboard actions preserve valid drawing order', () => {
  const content = { html: '<html><body><input id="parts" type="range"><input id="flag" type="checkbox"><span id="note"></span><span id="duplicate"></span><span id="duplicate"></span><script>const hidden="id=\"fiction\"";</script></body></html>' };
  const actions = supportedSceneActions([
    { id: 'a', type: 'widget_setState', state: { parts: 4, flag: true } },
    { id: 'b', type: 'widget_highlight', target: 'parts' },
    { id: 'c', type: 'widget_annotation', target: '#note', content: '保持整体一样大' },
    { id: 'd', type: 'widget_reveal', target: '#note' },
    { id: 'bad', type: 'widget_highlight', target: '#fiction' },
    { id: 'dup', type: 'widget_highlight', target: '#duplicate' },
    { id: 'badstate', type: 'widget_setState', state: { imaginary: 5 } },
    { id: 'open', type: 'wb_open' },
    { id: 'draw', type: 'wb_draw_text', elementId: 'example', content: '同样大小的整体', x: 10, y: 20 },
    { id: 'del', type: 'wb_delete', elementId: 'example' },
    { id: 'missing', type: 'wb_delete', elementId: 'example' },
    { id: 'unknown', type: 'wb_edit_code', elementId: 'missing', operation: 'delete_lines', lineIds: ['x'] },
  ], content);
  assert.deepEqual(actions.map(item => item.id), ['a', 'b', 'c', 'd', 'open', 'draw', 'del']);
  assert.deepEqual(actions[0].state, { controls: { '#parts': { value: 4 }, '#flag': { checked: true } } });
  assert.equal(actions[1].target, '#parts');
  const targets = interactiveTargets('<html><body><form><input id="removed"></form><input id="actual"></body></html>');
  assert.equal(targets.ids.has('removed'), false); assert.equal(targets.controls.has('actual'), true);
});

test('interactive action parsing keeps implemented whiteboard commands without an extra model call', async () => {
  const ai = model('<html><body><input id="parts" type="range"></body></html>', [
    { type: 'text', content: '看白板上的等分关系。' }, { type: 'action', name: 'wb_open', params: {} },
    { type: 'action', name: 'wb_draw_text', params: { elementId: 'unit', content: '同一个整体', x: 10, y: 20 } },
    { type: 'action', name: 'widget_setState', params: { state: { parts: 4 } } },
  ]);
  const scene = await generateScene(course, outline('simulation'), ai.call);
  assert.deepEqual(scene.actions.map(item => item.type), ['speech', 'wb_open', 'wb_draw_text', 'widget_setState']);
  assert.equal(ai.calls.length, 2);
});

test('media requests require explicit enable, keep image-only placeholders, and reject arbitrary remote references', async () => {
  const media = [{ type: 'video', elementId: 'gen_vid_x', prompt: 'video' },
    { type: 'image', elementId: 'https://outside/image.png', prompt: 'bad' },
    { type: 'image', elementId: 'gen_img_bad', prompt: 'bad ratio', aspectRatio: '2:7' },
    { type: 'image', elementId: 'gen_img_fractions', prompt: '相同大小的两块正方形', aspectRatio: '4:3' }];
  const raw = { ...outline(), type: 'slide', mediaGenerations: media };
  assert.equal(normalizeOutline(raw, 's', 0, false).mediaGenerations, undefined);
  assert.deepEqual(normalizeOutline(raw, 's', 0, true).mediaGenerations, [media[3]]);
  const ai = model({ outlines: [raw, { ...outline('game'), id: 'g' }, { ...outline(), type: 'quiz', id: 'q' }, { ...raw, id: 'end' }] });
  const result = await generateOutline({ ...course, mediaEnabled: true }, ai.call);
  assert.ok(result[0].mediaGenerations?.every(item => item.type === 'image'));
  const approved = new Set(['gen_img_fractions']);
  assert.equal(isApprovedSlideImage('gen_img_fractions', approved), true);
  assert.equal(isApprovedSlideImage('gen_img_other', approved), false);
  assert.equal(isApprovedSlideImage('https://outside/image.png', approved), false);
  assert.equal(isApprovedSlideImage('/api/classroom/media/../secret', approved), false);
  const clean = sanitizeSlidePayload({ elements: [{ src: 'gen_img_fractions', link: { target: 'https://outside' } }, { src: 'https://outside/image.png' }] }, approved);
  assert.equal(clean.elements[0].src, 'gen_img_fractions'); assert.equal(clean.elements[0].link, undefined); assert.equal(clean.elements[1].src, '');
});

test('generated image maps use placeholder src independently of renderer element ID', async () => {
  const raw = { ...outline(), type: 'slide', mediaGenerations: [{ type: 'image', elementId: 'gen_img_fractions', prompt: '同样大小的整体', aspectRatio: '1:1' }] };
  const ai = model({ elements: [
    { id: 'canvas-title', type: 'text', left: 20, top: 20, width: 800, height: 80, content: '<p>先确认同样大小的整体</p>' },
    { id: 'canvas-image', type: 'image', left: 20, top: 100, width: 400, height: 400, src: 'gen_img_fractions' },
    { id: 'foreign', type: 'image', left: 500, top: 100, width: 100, height: 100, src: 'https://bad.example/image.png' },
  ] }, [{ type: 'text', content: '图片会由课堂服务准备。' }]);
  const scene = await generateScene({ ...course, mediaEnabled: true }, raw, ai.call);
  const images = scene.content.canvas.elements.filter(item => item.type === 'image');
  assert.equal(images.length, 1); assert.equal(images[0].src, 'gen_img_fractions');
  assert.notEqual(images[0].id, 'gen_img_fractions');
});

/** 将浏览器Blob/Worker接到真正Node线程，用真实JS解析和死循环验证超时；不会连接任何网络。 */
async function runnerHarness() {
  const blobs = new Map(); let blobId = 0;
  class BrowserWorker {
    constructor(url) {
      this.killed = false; this.thread = null; this.onmessage = null; this.onerror = null;
      const blob = blobs.get(url);
      void blob.text().then(source => {
        if (this.killed) return;
        const prelude = "import {parentPort} from 'node:worker_threads';globalThis.self=globalThis;self.postMessage=(value)=>parentPort.postMessage(value);\n";
        this.thread = new NodeWorker(new URL('data:text/javascript;base64,' + Buffer.from(prelude + source).toString('base64')));
        this.thread.on('message', data => this.onmessage?.({ data }));
        this.thread.on('error', error => this.onerror?.({ message: error.message, preventDefault() {} }));
      });
    }
    terminate() { this.killed = true; void this.thread?.terminate(); }
  }
  const window = { addEventListener() {} };
  const source = await readFile(new URL('../dist/runtime/code-runner.js', import.meta.url), 'utf8');
  vm.runInNewContext(source, { window, Blob, Worker: BrowserWorker, URL: {
    createObjectURL(blob) { const id = `blob:${++blobId}`; blobs.set(id, blob); return id; },
    revokeObjectURL(id) { blobs.delete(id); },
  }, setTimeout, clearTimeout, performance, Element: class {} });
  return { api: window.EducationAgentCode, blobs };
}

test('JavaScript runner executes real code, surfaces syntax errors, blocks network, and releases Blob URLs', async () => {
  const { api, blobs } = await runnerHarness();
  const success = await api.run('console.log([1,2,3].map(n=>n*2));');
  assert.equal(success.status, 'success'); assert.equal(success.stdout, '[2,4,6]'); assert.equal(blobs.size, 0);
  const syntax = await api.run('const = bad;'); assert.equal(syntax.status, 'error');
  const network = await api.run('await fetch("https://not-contacted.example/");');
  assert.equal(network.status, 'error'); assert.match(network.error, /不能访问网络/); assert.equal(blobs.size, 0);
});

test('infinite student loop is terminated while the host remains responsive; rerun cancels previous job', async () => {
  const { api, blobs } = await runnerHarness();
  let hostTick = false; setTimeout(() => { hostTick = true; }, 20);
  const timeout = await api.run('while(true) {}', { timeoutMs: 150 });
  assert.equal(timeout.status, 'timeout'); assert.equal(hostTick, true); assert.equal(blobs.size, 0);
  const old = api.run('while(true) {}');
  const next = api.run('console.log("再试成功");');
  assert.equal((await old).status, 'cancelled'); assert.equal((await next).stdout, '再试成功'); assert.equal(blobs.size, 0);
});
