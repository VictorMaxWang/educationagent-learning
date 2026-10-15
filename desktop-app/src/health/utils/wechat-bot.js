/**
 * BDH-339 微信机器人推送工具（对接 SpringBoot 后端）
 *
 * 配置存储在 localStorage: newbot_wechat_config
 *   {
 *     enabled: true,
 *     baseUrl: 'http://127.0.0.1:8888',         // SpringBoot 服务地址
 *     apiPath: '/api/bot/sendPrivateMsg',       // 私聊发送接口路径
 *     targetWxid: 'wxid_xxxxxxxxxxxxxxxx',      // 目标微信用户 wxid
 *   }
 */

const CONFIG_KEY = 'newbot_wechat_config'
const LOG_KEY    = 'newbot_wechat_push_log'

// ══════════════════════════════════════════
//  SpringBoot 后端接口适配 — 按你的 Controller 改这里
// ══════════════════════════════════════════

/** 后端入参字段名映射 */
const FIELD_WXID = 'targetWxid'
const FIELD_MSG  = 'message'

/** SpringBoot 响应成功判断 */
function isSuccess(data) {
  // 兼容常见 SpringBoot 返回格式
  if (data == null) return false
  return (
    data.code === 0 || data.code === 200 ||
    data.status === 0 || data.status === 200 ||
    data.success === true ||
    data.ret === 0
  )
}

/** 从响应中提取错误信息 */
function extractError(data) {
  return data.msg || data.message || data.error || data.errMsg || JSON.stringify(data).slice(0, 100)
}

// ══════════════════════════════════════════
//  通用逻辑
// ══════════════════════════════════════════

const DEFAULT_CONFIG = {
  baseUrl: 'http://127.0.0.1:8888',
  apiPath: '/api/desktop/sendPrivateMsg',
  targetWxid: '',
  enabled: false,
}

export function loadConfig() {
  try {
    return { ...DEFAULT_CONFIG, ...JSON.parse(localStorage.getItem(CONFIG_KEY)) }
  } catch {
    return { ...DEFAULT_CONFIG }
  }
}

export function saveConfig(config) {
  try { localStorage.setItem(CONFIG_KEY, JSON.stringify(config)) } catch {}
}

// ── 推送日志 ──

function loadLog() {
  try { return JSON.parse(localStorage.getItem(LOG_KEY)) || [] } catch { return [] }
}

function appendLog(entry) {
  const log = loadLog()
  log.unshift({ ...entry, id: Date.now() })
  if (log.length > 100) log.length = 100
  try { localStorage.setItem(LOG_KEY, JSON.stringify(log)) } catch {}
}

export function loadPushLog() { return loadLog() }

export function clearPushLog() {
  try { localStorage.setItem(LOG_KEY, '[]') } catch {}
}

// ── 核心发送 ──

/**
 * 发送文本消息到 SpringBoot 微信机器人
 * @param {string} content
 * @param {object}  meta
 */
export async function sendText(content, meta = {}, overrideWxid = '') {
  const config = loadConfig()

  // 配置检查
  if (!config.enabled) {
    appendLog({ type: 'skip', reason: '推送已禁用', content: content.slice(0, 60), time: Date.now(), ...meta })
    return { success: false, error: '推送已禁用' }
  }

  // 优先用参数传入的成员 wxid，其次全局配置
  const targetWxid = overrideWxid || config.targetWxid
  if (!targetWxid) {
    appendLog({ type: 'fail', reason: '未填写目标 wxid', content: content.slice(0, 60), time: Date.now(), ...meta })
    return { success: false, error: '未填写目标 wxid' }
  }

  // 拼接完整 URL
  const base = config.baseUrl.replace(/\/+$/, '')
  const path = config.apiPath || '/api/desktop/sendPrivateMsg'
  const url  = base + (path.startsWith('/') ? '' : '/') + path

  // 构建请求体
  const body = {
    [FIELD_WXID]: targetWxid,
    [FIELD_MSG]: content,
  }

  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })

    // HTTP 状态码异常（404 / 500 / CORS 拒绝等）
    if (!res.ok) {
      appendLog({
        type: 'fail',
        reason: `HTTP ${res.status}`,
        url,
        content: content.slice(0, 60),
        time: Date.now(),
        ...meta,
      })
      return { success: false, error: `后端返回 HTTP ${res.status}` }
    }

    // 解析响应
    let data
    try {
      data = await res.json()
    } catch {
      appendLog({
        type: 'fail',
        reason: '响应体非 JSON',
        url,
        content: content.slice(0, 60),
        time: Date.now(),
        ...meta,
      })
      return { success: false, error: '响应体解析失败' }
    }

    // 判断业务是否成功
    if (isSuccess(data)) {
      appendLog({ type: 'success', content: content.slice(0, 60), time: Date.now(), ...meta })
      return { success: true }
    }

    appendLog({
      type: 'fail',
      reason: extractError(data),
      url,
      content: content.slice(0, 60),
      time: Date.now(),
      ...meta,
    })
    return { success: false, error: extractError(data) }
  } catch (err) {
    // 网络层失败（Failed to fetch / 连接拒绝 / DNS 解析失败）
    appendLog({
      type: 'fail',
      reason: err.message || '网络请求失败',
      url,
      content: content.slice(0, 60),
      time: Date.now(),
      ...meta,
    })
    return { success: false, error: err.message || '网络请求失败' }
  }
}

// ── 消息模板 ──

export function buildMedicineMsg(memberName, medicineName, dosage) {
  return [
    '【用药定时提醒】',
    `提醒对象：${memberName || '未指定'}`,
    `药品名称：${medicineName || '未指定'}`,
    `服用剂量：${dosage || '遵医嘱'}`,
    '到点请按时服药',
  ].join('\n')
}

/**
 * 发送到指定 wxid（覆盖全局配置的 targetWxid）。
 * 用于定时任务按闹钟绑定的成员 wxid 定向推送。
 * @param {string} content
 * @param {string} overrideWxid - 为空则回退到全局配置
 * @param {object} meta
 */
export async function sendTextTo(content, overrideWxid, meta = {}) {
  return sendText(content, meta, overrideWxid || '')
}

// ── 测试连接 ──

export async function testConnection() {
  const config = loadConfig()
  if (!config.baseUrl) return { success: false, error: '请先填写服务地址' }
  if (!config.targetWxid) return { success: false, error: '请先填写目标 wxid' }

  return sendText('✅ NewBot 桌面端 — 微信机器人连通测试成功！', { test: true })
}
