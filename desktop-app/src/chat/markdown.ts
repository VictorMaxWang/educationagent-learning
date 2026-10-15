/**
 * 轻量 markdown 渲染（从 App.jsx 平移，供 ChatWindow 复用）
 * 逐行处理，支持标题/列表/行内代码/加粗/表格。
 * LLM 输出按不可信文本处理，先转义 HTML 防 XSS。
 */

/** 转义 HTML 防 XSS */
export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 行内样式（行内代码 / 加粗） */
export function renderInline(s: string): string {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

/** 判断行是否为表格分隔行（| --- | 或 | :---: |） */
function isTableSep(line: string): boolean {
  return line.includes('-') && /^\|?[\s:|-]+\|?$/.test(line);
}

/** 解析表格行 "| a | b |" → ['a','b'] */
function parseTableRow(line: string): string[] {
  let t = line.trim();
  if (t.startsWith('|')) t = t.slice(1);
  if (t.endsWith('|')) t = t.slice(0, -1);
  return t.split('|').map((c) => c.trim());
}

/** 渲染 markdown 表格 → HTML <table> */
function renderTable(rows: string[]): string {
  const header = parseTableRow(rows[0]);
  const bodyRows = rows.slice(2).map(parseTableRow).filter((r) => r.some((c) => c !== ''));
  const html = ['<div class="md-table-wrap"><table class="md-table"><thead><tr>'];
  header.forEach((c) => html.push(`<th>${renderInline(c)}</th>`));
  html.push('</tr></thead><tbody>');
  bodyRows.forEach((r) => {
    html.push('<tr>');
    r.forEach((c) => html.push(`<td>${renderInline(c)}</td>`));
    html.push('</tr>');
  });
  html.push('</tbody></table></div>');
  return html.join('');
}

/** 渲染代码块 → 带语言标签 + 复制按钮的 <pre><code>（code 已由外层转义） */
function renderCodeBlock(lang: string, code: string): string {
  const langLabel = lang
    ? `<span class="md-code-lang">${escapeHtml(lang)}</span>`
    : '<span class="md-code-lang">code</span>';
  return `<div class="md-code">
      <div class="md-code-head">
        ${langLabel}
        <button class="md-code-copy" type="button" title="复制代码">⧉ 复制</button>
      </div>
      <pre class="md-code-pre"><code>${code}</code></pre>
    </div>`;
}

/**
 * 轻量 markdown 渲染。
 * - 空行直接跳过 → 不产生任何垂直空位
 * - 开头/结尾 trim → 气泡顶部/底部不再被空行顶出大片留白
 * - 行间距由 CSS .md-line 统一控制；空行后的行加 .md-para 保留轻微段落感
 * - 表格块：| 开头且下一行是 |---| 分隔行 → 整块渲染为 <table>
 * - 流式中途未闭合的标记（如只有一半的 ** 或 #）不匹配正则，按纯文本显示
 */
export function renderMarkdown(text: string): string {
  if (!text) return '';
  const lines = escapeHtml(text.trim()).split('\n');
  const out: string[] = [];
  let i = 0;
  let prevBlank = true; // 首行不需要额外段落间距
  while (i < lines.length) {
    const line = lines[i].trim();
    if (!line) {
      prevBlank = true; // 空行：之后的第一个非空行视为新段落
      i++;
      continue;
    }

    // 代码块：```lang ... ``` 整块渲染为样式化代码块（含语言标签 + 复制按钮）
    if (line.startsWith('```')) {
      const lang = line.slice(3).trim();
      const codeLines: string[] = [];
      i++; // 跳过开头的 ```
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++; // 跳过结尾的 ```
      out.push(renderCodeBlock(lang, codeLines.join('\n')));
      prevBlank = true;
      continue;
    }

    // 表格块：以 | 开头且下一行是分隔行 |---|
    if (line.startsWith('|') && i + 1 < lines.length && isTableSep(lines[i + 1].trim())) {
      const tableRows: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('|') && lines[i].trim() !== '') {
        tableRows.push(lines[i].trim());
        i++;
      }
      out.push(renderTable(tableRows));
      prevBlank = true; // 表格后视为段落间隙
      continue;
    }

    const para = prevBlank ? ' md-para' : '';
    prevBlank = false; // 非空行：后续行不再是段落首行

    const h = line.match(/^(#{1,4})\s+(.*)$/);
    if (h) {
      out.push(`<div class="md-line md-h${h[1].length}${para}">${renderInline(h[2])}</div>`);
      i++;
      continue;
    }

    const b = line.match(/^[-*+]\s+(.*)$/);
    if (b) {
      out.push(`<div class="md-line md-bullet${para}">• ${renderInline(b[1])}</div>`);
      i++;
      continue;
    }

    // 有序列表：1. / 1) 开头 → 编号高亮
    const ol = line.match(/^(\d+)[.)]\s+(.*)$/);
    if (ol) {
      out.push(`<div class="md-line md-ol${para}"><span class="md-ol-num">${ol[1]}.</span> ${renderInline(ol[2])}</div>`);
      i++;
      continue;
    }

    out.push(`<div class="md-line${para}">${renderInline(line)}</div>`);
    i++;
  }
  return out.join('');
}
