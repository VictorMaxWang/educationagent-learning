/**
 * 运维观测三个页面共用的请求底座。
 *
 * 错误约定沿用项目里最新的那一套（见 study/CourseKnowledgeView.jsx）：
 * 原地 fetch + 检查 json.code === 200 + 错误落进 msg 状态，**不向上抛异常**。
 * 本项目另外两套约定（travel/request.ts 的 wrap()、pet/petApi.js 的直接 throw）
 * 就不在这里混用了。
 */

const BASE_URL = (() => {
  if (import.meta.env.VITE_API_BASE_URL) return import.meta.env.VITE_API_BASE_URL;
  if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__) return 'http://localhost:8888';
  return 'http://localhost:8888';
})();

/**
 * 桌面端用户 ID（用于记忆隔离）。
 *
 * ⚠️ localStorage 的 key 必须是 `desktopUserId`——聊天页（App.jsx）、刷题页
 * （CourseKnowledgeView）、法律页（LawRagView）都用这个 key。
 * 这里原先写的是 `desktop_user_id`（带下划线），会生成**另一个随机 ID**，
 * 于是记忆写在 A 用户名下、脑图却去查 B 用户，页面永远是空的。
 */
export function getDesktopUserId() {
  let uid = localStorage.getItem('desktopUserId');
  if (!uid) {
    uid = 'user_' + Math.random().toString(36).slice(2, 10);
    localStorage.setItem('desktopUserId', uid);
  }
  return uid;
}

/**
 * 判断一个响应体是否成功。
 *
 * 本项目同时存在两种约定，且三个观测页共用本文件，所以两种都得认：
 *   · `{code:200, msg, data}`         —— 项目主流（19 个控制器）
 *   · `{success:true, data, timestamp}` —— 记忆脑图接口（按方案书）
 * 少认一种，就会让路由评测/指标监控/记忆脑图里某几页突然报"接口返回异常"。
 */
function isOk(json) {
  return json?.code === 200 || json?.success === true;
}

/**
 * GET 一个接口（兼容上述两种响应体）。
 *
 * @returns {{ok:boolean, data:any, msg:string}} 永不抛异常
 */
export async function getJson(path) {
  try {
    const res = await fetch(`${BASE_URL}${path}`);
    if (!res.ok) return { ok: false, data: null, msg: `HTTP ${res.status}` };
    const json = await res.json();
    if (isOk(json)) return { ok: true, data: json.data, msg: 'ok' };
    return { ok: false, data: null, msg: json?.msg || '接口返回异常' };
  } catch (e) {
    return { ok: false, data: null, msg: '后端未响应，请确认主工程已启动' };
  }
}

/** POST 一个接口（兼容上述两种响应体）。 */
export async function postJson(path, body) {
  try {
    const res = await fetch(`${BASE_URL}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body ?? {}),
    });
    if (!res.ok) return { ok: false, data: null, msg: `HTTP ${res.status}` };
    const json = await res.json();
    if (isOk(json)) return { ok: true, data: json.data, msg: 'ok' };
    return { ok: false, data: null, msg: json?.msg || '接口返回异常' };
  } catch (e) {
    return { ok: false, data: null, msg: '后端未响应，请确认主工程已启动' };
  }
}

/** 数字格式化：null/undefined 显示为 "—" 而不是 0（0 和"没有数据"是两回事）。 */
export function num(v, digits = 0) {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  return Number(v).toFixed(digits);
}

/** 百分比：入参是 0~1 的小数。 */
export function pct(v, digits = 1) {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  return `${(Number(v) * 100).toFixed(digits)}%`;
}
