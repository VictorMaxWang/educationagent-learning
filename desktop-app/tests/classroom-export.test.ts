import { afterEach, describe, expect, it, vi } from 'vitest';
import JSZip from 'jszip';
import { JSDOM } from 'jsdom';
import type { Slide } from '@educationagent/dsl';
import { collectSnapshot, inlineInteractiveHtml, inlineSnapshotAssets, readBoundedResponse } from '../src/classroom/export/snapshot';
import { assembleHtml, buildOfflineHtml, serializeScriptJson } from '../src/classroom/export/html';
import { importCourseFile, inspectZipDirectory, prepareImportedImages, readPortableFile } from '../src/classroom/export/importCourse';
import { buildStoredZip } from '../src/classroom/export/storedZip';
import { buildPptx } from '../src/classroom/export/pptx';
import { exportCourse } from '../src/classroom/export';
import { canonicalAnswer } from '../src/classroom/export/quiz';
import { playOfflineActions, type OfflinePlaybackPorts } from '../src/classroom/export/playback';
import type { TeachingAction } from '../src/classroom/types';
import { validateEnvelope, type PortableEnvelope } from '../src/classroom/export/contract';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

vi.mock('../src/classroom/api', () => ({ classroomRequest: vi.fn(), synthesizeSpeech: vi.fn(async () => ({ segments: [{ text: '配音', url: '/api/classroom/voice/audio/audio1.mp3' }] })) }));
const { classroomRequest, synthesizeSpeech } = await import('../src/classroom/api');
const pixel = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/5WQAAAAASUVORK5CYII=';
const canvas: Slide = {
  id: 'canvas1', viewportSize: 1280, viewportRatio: 0.5625,
  theme: { backgroundColor: '#ffffff', themeColors: ['#447755'], fontColor: '#223322', fontName: 'Microsoft YaHei' },
  elements: [
    { id: 'text1', type: 'text', left: 40, top: 30, width: 900, height: 100, rotate: 0, content: '<p style="font-size:36px">分数与田地面积</p>', defaultFontName: 'Microsoft YaHei', defaultColor: '#223322' },
    { id: 'image1', type: 'image', left: 40, top: 180, width: 120, height: 120, rotate: 0, src: pixel, fixedRatio: true },
    { id: 'chart1', type: 'chart', left: 220, top: 170, width: 440, height: 270, rotate: 0, chartType: 'column', data: { labels: ['第一组', '第二组'], legends: ['面积'], series: [[2, 4]] }, themeColors: ['#447755'] },
    { id: 'table1', type: 'table', left: 700, top: 170, width: 400, height: 200, rotate: 0, colWidths: [0.5, 0.5], cellMinHeight: 40, outline: { color: '#777777', width: 1 }, data: [[{ id: 'c1', text: '作物', colspan: 1, rowspan: 1 }, { id: 'c2', text: '面积', colspan: 1, rowspan: 1 }], [{ id: 'c3', text: '水稻', colspan: 1, rowspan: 1 }, { id: 'c4', text: '二分之一', colspan: 1, rowspan: 1 }]] },
    { id: 'code1', type: 'code', left: 40, top: 470, width: 800, height: 140, rotate: 0, language: 'javascript', fontSize: 20, showLineNumbers: true, lines: [{ id: 'l1', content: 'if (x < 2) console.log("观察结果");' }] },
  ],
};
function envelope(interactive = false): PortableEnvelope {
  return { format: 'educationagent.classroom', version: 1, course: {
    id: 'course1', title: '分数与田地', topic: '分数', grade: 4, subject: '数学', domain: 'general', status: 'ready', outlines: [], progress: { sceneIndex: 1, actionIndex: 3 }, messages: [{ id: 'private1', speaker: 'user', text: '不应导出的私人对话' }],
    runtime: { s1: { whiteboard: { id: 'board1', isOpen: true, canvas: { ...canvas, id: 'board1', elements: [canvas.elements[0]] } } } },
    scenes: [
      { id: 's1', title: '田地面积', order: 0, type: 'slide', content: { type: 'slide', canvas: structuredClone(canvas) }, actions: [{ type: 'speech', text: '把田地平均分成四份，每份是四分之一。' }] },
      { id: 's2', title: '分数练习', order: 1, type: 'quiz', content: { type: 'quiz', questions: [{ id: 'q1', no: 1, type: 'single', question: '四份中的一份是多少？', options: [{ label: '四分之一', value: 'A' }, { label: '二分之一', value: 'B' }], answer: ['A'], correctAnswer: ['A'], explanation: '整体平均分成四份。' }] }, actions: [] },
      ...(interactive ? [{ id: 's3', title: '动手分田地', order: 2, type: 'interactive' as const, content: { type: 'interactive' as const, html: '<button id="count">点一次分一份</button><script>let n=0;document.getElementById("count").onclick=()=>document.getElementById("count").textContent=String(++n)</script>' }, actions: [] }] : []),
    ],
  } };
}
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('统一导出快照', () => {
  it('选项与后端真实题库一致：value为答案键，label为正文', () => {
    const question = envelope().course.scenes[1].content.questions![0];
    expect(canonicalAnswer([question.options[0].value])).toBe(canonicalAnswer(question.correctAnswer));
    expect(canonicalAnswer([question.options[0].label])).not.toBe(canonicalAnswer(question.correctAnswer));
  });
  it('移除私人对话并保留人工白板、答案和图片；默认不调用语音', async () => {
    const source = envelope();
    const snapshot = await collectSnapshot(source, { format: 'html', includeNarration: false });
    expect(snapshot.envelope.course.messages).toBeUndefined();
    expect(source.course.messages[0].text).toContain('私人对话');
    expect(snapshot.envelope.course.runtime?.s1.whiteboard?.canvas.elements).toHaveLength(1);
    expect(snapshot.envelope.course.scenes[1].content.questions?.[0].correctAnswer).toEqual(['A']);
    expect(snapshot.assets.size).toBe(1);
    expect(synthesizeSpeech).not.toHaveBeenCalled();
    const inline = await inlineSnapshotAssets(snapshot);
    expect(inline.course.scenes[0].content.canvas?.elements.find(item => item.type === 'image')).toMatchObject({ src: pixel });
    expect(snapshot.envelope.course.progress).toEqual({ sceneIndex: 0, actionIndex: 0 });
  });
  it('配音仅在明确选择时调用，并包含真实音频字节', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new Uint8Array([0x49, 0x44, 0x33, 1]), { headers: { 'content-type': 'audio/mpeg' } })));
    const snapshot = await collectSnapshot(envelope(), { format: 'html', includeNarration: true });
    expect(synthesizeSpeech).toHaveBeenCalledTimes(1);
    expect(snapshot.narration.s1['0'][0].asset).toMatch(/\.mp3$/);
    const html = await buildOfflineHtml(snapshot, { script: 'window.exportFixture=true', css: 'body{color:black}' });
    expect(await html.text()).toContain('data:audio/mpeg;base64,SUQzAQ==');
  });
  it('不请求任意外部图片，缺失资源给出真实警告', async () => {
    const source = envelope();
    (source.course.scenes[0].content.canvas!.elements[1] as { src: string }).src = 'http://127.0.0.1:8888/private';
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const snapshot = await collectSnapshot(source, { format: 'html', includeNarration: false });
    expect(fetch).not.toHaveBeenCalled(); expect(snapshot.warnings.join('')).toContain('未打包');
  });
  it('实际流超过限制时中止读取，不依赖Content-Length', async () => {
    const cancel = vi.fn();
    const response = new Response(new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(7)); controller.enqueue(new Uint8Array(7)); }, cancel }));
    await expect(readBoundedResponse(response, 10)).rejects.toThrow('上限');
    expect(cancel).toHaveBeenCalled();
  });
  it('互动页面保留按钮脚本并内联图片，剥离外部脚本/样式和跳转', async () => {
    const dom = new JSDOM(); vi.stubGlobal('DOMParser', dom.window.DOMParser);
    const warnings: string[] = [];
    const html = await inlineInteractiveHtml('<link href="https://bad/style.css"><script src="https://bad/a.js"></script><img src="/api/classroom/media/p.jpg"><button onclick="this.textContent=1">开始</button><a href="https://bad">外链</a>', async () => pixel, warnings);
    expect(html).toContain(pixel); expect(html).toContain('onclick="this.textContent=1"');
    expect(html).not.toContain('https://bad'); expect(warnings.length).toBeGreaterThan(0); dom.window.close();
  });
});

describe('单文件 HTML 与课堂 ZIP', () => {
  it('公共ZIP导出入口包含统一清单、课程、播放器、资产与独立沙箱互动文件', async () => {
    const dom = new JSDOM(); vi.stubGlobal('DOMParser', dom.window.DOMParser);
    const source = envelope(true);
    vi.mocked(classroomRequest).mockResolvedValueOnce(source);
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(url.endsWith('.css') ? 'body{margin:0}' : 'window.playerFixture=true', { headers: { 'content-type': url.endsWith('.css') ? 'text/css' : 'text/javascript' } })));
    const result = await exportCourse(source.course, { format: 'zip', includeNarration: false });
    const archive = await JSZip.loadAsync(await result.blob.arrayBuffer());
    expect(['manifest.json', 'course.json', 'index.html', '讲稿.md', 'README.txt'].every(name => !!archive.file(name))).toBe(true);
    const activity = await archive.file('interactive/page-3.html')!.async('string');
    expect(activity).toContain('sandbox="allow-scripts"');
    expect(activity).not.toContain('allow-same-origin');
    expect(activity).not.toContain('<button');
    expect(activity).toContain('&lt;button');
    const restored = await readPortableFile(new File([result.blob], result.fileName));
    expect(restored.course.scenes).toHaveLength(3);
    expect(restored.course.scenes[0].content.canvas?.elements[1]).toMatchObject({ src: pixel });
    dom.window.close();
  });
  it('脚本结束标签不能从课程数据逃逸，CSP禁止在线请求', async () => {
    const data = { envelope: envelope(), narration: {}, warnings: ['</script><script>alert(1)</script>'] };
    const encoded = serializeScriptJson(data);
    expect(encoded).not.toContain('</script>'); expect(JSON.parse(encoded)).toEqual(data);
    const html = await assembleHtml(data, { script: 'window.ready=true', css: '' }).text();
    expect(html).toContain("connect-src 'none'");
    expect(html).toContain('worker-src blob:');
    expect(html).not.toContain('unsafe-eval');
    expect(html).not.toContain('<script>alert(1)');
    expect(html).toContain('educationagent-course-data');
  });
  it('ZIP实际字节能打开并重新导入，图片还原、白板与正确答案保留', async () => {
    const snapshot = await collectSnapshot(envelope(), { format: 'zip', includeNarration: false });
    const archive = await buildStoredZip([{ path: 'course.json', data: JSON.stringify(snapshot.envelope) }, ...[...snapshot.assets].map(([path, data]) => ({ path, data }))]);
    const zip = await JSZip.loadAsync(await archive.arrayBuffer(), { checkCRC32: true });
    expect(zip.file('course.json')).not.toBeNull();
    const imported = await readPortableFile(new File([archive], '课堂.educationagent.zip'));
    expect(imported.course.scenes[0].content.canvas?.elements[1]).toMatchObject({ src: pixel });
    expect(imported.course.runtime?.s1.whiteboard?.id).toBe('board1');
    expect(imported.course.scenes[1].content.questions?.[0].answer).toEqual(['A']);
  });
  it('拒绝目录遍历ZIP和伪报解压长度的条目', async () => {
    const bad = new JSZip(); bad.file('../course.json', '{}');
    await expect(readPortableFile(new File([await bad.generateAsync({ type: 'uint8array' })], 'bad.zip'))).rejects.toThrow('不安全');
    const archive = await buildStoredZip([{ path: 'course.json', data: JSON.stringify(envelope()) }]);
    const bytes = new Uint8Array(await archive.arrayBuffer());
    const view = new DataView(bytes.buffer);
    for (let offset = 0; offset < bytes.length - 46; offset++) {
      if (view.getUint32(offset, true) === 0x02014b50) { view.setUint32(offset + 24, 1, true); break; }
    }
    await expect(readPortableFile(new File([bytes], 'bad-size.zip'))).rejects.toThrow();
  });
  it('拒绝ZIP64/过大目录，JSON只接受指定课程版本', () => {
    expect(() => inspectZipDirectory(new ArrayBuffer(25))).toThrow();
    expect(() => validateEnvelope({ ...envelope(), version: 9 })).toThrow('版本');
  });
});

describe('真实 PPTX 内容', () => {
  it('文字、图表、表格可编辑，包含图片和讲稿备注及保存白板', async () => {
    const result = await buildPptx(envelope(true).course);
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer());
    expect(Object.keys(zip.files).filter(name => /^ppt\/slides\/slide\d+\.xml$/.test(name))).toHaveLength(4);
    const slide = await zip.file('ppt/slides/slide1.xml')!.async('string');
    expect(slide).toContain('分数与田地面积'); expect(slide).toContain('<a:tbl>'); expect(slide).toContain('<c:chart');
    expect(slide).toContain('if (x &lt; 2) console.log');
    const chart = await zip.file('ppt/charts/chart1.xml')!.async('string');
    expect(chart).toContain('第一组'); expect(chart).toContain('<c:barDir val="bar"/>');
    expect(Object.keys(zip.files).some(name => /^ppt\/media\//.test(name))).toBe(true);
    expect(await zip.file('ppt/notesSlides/notesSlide1.xml')!.async('string')).toContain('平均分成四份');
    expect(await zip.file('ppt/slides/slide4.xml')!.async('string')).toContain('互动活动');
    expect(await zip.file('ppt/slides/slide3.xml')!.async('string')).toContain('A. 四分之一');
    const directory = fileURLToPath(new URL('../../target/classroom-export-qa/', import.meta.url));
    await mkdir(directory, { recursive: true });
    await writeFile(`${directory}/rural-course.pptx`, new Uint8Array(await result.blob.arrayBuffer()));
  });
});

describe('导入图片在本站落盘后再提交课件', () => {
  it('一页四张300KB图片不会作为base64使Java单页超限，重复引用仅上传一次', async () => {
    const source = envelope();
    const png = Uint8Array.from(atob(pixel.split(',')[1]), c => c.charCodeAt(0));
    const images = Array.from({ length: 4 }, (_, index) => {
      const bytes = new Uint8Array(300 * 1024); bytes.set(png); bytes[bytes.length - 1] = index;
      return `data:image/png;base64,${Buffer.from(bytes).toString('base64')}`;
    });
    source.course.scenes[0].content.canvas!.elements = [...images, images[0]].map((src, index) => ({ id: `picture-${index}`, type: 'image', left: index * 100, top: 0, width: 100, height: 100, rotate: 0, fixedRatio: true, src }));
    expect(JSON.stringify(source.course.scenes[0]).length).toBeGreaterThan(1_500_000);
    let uploads = 0;
    let saved = '';
    vi.mocked(classroomRequest).mockImplementation(async (url, options = {}) => {
      if (url === '/media/upload') {
        uploads++;
        expect(options.body).toBeInstanceOf(FormData);
        expect((options.body as FormData).get('file')).toBeInstanceOf(Blob);
        return { url: `/api/classroom/media/${String(uploads).padStart(64, '0')}.jpg` } as never;
      }
      if (url === '/courses/import') { saved = String(options.body); return source.course as never; }
      throw new Error('unexpected request');
    });
    await importCourseFile(new File([JSON.stringify(source)], 'course.json'));
    expect(uploads).toBe(4); expect(saved).not.toContain('data:image');
    expect(JSON.parse(saved).course.scenes[0].content.canvas.elements[0].src).toMatch(/^\/api\/classroom\/media\//);
    expect(saved.length).toBeLessThan(50_000);
  });
  it('取消上传后不提交课程，也不继续上传其他图片', async () => {
    const controller = new AbortController();
    vi.mocked(classroomRequest).mockImplementation(async url => {
      expect(url).toBe('/media/upload'); controller.abort();
      return { url: `/api/classroom/media/${'a'.repeat(64)}.jpg` } as never;
    });
    await expect(prepareImportedImages(envelope(), controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(classroomRequest).toHaveBeenCalledTimes(1);
  });
});

describe('离线讲授动作连续播放', () => {
  function ports(hasAudio = true): OfflinePlaybackPorts {
    return { caption: vi.fn(), position: vi.fn(), hasAudio: () => hasAudio, speech: vi.fn(async () => {}), control: vi.fn(async () => {}), visual: vi.fn(), warning: vi.fn() };
  }
  it('讨论暂停后从下一项继续，后面的配音和互动动作可达', async () => {
    const actions: TeachingAction[] = [{ type: 'speech', text: '前半段' }, { type: 'discussion', topic: '为什么？' }, { type: 'widget_setState', state: { controls: { '#parts': { value: 4 } } } }, { type: 'speech', text: '后半段' }];
    const output = ports(); const signal = new AbortController().signal;
    const first = await playOfflineActions(actions, 0, output, signal);
    expect(first).toEqual({ nextAction: 2, reason: 'discussion' });
    const second = await playOfflineActions(actions, first.nextAction, output, signal);
    expect(second.reason).toBe('done'); expect(output.control).toHaveBeenCalledTimes(1);
    expect(vi.mocked(output.speech).mock.calls.map(call => call[0])).toEqual([0, 3]);
  });
  it('没有配音时保留文字停顿，继续后执行白板而不是静默丢掉', async () => {
    const actions: TeachingAction[] = [{ type: 'speech', text: '先看文字' }, { type: 'wb_open' }, { type: 'wb_draw_text', content: '平均分', x: 10, y: 20 }];
    const output = ports(false); const signal = new AbortController().signal;
    const first = await playOfflineActions(actions, 0, output, signal);
    expect(first).toEqual({ nextAction: 1, reason: 'text' });
    await playOfflineActions(actions, first.nextAction, output, signal);
    expect(output.control).toHaveBeenCalledTimes(2); expect(output.speech).not.toHaveBeenCalled();
  });
  it('互动执行失败不推进后续配音，未知动作给出可见警告', async () => {
    const output = ports(); vi.mocked(output.control).mockRejectedValueOnce(new Error('没有收到执行确认'));
    await expect(playOfflineActions([{ type: 'widget_highlight', target: '#absent' }, { type: 'speech', text: '不能说成功' }], 0, output, new AbortController().signal)).rejects.toThrow('执行确认');
    expect(output.speech).not.toHaveBeenCalled();
    await playOfflineActions([{ type: 'future_action' } as unknown as TeachingAction], 0, output, new AbortController().signal);
    expect(output.warning).toHaveBeenCalledTimes(1);
  });
});

describe('可在浏览器独立打开的完整文件', () => {
  it('实际内联预构建播放器、图表和字体，并保存独立验收夹具', async () => {
    const dom = new JSDOM(); vi.stubGlobal('DOMParser', dom.window.DOMParser);
    const source = envelope(true);
    source.course.scenes[2].content.html = '<label>等分份数<input id="parts" type="range" min="1" max="8" value="1"></label><output id="part-count">1</output><button id="count">点一次分一份</button><script>document.getElementById("parts").oninput=()=>document.getElementById("part-count").textContent=document.getElementById("parts").value;let n=0;document.getElementById("count").onclick=()=>document.getElementById("count").textContent=String(++n)</script>';
    source.course.scenes[2].actions = [{ id: 'set-parts', type: 'widget_setState', state: { controls: { '#parts': { value: 4 } } } }, { id: 'think', type: 'discussion', topic: '现在把整体分成了几份？' }, { id: 'draw', type: 'wb_draw_text', content: '整体平均分成四份', elementId: 'demo-text', x: 80, y: 80 }, { id: 'after', type: 'speech', text: '讨论后这段讲解仍然可以继续。' }];
    const runner = await readFile(new URL('../../course-engine/runtime/code-runner.js', import.meta.url), 'utf8');
    source.course.scenes.push({ id: 's4', title: '代码练习', order: 3, type: 'interactive', content: { type: 'interactive', widgetType: 'code', html: `<h2>观察加法结果</h2><textarea id="code-input">console.log(2 + 2);</textarea><button id="run-code" data-expected-output="4">运行</button><button id="stop-code">停止</button><pre id="code-output"></pre><span id="code-status"></span><script>${runner}</script>` }, actions: [] });
    const snapshot = await collectSnapshot(source, { format: 'html', includeNarration: false });
    const script = await readFile(new URL('../public/educationagent-export/player.js', import.meta.url), 'utf8');
    const css = await readFile(new URL('../public/educationagent-export/player.css', import.meta.url), 'utf8');
    const result = await buildOfflineHtml(snapshot, { script, css });
    const text = await result.text();
    expect(text).toContain('data:font/');
    expect(text).not.toMatch(/<script[^>]+src=/);
    expect(text).not.toMatch(/<link[^>]+href=/);
    const directory = fileURLToPath(new URL('../../target/classroom-export-qa/', import.meta.url));
    await mkdir(directory, { recursive: true });
    await writeFile(`${directory}/rural-course.html`, text, 'utf8');
    dom.window.close();
  });
});
