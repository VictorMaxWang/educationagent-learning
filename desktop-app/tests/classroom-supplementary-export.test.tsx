import { afterEach, describe, expect, it, vi } from 'vitest';
import JSZip from 'jszip';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { JSDOM } from 'jsdom';
import { canonicalVideoUrl, portableSupplementaryResources } from '../src/classroom/export/supplementary';
import { validateEnvelope, type PortableEnvelope } from '../src/classroom/export/contract';
import { collectSnapshot } from '../src/classroom/export/snapshot';
import { buildOfflineHtml } from '../src/classroom/export/html';
import { buildStoredZip } from '../src/classroom/export/storedZip';
import { readPortableFile } from '../src/classroom/export/importCourse';
import { courseToMarkdown } from '../src/classroom/export';
import { buildPptx } from '../src/classroom/export/pptx';
import SupplementaryLinks from '../src/classroom/export/SupplementaryLinks';

vi.mock('../src/classroom/api', () => ({ classroomRequest: vi.fn(), synthesizeSpeech: vi.fn() }));

const video = {
  sceneId: 's1', id: 'BV1xx411c7mD:2', title: '诗文诵读示范', author: '课程作者',
  url: 'https://www.bilibili.com/video/BV1xx411c7mD/?p=2', durationSeconds: 125,
  purpose: '诵读节奏', goals: ['节奏与情感'], reason: '对照本课诗句观察停顿', checkedAt: '2026-10-16T08:30:00Z',
};
function envelope(): PortableEnvelope {
  return { format: 'educationagent.classroom', version: 1, course: {
    id: 'c1', title: '沁园春·长沙', topic: '诵读', grade: 10, subject: '语文', domain: 'general',
    status: 'ready', outlines: [], messages: [], progress: { sceneIndex: 0, actionIndex: 0 },
    supplementaryResources: [structuredClone(video)],
    scenes: [{ id: 's1', title: '秋景与诵读', type: 'slide', order: 0, content: { type: 'slide' }, actions: [{ type: 'speech', text: '先读一读，再说说感受。' }] }],
  } };
}
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('补充资料导出边界', () => {
  it.each([
    'javascript:alert(1)', 'data:text/html,hello', 'https://www.bilibili.com.evil.test/video/BV1xx411c7mD/',
    'https://www.bilibili.com@evil.test/video/BV1xx411c7mD/', 'https://evil.test@www.bilibili.com/video/BV1xx411c7mD/',
    'https://www.bilibili.com:8443/video/BV1xx411c7mD/', 'https://www.bilibili.com/video/BV1xx411c7mD/?redirect=https://evil.test',
    'https://www.bilibili.com/video/BV1xx411c7mD/#bad', 'https://www.bilibili.com\\@evil.test/video/BV1xx411c7mD/',
    'https://www.bilibili.com/video/%42V1xx411c7mD/', 'https://www.bilibili.com/video/BV1xx411c7mD/?p=0',
    '//www.bilibili.com/video/BV1xx411c7mD/', 'https://www.bilibili.com/video/BV1xx411c7mD/\n',
  ])('拒绝危险或伪装视频地址 %s', async url => {
    expect(canonicalVideoUrl(url)).toBeNull();
    const source = envelope(); source.course.supplementaryResources![0].url = url;
    expect(() => validateEnvelope(source)).toThrow('Bilibili');
    await expect(collectSnapshot(source, { format: 'zip', includeNarration: false })).rejects.toThrow('Bilibili');
  });

  it('规范化实际BV链接、保留分P、剥离审计和状态，原始对象不改写', async () => {
    const source = envelope();
    Object.assign(source.course.supplementaryResources![0], { audit: { token: 'private-sentinel' }, verified: true, status: 'ready', modelEvidence: { prompt: 'secret-prompt' } });
    Object.assign(source.course, { recommendationAudit: 'private-course-sentinel' });
    Object.assign(source, { audit: 'private-envelope-sentinel' });
    const snapshot = await collectSnapshot(source, { format: 'zip', includeNarration: false });
    expect(snapshot.envelope.course.supplementaryResources).toEqual([{ ...video, resourceStatus: 'historical' }]);
    const serialized = JSON.stringify(snapshot.envelope);
    expect(serialized).not.toContain('private-'); expect(serialized).not.toContain('secret-prompt'); expect(serialized).not.toContain('verified');
    expect(JSON.stringify(source)).toContain('private-sentinel');
    expect(canonicalVideoUrl('https://bilibili.com/video/BV1xx411c7mD')).toBe('https://www.bilibili.com/video/BV1xx411c7mD/?p=1');
  });

  it('同页重复链接去重；缺失页面、未知时长和过大列表被拒绝', () => {
    expect(portableSupplementaryResources([video, video], new Set(['s1']))).toHaveLength(1);
    expect(() => portableSupplementaryResources([{ ...video, sceneId: 'other' }], new Set(['s1']))).toThrow();
    expect(() => portableSupplementaryResources([{ ...video, durationSeconds: 0 }], new Set(['s1']))).toThrow('时长');
    expect(() => portableSupplementaryResources(Array(193).fill(video), new Set(['s1']))).toThrow('列表');
  });

  it('JSON与ZIP导入只保留历史入口，HTML带安全外跳而不下载视频', async () => {
    vi.stubGlobal('fetch', vi.fn(() => { throw new Error('supplementary export must not fetch'); }));
    const snapshot = await collectSnapshot(envelope(), { format: 'zip', includeNarration: false });
    expect(snapshot.assets.size).toBe(0);
    const json = await readPortableFile(new File([JSON.stringify(snapshot.envelope)], '课程.json'));
    expect(json.course.supplementaryResources![0].resourceStatus).toBe('historical');
    const archive = await buildStoredZip([{ path: 'course.json', data: JSON.stringify(snapshot.envelope) }]);
    const restored = await readPortableFile(new File([archive], '课程.zip'));
    expect(restored.course.supplementaryResources).toEqual(json.course.supplementaryResources);
    const html = await buildOfflineHtml(snapshot, { script: '', css: '' });
    const document = new JSDOM(await html.text());
    const exported = JSON.parse(document.window.document.getElementById('educationagent-course-data')!.textContent!);
    expect(exported.envelope.course.supplementaryResources[0].url).toBe(video.url);
    document.window.close();
    const markup = renderToStaticMarkup(createElement(SupplementaryLinks, { course: restored.course, sceneId: 's1' }));
    expect(markup).toContain('需联网'); expect(markup).toContain('noopener noreferrer'); expect(markup).toContain('target="_blank"');
    expect(markup).toContain(video.url.replace('&', '&amp;'));
    expect(renderToStaticMarkup(createElement(SupplementaryLinks, { course: restored.course, sceneId: 's2' }))).toBe('');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('讲稿与PPTX备注保留链接，标题的Markdown不能注入另一个外链', async () => {
    const source = envelope(); source.course.supplementaryResources![0].title = '诵读](javascript:alert(1))<img src=x>';
    const snapshot = await collectSnapshot(source, { format: 'pptx', includeNarration: false });
    const markdown = courseToMarkdown(snapshot.envelope.course);
    expect(markdown).toContain(video.url); expect(markdown).toContain('需联网'); expect(markdown).toContain('诵读\\]\\(javascript:alert\\(1\\)\\)');
    expect(markdown).not.toContain('<img');
    const pptx = await buildPptx(snapshot.envelope.course);
    const archive = await JSZip.loadAsync(await pptx.blob.arrayBuffer());
    const notes = await archive.file('ppt/notesSlides/notesSlide1.xml')!.async('string');
    expect(notes).toContain(video.url); expect(notes).toContain('需联网'); expect(notes).toContain('历史入口');
  });
});
