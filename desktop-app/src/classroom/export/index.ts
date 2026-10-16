import type { Course } from '../types';
import { checkAbort, EXPORT_MAX_BYTES, safeFilename, type ExportOptions, type ExportResult, type PortableCourse } from './contract';
import { fetchExportSnapshot, inlineSnapshotAssets } from './snapshot';
import { buildOfflineHtml, escapeHtml, offlineCsp, readPlayerAssets } from './html';
import { buildStoredZip, type StoredZipEntry } from './storedZip';
import { answerText, QUESTION_TYPE_LABELS } from './quiz';
import { markdownResourceLabel, resourceDuration, supplementaryForScene } from './supplementary';

export type { ExportOptions, ExportProgress, ExportResult } from './contract';
export { importCourseFile, readPortableFile } from './importCourse';

function textOnly(value: string): string {
  return value.replace(/<br\s*\/?\s*>/gi, '\n').replace(/<\/(?:p|div|li)>/gi, '\n').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').trim();
}

/** 文本副本由同一快照产生，便于教师检索或打印，不另调一次模型改写内容。 */
export function courseToMarkdown(course: PortableCourse): string {
  const direction = { academic: '学科学习', wellbeing: '身心成长', exploration: '实践探索' }[course.direction || 'academic'];
  const lines = [`# ${course.title}`, '', `晓陶学伴 · ${course.grade} 年级 · ${direction}${course.direction && course.direction !== 'academic' ? '' : ` · ${course.subject}`}`, ''];
  for (const [index, scene] of course.scenes.entries()) {
    lines.push(`## ${index + 1}. ${scene.title}`, '');
    for (const element of scene.content.canvas?.elements || []) {
      if (element.type === 'text') lines.push(textOnly(element.content), '');
      if (element.type === 'latex') lines.push(`$$${element.latex}$$`, '');
    }
    for (const question of scene.content.questions || []) {
      lines.push(`【${QUESTION_TYPE_LABELS[question.type]}】${question.question}`, ...(question.options || []).map(option => `- ${option.value}. ${option.label}`));
      const answer = question.correctAnswer ?? question.answer;
      lines.push('', `参考答案：${answerText(answer) || '未附'}`, question.explanation || question.analysis || '', ...(question.gradingPoints || []).map(point => `- ${point}`), question.type === 'short_answer' ? '简答请结合参考答案与要点自查，离线不进行 AI 判分。' : '', '');
    }
    for (const item of scene.content.thinking || []) lines.push(`思考题（不计分）：${item.question}`, '先自己说说思路，不用提交。', '');
    if (scene.type === 'interactive') lines.push('互动操作见 index.html 或 interactive 文件夹。', '');
    lines.push('### 讲稿', '', ...(scene.actions || []).filter(action => action.type === 'speech' || action.type === 'discussion').flatMap(action => [String(action.text || action.content || action.prompt || action.topic || ''), '']));
    const resources = supplementaryForScene(course, scene.id);
    if (resources.length) lines.push('### 补充视频资料（需联网）', '', '以下为导出时保留的历史资料入口，未下载视频；链接当前是否可用以 Bilibili 为准。', '', ...resources.flatMap(item => [
      `- [${markdownResourceLabel(item.title)}](${item.url}) · ${markdownResourceLabel(item.author)} · ${resourceDuration(item.durationSeconds)}`,
      `  ${markdownResourceLabel([item.purpose, item.reason].filter(Boolean).join('：'))}`, '',
    ]));
  }
  return lines.join('\n');
}

export async function exportCourse(course: Course, options: ExportOptions): Promise<ExportResult> {
  checkAbort(options.signal);
  // PPTX 的讲稿在演讲者备注中；此格式不会悄悄批量生成后又丢弃语音。
  const effectiveOptions = options.format === 'pptx' ? { ...options, includeNarration: false } : options;
  const snapshot = await fetchExportSnapshot(course, effectiveOptions);
  const name = safeFilename(snapshot.envelope.course.title);
  if (options.format === 'pptx') {
    const { buildPptx } = await import('./pptx');
    const result = await buildPptx((await inlineSnapshotAssets(snapshot)).course, options);
    checkAbort(options.signal);
    return { blob: result.blob, fileName: `${name}.pptx`, warnings: [...snapshot.warnings, ...result.warnings, ...(options.includeNarration ? ['PPTX 保留文字讲稿备注；配音请使用 HTML 或 ZIP 导出'] : [])] };
  }
  const player = await readPlayerAssets(options.signal);
  options.onProgress?.({ phase: '打包离线播放器', completed: 0, total: 1 });
  const html = await buildOfflineHtml(snapshot, player, { linkedMedia: options.format === 'zip', signal: options.signal });
  checkAbort(options.signal);
  if (options.format === 'html') return { blob: html, fileName: `${name}.html`, warnings: snapshot.warnings };
  const entries: StoredZipEntry[] = [
    { path: 'manifest.json', data: JSON.stringify({ format: 'educationagent.classroom.zip', version: 1, exportedAt: new Date().toISOString(), course: 'course.json', player: 'index.html', assets: [...snapshot.assets].map(([path, blob]) => ({ path, mimeType: blob.type, bytes: blob.size })), narration: snapshot.narration, warnings: snapshot.warnings }, null, 2) },
    { path: 'course.json', data: JSON.stringify(snapshot.envelope, null, 2) },
    { path: 'index.html', data: html },
    { path: '讲稿.md', data: courseToMarkdown(snapshot.envelope.course) },
    { path: 'README.txt', data: '晓陶学伴 · 离线课堂\n\n请先完整解压，再打开 index.html；不要移动 assets 文件夹。\n课程内容、互动和已打包配音可以离线使用；AI 问答、录音识别和成绩同步需要回到在线课堂。\n补充视频仅保留历史链接，访问 Bilibili 需要联网，课包不包含视频文件。\n在在线课堂使用“导入课程”选择本 ZIP，可复制成一节新课程，不覆盖原来的课程和学习记录。\ncourse.json 含标准答案供离线练习，简答请结合参考答案与评分要点自查，离线不进行 AI 判分。\n' },
    ...[...snapshot.assets].map(([path, data]) => ({ path, data })),
  ];
  for (const [index, scene] of snapshot.envelope.course.scenes.entries()) {
    if (scene.type !== 'interactive' || !scene.content.html) continue;
    // 单独打开活动文件时也保留沙箱，不能因为离开课堂容器就把生成脚本提到顶层页面执行。
    entries.push({ path: `interactive/page-${index + 1}.html`, data: `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="${escapeHtml(offlineCsp())}"><title>${escapeHtml(scene.title)}</title><style>body{margin:0}iframe{display:block;border:0;width:100vw;height:100vh}</style><iframe sandbox="allow-scripts" referrerpolicy="no-referrer" title="${escapeHtml(scene.title)}" srcdoc="${escapeHtml(scene.content.html)}"></iframe>` });
  }
  const blob = await buildStoredZip(entries);
  checkAbort(options.signal);
  if (blob.size > EXPORT_MAX_BYTES) throw new Error('课包超过 80 MB，请取消配音或减少图片后重试');
  return { blob, fileName: `${name}.educationagent.zip`, warnings: snapshot.warnings };
}

/** 没有验证本地 Chromium/FFmpeg 渲染链时不展示假的 MP4 成功入口。 */
export function videoExportCapability(): { available: false; reason: string } {
  return { available: false, reason: '当前版本尚未接入并验证本地 MP4 渲染，可导出 HTML 或课堂 ZIP。' };
}

export function downloadExport(result: ExportResult): void {
  const url = URL.createObjectURL(result.blob);
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = result.fileName; anchor.style.display = 'none';
  document.body.appendChild(anchor); anchor.click(); anchor.remove();
  // 手机保存对话框可能稍后才读取 Blob，避免点击后立即撤销。
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
