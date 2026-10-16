import { describe, expect, it } from 'vitest';
import JSZip from 'jszip';
import { collectSnapshot } from '../src/classroom/export/snapshot';
import { courseToMarkdown } from '../src/classroom/export';
import { buildPptx } from '../src/classroom/export/pptx';
import type { PortableEnvelope } from '../src/classroom/export/contract';

function thinkingCourse(): PortableEnvelope {
  return { format: 'educationagent.classroom', version: 1, course: {
    id: 'thinking-course', title: '体积思考', topic: '体积', grade: 5, subject: '数学', domain: 'general',
    status: 'ready', outlines: [], messages: [], progress: { sceneIndex: 0, actionIndex: 0 },
    scenes: [{ id: 'thinking-page', title: '说说你的思路', order: 0, type: 'quiz', actions: [],
      content: { type: 'quiz', questions: [], thinking: [{ id: 't1', question: '长方体的体积为什么要把长宽高相乘？',
        review: { result: 'RISKY_THINK', solvedAnswer: '不应携带的审核答案' }, answer: '不应携带的答案' }] } }],
  } } as unknown as PortableEnvelope;
}

describe('不计分思考题跨导出格式保留', () => {
  it('深副本只保留思考题题干，讲稿可读且不携带审核答案', async () => {
    const source = thinkingCourse();
    const snapshot = await collectSnapshot(source, { format: 'zip', includeNarration: false });
    expect(snapshot.envelope.course.scenes[0].content.thinking).toEqual([
      { id: 't1', question: '长方体的体积为什么要把长宽高相乘？' },
    ]);
    const markdown = courseToMarkdown(snapshot.envelope.course);
    expect(markdown).toContain('长方体的体积为什么要把长宽高相乘？');
    expect(markdown).toContain('思考题（不计分）');
    expect(JSON.stringify(snapshot)).not.toContain('不应携带');
    expect(JSON.stringify(source)).toContain('不应携带');
  });

  it('只有思考题的PPTX页面显示题干，不误报成互动说明页', async () => {
    const snapshot = await collectSnapshot(thinkingCourse(), { format: 'pptx', includeNarration: false });
    const output = await buildPptx(snapshot.envelope.course);
    const zip = await JSZip.loadAsync(await output.blob.arrayBuffer());
    const slide = await zip.file('ppt/slides/slide1.xml')!.async('string');
    expect(slide).toContain('长方体的体积为什么要把长宽高相乘？');
    expect(slide).toContain('不计分');
    expect(slide).not.toContain('这一页包含互动活动');
    expect(output.warnings).toEqual([]);
  });

  it('白板开放笔迹导出成透明填充的SVG，不出现白色覆盖区域', async () => {
    const source = thinkingCourse();
    source.course.runtime = { 'thinking-page': { whiteboard: { id: 'board', isOpen: true, canvas: {
      id: 'board', viewportSize: 1280, viewportRatio: 0.5625, elements: [{ id: 'ink-test', type: 'shape',
        left: 10, top: 10, width: 100, height: 100, rotate: 0, viewBox: [100, 100], path: 'M5 5 L50 90 L95 5',
        fill: 'transparent', outline: { color: '#334455', width: 3, style: 'solid' }, fixedRatio: false }],
    } } } };
    const output = await buildPptx(source.course);
    const zip = await JSZip.loadAsync(await output.blob.arrayBuffer());
    const svgs = await Promise.all(Object.keys(zip.files).filter(name => name.endsWith('.svg')).map(name => zip.file(name)!.async('string')));
    expect(svgs.some(svg => svg.includes('M5 5 L50 90 L95 5') && svg.includes('fill="none"') && svg.includes('stroke="#334455"'))).toBe(true);
  });
});
