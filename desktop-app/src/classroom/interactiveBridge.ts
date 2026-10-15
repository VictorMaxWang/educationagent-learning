import type { ActionOutcome, TeachingAction } from './types';

export const INTERACTIVE_BRIDGE_CHANNEL = 'educationagent.interactive.v1';
export const INTERACTIVE_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; worker-src blob:; connect-src 'none'; form-action 'none'; base-uri 'none'";
export interface BridgeIdentity { sceneId: string; nonce: string }
export interface BridgeResult { success: boolean; status?: ActionOutcome; reason?: string; error?: string; state?: unknown; html?: string; properties?: EditableProperties }
export interface EditableProperties {
  tagName: string;
  textContent: string;
  canEditText: boolean;
  value?: string;
  title: string;
  'aria-label': string;
  color: string;
  backgroundColor: string;
  fontSize: string;
}
export type EditablePatch = Partial<Pick<EditableProperties, 'textContent' | 'value' | 'title' | 'aria-label' | 'color' | 'backgroundColor' | 'fontSize'>>;
export interface WidgetCommand { type: string; target?: string; state?: unknown; event?: string; content?: string }
export interface BridgeMessage extends BridgeIdentity {
  channel: typeof INTERACTIVE_BRIDGE_CHANNEL;
  kind: 'ready' | 'picked' | 'state' | 'ack' | 'error';
  requestId?: string;
  selector?: string;
  summary?: string;
  properties?: EditableProperties;
  result?: BridgeResult;
  state?: Record<string, unknown>;
  error?: string;
}

/** 只接受可由 Java 从当前文档重新解析的稳定 ID，不传 nth-child 等临时 DOM 路径。 */
export function isStableSelector(value: unknown): value is string {
  return typeof value === 'string' && /^#[A-Za-z][A-Za-z0-9_-]{0,126}$/.test(value);
}
export function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
export function createBridgeNonce(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
}

/** 沙箱为 opaque origin，不能用 origin 作身份判断；窗口引用、场景和随机 nonce 必须同时匹配。 */
export function acceptsBridgeMessage(event: Pick<MessageEvent, 'source' | 'data'>, frameWindow: Window | null, identity: BridgeIdentity): event is MessageEvent<BridgeMessage> {
  if (!frameWindow || event.source !== frameWindow || !isRecord(event.data)) return false;
  const data = event.data;
  return data.channel === INTERACTIVE_BRIDGE_CHANNEL && data.sceneId === identity.sceneId && data.nonce === identity.nonce
    && ['ready', 'picked', 'state', 'ack', 'error'].includes(String(data.kind));
}

export function commandForTeachingAction(action: TeachingAction): WidgetCommand {
  const aliases: Record<string, string> = {
    widget_set_state: 'SET_WIDGET_STATE', widget_setState: 'SET_WIDGET_STATE', SET_WIDGET_STATE: 'SET_WIDGET_STATE',
    widget_reset: 'RESET_WIDGET', RESET_WIDGET: 'RESET_WIDGET', RESET_WIDGET_STATE: 'RESET_WIDGET',
    widget_trigger: 'TRIGGER_WIDGET', TRIGGER_WIDGET: 'TRIGGER_WIDGET', TRIGGER_WIDGET_EVENT: 'TRIGGER_WIDGET',
    widget_highlight: 'HIGHLIGHT_ELEMENT', HIGHLIGHT_ELEMENT: 'HIGHLIGHT_ELEMENT',
    widget_reveal: 'REVEAL_ELEMENT', REVEAL_ELEMENT: 'REVEAL_ELEMENT',
    widget_annotation: 'ANNOTATE_ELEMENT', ANNOTATE_ELEMENT: 'ANNOTATE_ELEMENT',
  };
  const type = aliases[String(action.type)];
  if (!type) throw new Error(`不支持的互动操作：${String(action.type)}`);
  const rawTarget = action.selector ?? action.target ?? action.elementId ?? action.componentId;
  const target = typeof rawTarget === 'string' ? (rawTarget.startsWith('#') ? rawTarget : `#${rawTarget}`) : undefined;
  if (target !== undefined && !isStableSelector(target)) throw new Error('互动操作必须指定唯一的稳定 #id');
  return { type, target, state: action.state ?? (action.value !== undefined ? { value: action.value } : undefined), event: typeof action.event === 'string' ? action.event : undefined, content: typeof action.content === 'string' ? action.content : undefined };
}

/** 表单和 iframe 内部各校验一次，避免通过 message 绕过属性白名单。 */
export function validateEditablePatch(patch: unknown): patch is EditablePatch {
  if (!isRecord(patch) || Object.keys(patch).length === 0) return false;
  return Object.entries(patch).every(([key, value]) => {
    if (typeof value !== 'string' || value.length > 2000) return false;
    if (['textContent', 'value', 'title', 'aria-label'].includes(key)) return true;
    if (['color', 'backgroundColor'].includes(key)) return value === '' || /^(?:#[\da-f]{3,8}|[a-z]{1,30}|(?:rgb|hsl)a?\([\d\s.,%+-]+\))$/i.test(value);
    if (key === 'fontSize') return value === '' || /^(?:[8-9]|[1-9]\d|1[01]\d|120)(?:\.\d{1,2})?px$/.test(value);
    return false;
  });
}

/**
 * 此函数完全自包含，序列化后在 sandbox 内执行；不得依赖模块闭包、eval 或网络接口。
 * 状态仅声明为实际可读取的 DOM 控件快照，未实现的页面私有 JS 状态不会被伪装为已保存。
 */
function installInteractiveBridge(identity: BridgeIdentity) {
  const channel = 'educationagent.interactive.v1';
  const marker = 'data-educationagent-bridge';
  const excluded = new Set(['HTML', 'HEAD', 'BODY', 'SCRIPT', 'STYLE', 'META', 'LINK', 'BASE', 'IFRAME', 'OBJECT', 'EMBED', 'CANVAS']);
  let selectionMode = false;
  let selectedSelector = '';
  let selectedHandle: Element | null = null;
  let hoverHandle: Element | null = null;
  let overlay: HTMLDivElement | null = null;
  let selectedBox: HTMLDivElement | null = null;
  let hoverBox: HTMLDivElement | null = null;
  let stateTimer: ReturnType<typeof setTimeout> | undefined;
  const defaults = new Map<string, { value?: string; checked?: boolean }>();
  const validSelector = (value: unknown): value is string => typeof value === 'string' && /^#[A-Za-z][A-Za-z0-9_-]{0,126}$/.test(value);
  const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
  const emit = (payload: Record<string, unknown>) => window.parent.postMessage({ ...payload, channel, ...identity }, '*');
  const fail = (message: string): never => { throw new Error(message); };
  function unique(selector: unknown): HTMLElement {
    if (!validSelector(selector)) return fail('目标必须是稳定 #id');
    const matches = document.querySelectorAll(selector);
    if (matches.length !== 1 || excluded.has(matches[0].tagName) || matches[0].closest(`[${marker}]`)) return fail('目标已不存在、ID 重复或不可操作');
    return matches[0] as HTMLElement;
  }
  function control(node: Element): node is HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement {
    return node instanceof HTMLSelectElement || node instanceof HTMLTextAreaElement
      || (node instanceof HTMLInputElement && !['file', 'password', 'hidden', 'button', 'submit', 'reset', 'image'].includes(node.type));
  }
  function values(node: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement) {
    return node instanceof HTMLInputElement && ['checkbox', 'radio'].includes(node.type) ? { checked: node.checked } : { value: node.value };
  }
  function snapshot(): Record<string, unknown> | undefined {
    const controls: Record<string, unknown> = {};
    for (const node of Array.from(document.querySelectorAll('input[id],select[id],textarea[id]')).slice(0, 400)) {
      if (!control(node) || !validSelector(`#${node.id}`) || document.querySelectorAll(`#${node.id}`).length !== 1) continue;
      controls[`#${node.id}`] = values(node);
    }
    return Object.keys(controls).length ? { controls } : undefined;
  }
  function emitState() {
    const state = snapshot();
    if (state) emit({ kind: 'state', state });
  }
  function emitStateLater() {
    clearTimeout(stateTimer);
    stateTimer = setTimeout(emitState, 80);
  }
  function properties(node: HTMLElement): EditableProperties {
    const computed = getComputedStyle(node);
    return {
      tagName: node.tagName.toLowerCase(), textContent: (node.textContent || '').slice(0, 2000),
      canEditText: node.children.length === 0 && !control(node), ...(control(node) && !(node instanceof HTMLInputElement && ['checkbox', 'radio'].includes(node.type)) ? { value: node.value } : {}),
      title: node.getAttribute('title') || '', 'aria-label': node.getAttribute('aria-label') || '',
      color: node.style.color || computed.color, backgroundColor: node.style.backgroundColor || computed.backgroundColor,
      fontSize: node.style.fontSize || computed.fontSize,
    };
  }
  function candidate(element: Element | null): HTMLElement | null {
    let node = element;
    while (node && node !== document.body && node !== document.documentElement) {
      if (!excluded.has(node.tagName) && validSelector(`#${node.id}`) && !node.closest(`[${marker}]`) && document.querySelectorAll(`#${node.id}`).length === 1) return node as HTMLElement;
      node = node.parentElement;
    }
    return null;
  }
  function ensureOverlay() {
    if (overlay?.isConnected) return;
    overlay = document.createElement('div'); overlay.setAttribute(marker, 'selection');
    overlay.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;';
    selectedBox = document.createElement('div'); hoverBox = document.createElement('div');
    for (const box of [selectedBox, hoverBox]) box.style.cssText = 'display:none;position:absolute;border:3px solid #216d51;background:#216d5118;box-sizing:border-box;border-radius:3px;pointer-events:none;';
    hoverBox.style.borderStyle = 'dashed'; overlay.append(selectedBox, hoverBox); document.body.append(overlay);
  }
  function drawBox(box: HTMLDivElement | null, node: Element | null) {
    if (!box) return;
    if (!node?.isConnected) { box.style.display = 'none'; return; }
    const bounds = node.getBoundingClientRect();
    Object.assign(box.style, { display: 'block', left: `${bounds.left}px`, top: `${bounds.top}px`, width: `${bounds.width}px`, height: `${bounds.height}px` });
  }
  function draw() {
    if (!selectionMode && !selectedSelector) { overlay?.remove(); overlay = null; return; }
    ensureOverlay();
    let selected: Element | null = null;
    if (selectedSelector) {
      try { const current = unique(selectedSelector); if (!selectedHandle || selectedHandle === current) { selectedHandle = current; selected = current; } } catch { /* 被页面脚本替换的对象不得静默重绑到新对象。 */ }
    }
    drawBox(selectedBox, selected); drawBox(hoverBox, selectionMode ? hoverHandle : null);
  }
  function setSelection(selector: unknown) {
    const next = validSelector(selector) ? selector : '';
    if (next !== selectedSelector) selectedHandle = null;
    selectedSelector = next; draw();
  }
  function block(event: Event) { event.preventDefault(); event.stopImmediatePropagation(); }
  window.addEventListener('pointermove', event => { if (selectionMode) { hoverHandle = candidate(event.target instanceof Element ? event.target : null); draw(); } }, true);
  window.addEventListener('click', event => {
    // 不让生成页的链接离开隔离文档；学生操作普通控件仍然可用。
    if (!selectionMode) { if (event.target instanceof Element && event.target.closest('a[href]')) event.preventDefault(); return; }
    block(event);
    const node = candidate(event.target instanceof Element ? event.target : null);
    if (!node) { emit({ kind: 'error', error: '这个位置没有可引用的稳定对象 ID，请选择带边框的控件或文字。' }); return; }
    selectedSelector = `#${node.id}`; selectedHandle = node; hoverHandle = null; draw();
    emit({ kind: 'picked', selector: selectedSelector, summary: `${node.tagName.toLowerCase()} · ${(node.getAttribute('aria-label') || node.getAttribute('title') || node.textContent || (control(node) ? node.value : '') || node.id).trim().slice(0, 160)}`, properties: properties(node) });
  }, true);
  window.addEventListener('submit', event => event.preventDefault(), true);
  window.addEventListener('scroll', draw, true); window.addEventListener('resize', draw);
  window.addEventListener('input', emitStateLater); window.addEventListener('change', emitStateLater);

  function entriesForState(state: unknown, target?: string): [HTMLElement, { value?: string; checked?: boolean }][] {
    if (!record(state)) return fail('互动状态必须是对象');
    if (!target && typeof (state.target ?? state.selector) === 'string') {
      const rawTarget = String(state.target ?? state.selector);
      target = rawTarget.startsWith('#') ? rawTarget : `#${rawTarget}`;
    }
    const source = record(state.controls) ? state.controls : state;
    const entries: [HTMLElement, { value?: string; checked?: boolean }][] = [];
    const rawEntries = target ? [[target, source] as [string, unknown]] : Object.entries(source);
    if (!rawEntries.length || rawEntries.length > 400) return fail('没有可恢复的控件状态');
    for (const [key, raw] of rawEntries) {
      const node = unique(key.startsWith('#') ? key : `#${key}`);
      if (!control(node)) return fail(`${key} 不是可读写的输入控件`);
      const patch = record(raw) ? raw : { value: raw };
      if (node instanceof HTMLInputElement && ['checkbox', 'radio'].includes(node.type)) {
        const checked = patch.checked ?? patch.value;
        if (typeof checked !== 'boolean') return fail(`${key} 需要布尔值`);
        entries.push([node, { checked }]);
      } else {
        if (typeof patch.value !== 'string' && !(typeof patch.value === 'number' && Number.isFinite(patch.value))) return fail(`${key} 需要文字或有限数字值`);
        const value = String(patch.value); if (value.length > 10000) return fail('控件值过长');
        if (node instanceof HTMLSelectElement && !Array.from(node.options).some(option => option.value === value)) return fail(`${key} 的选项不存在`);
        entries.push([node, { value }]);
      }
    }
    return entries;
  }
  function applyState(state: unknown, target?: string) {
    const entries = entriesForState(state, target);
    for (const [node, patch] of entries) {
      if ('checked' in patch) (node as HTMLInputElement).checked = patch.checked!;
      else (node as HTMLInputElement).value = patch.value!;
    }
    for (const [node] of entries) { node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); }
    for (const [node, patch] of entries) {
      if (!node.isConnected || ('checked' in patch ? (node as HTMLInputElement).checked !== patch.checked : (node as HTMLInputElement).value !== patch.value)) return fail('页面未接受目标状态，可能超出控件范围');
    }
    return snapshot();
  }
  function bodySignature() {
    const copy = document.body.cloneNode(true) as HTMLElement;
    copy.querySelectorAll(`[${marker}]`).forEach(node => node.remove());
    return copy.outerHTML + JSON.stringify(snapshot());
  }
  function hiddenContainer(node: HTMLElement): Element | null {
    // 不能为显示一个对象而擅自展开整个上层区域，特别是关闭的题目/答案面板。
    for (let ancestor = node.parentElement; ancestor; ancestor = ancestor.parentElement) {
      const style = getComputedStyle(ancestor);
      if (style.display === 'none' || style.getPropertyValue('content-visibility') === 'hidden' || Number.parseFloat(style.opacity) <= 0) return ancestor;
    }
    return null;
  }
  async function reveal(node: HTMLElement): Promise<void> {
    if (hiddenContainer(node)) return fail('目标所在的上层区域仍被隐藏，无法单独显示它。');
    node.removeAttribute('hidden');
    node.style.removeProperty('display');
    let style = getComputedStyle(node);
    if (style.display === 'none') {
      const naturalDisplay: Record<string, string> = { TR: 'table-row', TD: 'table-cell', TH: 'table-cell', TABLE: 'table', TBODY: 'table-row-group', THEAD: 'table-header-group', TFOOT: 'table-footer-group', LI: 'list-item', SPAN: 'inline', A: 'inline', BUTTON: 'inline-block', INPUT: 'inline-block', SELECT: 'inline-block', TEXTAREA: 'inline-block' };
      // 普通内联赋值无法覆盖样式表的 !important；设置后还必须读取最终计算样式。
      node.style.setProperty('display', naturalDisplay[node.tagName] || 'block', 'important');
    }
    if (style.visibility === 'hidden' || style.visibility === 'collapse') node.style.setProperty('visibility', 'visible', 'important');
    if (Number.parseFloat(style.opacity) <= 0) node.style.setProperty('opacity', '1', 'important');
    if (style.getPropertyValue('content-visibility') === 'hidden') node.style.setProperty('content-visibility', 'visible', 'important');
    node.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    // 让页面样式和其同步事件完成一次绘制周期，再确认；发出命令本身不代表成功。
    await new Promise<void>(resolve => setTimeout(resolve, 50));
    if (!node.isConnected) return fail('目标已从页面移除，无法确认显示。');
    if (hiddenContainer(node)) return fail('目标所在的上层区域仍被隐藏，无法单独显示它。');
    style = getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse'
      || Number.parseFloat(style.opacity) <= 0 || style.getPropertyValue('content-visibility') === 'hidden') return fail('页面样式仍隐藏着目标，未能显示该对象。');
    const visibility = node as HTMLElement & { checkVisibility?: (options: Record<string, boolean>) => boolean };
    if (typeof visibility.checkVisibility === 'function' && !visibility.checkVisibility({ opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true })) return fail('目标仍没有可见内容，未能确认显示。');
    const rectangles = Array.from(node.getClientRects()).filter(rect => rect.width > 0 && rect.height > 0);
    if (!rectangles.length) return fail('目标没有可见的显示区域，未能确认显示。');
    const visible = rectangles.some(rect => {
      let left = Math.max(0, rect.left), top = Math.max(0, rect.top);
      let right = Math.min(window.innerWidth, rect.right), bottom = Math.min(window.innerHeight, rect.bottom);
      for (let ancestor = node.parentElement; ancestor; ancestor = ancestor.parentElement) {
        const computed = getComputedStyle(ancestor), bounds = ancestor.getBoundingClientRect();
        if (['hidden', 'clip', 'scroll', 'auto'].includes(computed.overflowX)) { left = Math.max(left, bounds.left); right = Math.min(right, bounds.right); }
        if (['hidden', 'clip', 'scroll', 'auto'].includes(computed.overflowY)) { top = Math.max(top, bounds.top); bottom = Math.min(bottom, bounds.bottom); }
      }
      return right > left && bottom > top;
    });
    if (!visible) return fail('目标仍在可见区域之外或被上层区域裁剪，未能确认显示。');
  }
  async function execute(command: WidgetCommand): Promise<BridgeResult> {
    if (!record(command)) return fail('缺少互动操作');
    if (command.type === 'SET_WIDGET_STATE') return { success: true, state: applyState(command.state, command.target) };
    if (command.type === 'RESET_WIDGET') {
      const controls = Object.fromEntries(Array.from(defaults.entries()).filter(([key]) => !command.target || key === command.target));
      if (!Object.keys(controls).length) return fail('页面没有可恢复的默认控件值');
      return { success: true, state: applyState({ controls }) };
    }
    const node = unique(command.target);
    if (command.type === 'TRIGGER_WIDGET') {
      if (node.hasAttribute('disabled')) return fail('控件当前已禁用');
      const event = command.event || 'click';
      if (!['click', 'input', 'change'].includes(event)) return fail('仅支持 click、input、change 事件');
      if (event === 'click' && !(node instanceof HTMLButtonElement || (node instanceof HTMLInputElement && ['checkbox', 'radio', 'button', 'reset'].includes(node.type)))) return fail('目标不是可点击的按钮或选择控件');
      if (event !== 'click' && !control(node)) return fail('目标不支持输入事件');
      const before = bodySignature();
      if (event === 'click') node.click(); else node.dispatchEvent(new Event(event, { bubbles: true }));
      await new Promise<void>(resolve => setTimeout(resolve, 80));
      if (before === bodySignature()) return fail('事件已发送，但未观察到页面或控件状态变化');
      return { success: true, state: snapshot() };
    }
    if (command.type === 'HIGHLIGHT_ELEMENT') { setSelection(command.target); return { success: true }; }
    if (command.type === 'ANNOTATE_ELEMENT') {
      if (typeof command.content !== 'string' || !command.content.trim() || command.content.length > 2000) return fail('标注需要不超过 2000 字的文字');
      setSelection(command.target); ensureOverlay();
      const note = document.createElement('div');
      note.setAttribute(marker, 'annotation'); note.textContent = command.content;
      const bounds = node.getBoundingClientRect();
      note.style.cssText = 'position:absolute;max-width:300px;white-space:pre-wrap;overflow-wrap:anywhere;padding:8px 12px;border:1px solid #216d51;border-radius:8px;background:#fffce5;color:#153e2e;font:16px/1.5 sans-serif;box-shadow:0 2px 10px #0002;';
      note.style.left = `${Math.max(4, Math.min(bounds.left, window.innerWidth - 310))}px`; note.style.top = `${Math.max(4, Math.min(bounds.bottom + 6, window.innerHeight - 100))}px`;
      overlay!.querySelectorAll(`[${marker}="annotation"]`).forEach(item => item.remove()); overlay!.append(note);
      return { success: true };
    }
    if (command.type === 'REVEAL_ELEMENT') {
      await reveal(node);
      return { success: true };
    }
    return fail('页面尚不支持这个互动操作');
  }
  function edit(selector: unknown, input: unknown): BridgeResult {
    const node = unique(selector);
    if (!record(input) || !Object.keys(input).length) return fail('没有要保存的属性');
    const patch: Record<string, string> = {};
    for (const [key, value] of Object.entries(input)) {
      if (typeof value !== 'string' || value.length > 2000) return fail('属性值格式不正确');
      if (['textContent', 'value', 'title', 'aria-label'].includes(key)) patch[key] = value;
      else if (['color', 'backgroundColor'].includes(key) && (value === '' || /^(?:#[\da-f]{3,8}|[a-z]{1,30}|(?:rgb|hsl)a?\([\d\s.,%+-]+\))$/i.test(value)) && (value === '' || CSS.supports(key === 'backgroundColor' ? 'background-color' : 'color', value))) patch[key] = value;
      else if (key === 'fontSize' && (value === '' || /^(?:[8-9]|[1-9]\d|1[01]\d|120)(?:\.\d{1,2})?px$/.test(value))) patch[key] = value;
      else return fail(`不允许修改属性：${key}`);
    }
    if ('textContent' in patch && (node.children.length > 0 || control(node))) return fail('含有子控件的对象不能整体替换文字，请选择其中的文字对象');
    if ('value' in patch && (!control(node) || (node instanceof HTMLInputElement && ['checkbox', 'radio'].includes(node.type)))) return fail('这个对象不支持文字输入值');
    // 先完成全部验证，再写属性，防止一半成功、一半拒绝。
    for (const [key, value] of Object.entries(patch)) {
      if (key === 'textContent') node.textContent = value;
      else if (key === 'value') { (node as HTMLInputElement).value = value; node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); }
      else if (key === 'title' || key === 'aria-label') node.setAttribute(key, value);
      else if (key === 'color') node.style.color = value;
      else if (key === 'backgroundColor') node.style.backgroundColor = value;
      else if (key === 'fontSize') node.style.fontSize = value;
    }
    const copy = document.documentElement.cloneNode(true) as HTMLElement;
    copy.querySelectorAll(`[${marker}]`).forEach(element => element.remove());
    // outerHTML 不含控件的当前 property，显式同步可保存的 value/checked/selected。
    for (const live of Array.from(document.querySelectorAll('input[id],select[id],textarea[id]'))) {
      if (!control(live) || !validSelector(`#${live.id}`)) continue;
      const saved = copy.querySelector(`#${live.id}`);
      if (!saved) continue;
      if (live instanceof HTMLInputElement && ['checkbox', 'radio'].includes(live.type)) { if (live.checked) saved.setAttribute('checked', ''); else saved.removeAttribute('checked'); }
      else if (live instanceof HTMLTextAreaElement) saved.textContent = live.value;
      else if (live instanceof HTMLSelectElement) Array.from(saved.querySelectorAll('option')).forEach(option => { if (option.value === live.value) option.setAttribute('selected', ''); else option.removeAttribute('selected'); });
      else saved.setAttribute('value', live.value);
    }
    draw();
    return { success: true, html: `<!doctype html>\n${copy.outerHTML}`, properties: properties(node), state: snapshot() };
  }
  window.addEventListener('message', event => {
    const data = event.data;
    if (event.source !== window.parent || !record(data) || data.channel !== channel || data.sceneId !== identity.sceneId || data.nonce !== identity.nonce) return;
    if (data.command === 'hello') { if (document.readyState !== 'loading') emit({ kind: 'ready' }); return; }
    if (data.command === 'selection') { selectionMode = data.enabled === true; hoverHandle = null; setSelection(data.selector); return; }
    if (typeof data.requestId !== 'string' || data.requestId.length > 100) return;
    const requestId = data.requestId;
    void (async () => {
      try {
        let result: BridgeResult;
        if (data.command === 'execute') result = await execute(data.action as WidgetCommand);
        else if (data.command === 'restore') result = { success: true, state: applyState(data.state) };
        else if (data.command === 'inspect') result = { success: true, properties: properties(unique(data.selector)) };
        else if (data.command === 'edit') result = edit(data.selector, data.patch);
        else if (data.command === 'snapshot') { const state = snapshot(); result = state ? { success: true, state } : { success: false, error: '页面没有可读取的控件状态' }; }
        else result = { success: false, error: '未知互动请求' };
        emit({ kind: 'ack', requestId, result: { ...result, status: result.success ? 'confirmed' : 'rejected', ...(!result.success ? { reason: 'rejected' } : {}) } });
      } catch (error) { emit({ kind: 'ack', requestId, result: { success: false, status: 'rejected', reason: 'rejected', error: error instanceof Error ? error.message : '互动操作失败' } }); }
    })();
  });
  const ready = () => {
    const state = snapshot();
    if (state && record(state.controls)) for (const [key, value] of Object.entries(state.controls)) defaults.set(key, value as { value?: string; checked?: boolean });
    emit({ kind: 'ready' });
  };
  if (document.readyState === 'loading') window.addEventListener('DOMContentLoaded', ready, { once: true }); else ready();
}

export function bridgeScript(identity: BridgeIdentity): string {
  const safeIdentity = JSON.stringify(identity).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return `(${installInteractiveBridge.toString()})(${safeIdentity});`;
}

/** 先插入 CSP 与桥，再让原页面脚本运行；对源文档的编辑仍只能走白名单 message。 */
export function interactiveDocument(html: string, identity: BridgeIdentity): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('[data-educationagent-bridge],base,meta[http-equiv],script[src],link,iframe,object,embed').forEach(node => node.remove());
  const csp = doc.createElement('meta'); csp.httpEquiv = 'Content-Security-Policy'; csp.content = INTERACTIVE_CSP; csp.setAttribute('data-educationagent-bridge', 'csp');
  const referrer = doc.createElement('meta'); referrer.name = 'referrer'; referrer.content = 'no-referrer'; referrer.setAttribute('data-educationagent-bridge', 'referrer');
  const script = doc.createElement('script'); script.setAttribute('data-educationagent-bridge', 'runtime'); script.textContent = bridgeScript(identity).replace(/<\/script/gi, '<\\/script');
  doc.head.prepend(csp, referrer, script);
  return `<!doctype html>\n${doc.documentElement.outerHTML}`;
}
