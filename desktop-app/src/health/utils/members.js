/**
 * 多成员身体档案管理
 *
 * 数据迁移：旧版单人数据自动包装为默认成员
 * 存储结构：
 *   newbot_members       → [{ id, name, age, gender, createdAt }]
 *   newbot_body_profile  → { [memberId]: [{ date, height, weight, ... }] }
 *   newbot_period        → { [memberId]: { cycleDays, periodDays, ... } }
 *   newbot_blood         → { [memberId]: [{ date, time, sbp, dbp, ... }] }
 *   newbot_active_member → string (当前选中成员 ID)
 */

export const MEMBERS_KEY    = 'newbot_members'
export const ACTIVE_KEY     = 'newbot_active_member'
const BODY_KEY              = 'newbot_body_profile'
const PERIOD_KEY            = 'newbot_period'
const BLOOD_KEY             = 'newbot_blood'

// ── 通用读写 ──
const read = (key) => { try { return JSON.parse(localStorage.getItem(key)) } catch { return null } }
const write = (key, val) => { try { localStorage.setItem(key, JSON.stringify(val)) } catch {} }

// ── 迁移旧数据 ──
function migrateIfNeeded() {
  const oldBody = read(BODY_KEY)
  // 如果 body 数据是数组（旧格式），包装为 { default: [...] }
  if (Array.isArray(oldBody)) {
    const defaultMember = { id: 'default', name: '本人', age: '', gender: '', wxid: '', createdAt: Date.now() }
    write(MEMBERS_KEY, [defaultMember])
    write(ACTIVE_KEY, 'default')
    write(BODY_KEY, { default: oldBody })

    const oldPeriod = read(PERIOD_KEY)
    if (oldPeriod && !oldPeriod.cycleDays) {
      // 旧 period 数据可能是数组 [{...}]，取第一个
      const p = Array.isArray(oldPeriod) ? (oldPeriod[0] || {}) : oldPeriod
      write(PERIOD_KEY, { default: p })
    } else if (oldPeriod && oldPeriod.cycleDays) {
      write(PERIOD_KEY, { default: oldPeriod })
    }

    const oldBlood = read(BLOOD_KEY)
    if (Array.isArray(oldBlood)) {
      write(BLOOD_KEY, { default: oldBlood })
    }
    return true
  }
  return false
}

// 初始化：迁移 + 确保有默认成员
export function initMembers() {
  migrateIfNeeded()
  let members = read(MEMBERS_KEY)
  if (!members || members.length === 0) {
    members = [{ id: 'default', name: '本人', age: '', gender: '', wxid: '', createdAt: Date.now() }]
    write(MEMBERS_KEY, members)
    write(ACTIVE_KEY, 'default')
  }
  return members
}

// ── 成员 CRUD ──
export function loadMembers() {
  return read(MEMBERS_KEY) || initMembers()
}

export function saveMembers(members) {
  write(MEMBERS_KEY, members)
}

let _idSeq = Date.now()
export function createMember(name, age = '', gender = '', wxid = '') {
  const members = loadMembers()
  const member = { id: 'm' + (++_idSeq).toString(36), name, age, gender, wxid, createdAt: Date.now() }
  members.push(member)
  saveMembers(members)
  return member
}

export function updateMember(id, updates) {
  const members = loadMembers().map(m => m.id === id ? { ...m, ...updates } : m)
  saveMembers(members)
  return members
}

export function deleteMember(id) {
  if (id === 'default') return // 不允许删除默认成员
  let members = loadMembers().filter(m => m.id !== id)
  saveMembers(members)
  // 清理该成员数据
  ;[BODY_KEY, PERIOD_KEY, BLOOD_KEY].forEach(key => {
    const data = read(key) || {}
    delete data[id]
    write(key, data)
  })
  // 如果当前选中该成员，切回 default
  if (getActiveMemberId() === id) setActiveMember('default')
  return members
}

// ── 当前选中成员 ──
export function getActiveMemberId() {
  return read(ACTIVE_KEY) || 'default'
}

export function setActiveMember(id) {
  write(ACTIVE_KEY, id)
}

export function getActiveMember() {
  const members = loadMembers()
  const activeId = getActiveMemberId()
  return members.find(m => m.id === activeId) || members[0] || { id: 'default', name: '本人', age: '', gender: '' }
}

// ── 成员作用域数据读写 ──
// body profile
export function loadBodyRecords(memberId) {
  const data = read(BODY_KEY) || {}
  return data[memberId || getActiveMemberId()] || []
}
export function saveBodyRecords(records, memberId) {
  const data = read(BODY_KEY) || {}
  data[memberId || getActiveMemberId()] = records
  write(BODY_KEY, data)
}

// period
export function loadPeriod(memberId) {
  const data = read(PERIOD_KEY) || {}
  return data[memberId || getActiveMemberId()] || { cycleDays: 28, periodDays: 5, lastStart: '', symptoms: [] }
}
export function savePeriod(period, memberId) {
  const data = read(PERIOD_KEY) || {}
  data[memberId || getActiveMemberId()] = period
  write(PERIOD_KEY, data)
}

// blood
export function loadBloodRecords(memberId) {
  const data = read(BLOOD_KEY) || {}
  return data[memberId || getActiveMemberId()] || []
}
export function saveBloodRecords(records, memberId) {
  const data = read(BLOOD_KEY) || {}
  data[memberId || getActiveMemberId()] = records
  write(BLOOD_KEY, data)
}

// ── 初始化调用 ──
initMembers()
