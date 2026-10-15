/**
 * 闹钟 / 定时提醒服务
 * 使用 setInterval + Notification API 实现
 */

const ALARM_STORAGE_KEY = 'newbot_medicine_alarms'
const CHECK_INTERVAL = 30000 // 每 30 秒检查一次

let intervalId = null
let listeners = []

/**
 * 加载持久化闹钟列表
 */
export function loadAlarms() {
  try { return JSON.parse(localStorage.getItem(ALARM_STORAGE_KEY)) || [] }
  catch { return [] }
}

/**
 * 保存闹钟列表
 */
export function saveAlarms(alarms) {
  try { localStorage.setItem(ALARM_STORAGE_KEY, JSON.stringify(alarms)) }
  catch { /* quota exceeded */ }
}

/**
 * 生成唯一 ID
 */
export function genAlarmId() {
  return 'alarm_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

/**
 * 添加闹钟
 */
export function addAlarm(alarm) {
  const alarms = loadAlarms()
  const newAlarm = { ...alarm, id: genAlarmId(), enabled: true }
  alarms.push(newAlarm)
  saveAlarms(alarms)
  return newAlarm
}

/**
 * 删除闹钟
 */
export function removeAlarm(id) {
  const alarms = loadAlarms().filter(a => a.id !== id)
  saveAlarms(alarms)
  return alarms
}

/**
 * 切换闹钟启用/禁用
 */
export function toggleAlarm(id) {
  const alarms = loadAlarms().map(a => a.id === id ? { ...a, enabled: !a.enabled } : a)
  saveAlarms(alarms)
  return alarms
}

/**
 * 请求浏览器通知权限
 */
export async function requestNotification() {
  if (!('Notification' in window)) return 'denied'
  if (Notification.permission === 'granted') return 'granted'
  return await Notification.requestPermission()
}

/**
 * 发送浏览器通知
 */
function sendNotification(title, body) {
  if (Notification.permission === 'granted') {
    new Notification(title, { body, icon: '💊', requireInteraction: true })
  }
  // 回调给 UI 层
  listeners.forEach(fn => fn({ title, body, time: Date.now() }))
}

/**
 * 检查是否有到期的闹钟
 */
async function checkAlarms() {
  const now = new Date()
  const currentTime = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0')
  const alarms = loadAlarms()
  let triggered = false
  for (const alarm of alarms) {
    if (!alarm.enabled) continue
    if (alarm.time === currentTime && alarm.lastTriggered !== currentTime) {
      // 1. 浏览器桌面通知（原有逻辑）
      const memberName = alarm.memberName || '家人'
      const medicineName = alarm.name
      const dosage = alarm.dosage || '遵医嘱'
      sendNotification('💊 吃药提醒', `${memberName} — ${medicineName} ${dosage}`)

      // 2. 微信机器人推送（按成员 wxid 定向，不阻断主流程）
      try {
        const { buildMedicineMsg, sendTextTo } = await import('./wechat-bot.js')
        const msg = buildMedicineMsg(memberName, medicineName, dosage)
        // 优先用闹钟绑定的成员 wxid，没有则回退到全局配置
        const memberWxid = alarm.memberWxid || ''
        console.log('[alarm] 触发推送 memberWxid=' + memberWxid + ' alarm=', alarm)
        sendTextTo(msg, memberWxid, { alarmId: alarm.id, memberName, medicine: medicineName }).catch(() => {})
      } catch { /* 推送模块加载失败，不影响桌面通知 */ }

      alarm.lastTriggered = currentTime
      triggered = true
    }
  }
  if (triggered) saveAlarms(alarms)
}

/**
 * 启动闹钟后台检查
 */
export function startAlarmChecker() {
  if (intervalId) return
  checkAlarms()
  intervalId = setInterval(checkAlarms, CHECK_INTERVAL)
}

/**
 * 停止闹钟后台检查
 */
export function stopAlarmChecker() {
  if (intervalId) { clearInterval(intervalId); intervalId = null }
}

/**
 * 注册 UI 回调（触发时通知组件）
 */
export function onAlarmTrigger(fn) {
  listeners.push(fn)
  return () => { listeners = listeners.filter(l => l !== fn) }
}

/**
 * 播放提示音（简单的 beep）
 */
export function playBeep(duration = 500, freq = 800) {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = freq
    gain.gain.value = 0.3
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start()
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration / 1000)
    osc.stop(ctx.currentTime + duration / 1000)
  } catch { /* Audio not available */ }
}
