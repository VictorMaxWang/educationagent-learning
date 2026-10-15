import { useState, useEffect, useCallback } from 'react'
import {
  loadAlarms, addAlarm, removeAlarm, toggleAlarm,
  startAlarmChecker, stopAlarmChecker, onAlarmTrigger,
  requestNotification, playBeep,
} from '../utils/alarm.js'
import { loadConfig, saveConfig, testConnection, loadPushLog, clearPushLog } from '../utils/wechat-bot.js'
import { loadMembers } from '../utils/members.js'

export default function MedicineReminder({ onClose, activeMember, members, activeMemberId = 'default', onSwitchMember, onAddMember }) {
  const [alarms, setAlarms] = useState(() => loadAlarms())
  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({
    name: '', dosage: '', time: '08:00', repeat: 'daily',
    memberId: activeMemberId,
  })
  const [notifyPerm, setNotifyPerm] = useState(Notification.permission)
  const [triggerLog, setTriggerLog] = useState([])
  const [wxConfig, setWxConfig] = useState(loadConfig)
  const [wxTestResult, setWxTestResult] = useState(null)
  const [pushLog, setPushLog] = useState(loadPushLog)
  const [showWxConfig, setShowWxConfig] = useState(false)
  const [filterMemberId, setFilterMemberId] = useState(activeMemberId)
  const [memberDropOpen, setMemberDropOpen] = useState(false)
  const [filterDropOpen, setFilterDropOpen] = useState(false)

  // 切换成员时联动过滤
  const handleSwitchMember = (id) => {
    onSwitchMember?.(id)
    setFilterMemberId(id)
    setForm(prev => ({ ...prev, memberId: id }))
  }

  // 成员列表：优先 prop，其次直接从 localStorage 加载
  const memberList = (members && members.length > 0) ? members : loadMembers()

  const getMemberName = (mid) => {
    const m = memberList.find(x => x.id === mid)
    return m?.name || '本人'
  }

  const displayAlarms = filterMemberId === 'all'
    ? alarms
    : alarms.filter(a => a.memberId === filterMemberId)

  const refreshAlarms = useCallback(() => setAlarms(loadAlarms()), [])

  const handleTrigger = useCallback((info) => {
    playBeep(800, 600)
    setTriggerLog(prev => [{ ...info, id: Date.now() }, ...prev].slice(0, 20))
    refreshAlarms()
  }, [refreshAlarms])

  useEffect(() => {
    startAlarmChecker()
    const unsub = onAlarmTrigger(handleTrigger)
    return () => { unsub(); stopAlarmChecker() }
  }, [handleTrigger])

  const enableNotify = async () => {
    const perm = await requestNotification()
    setNotifyPerm(perm)
  }

  const handleAdd = () => {
    if (!form.name) return
    const selectedMember = memberList.find(m => m.id === form.memberId) || activeMember
    addAlarm({
      name: form.name, dosage: form.dosage, time: form.time, repeat: form.repeat,
      memberId: form.memberId,
      memberName: selectedMember?.name || '本人',
      memberWxid: selectedMember?.wxid || '',
    })
    refreshAlarms()
    setForm({ name: '', dosage: '', time: '08:00', repeat: 'daily', memberId: activeMember?.id || 'default' })
    setShowForm(false)
  }

  const handleDelete = (id) => { removeAlarm(id); refreshAlarms() }
  const handleToggle = (id) => { toggleAlarm(id); refreshAlarms() }

  const handleWxSave = () => { saveConfig(wxConfig); setShowWxConfig(false) }
  const handleWxTest = async () => {
    saveConfig(wxConfig)
    const r = await testConnection()
    setWxTestResult(r)
    setPushLog(loadPushLog())
    setTimeout(() => setWxTestResult(null), 4000)
  }
  const refreshPushLog = () => setPushLog(loadPushLog())

  const sorted = [...displayAlarms].sort((a, b) => (a.time || '').localeCompare(b.time || ''))

  const selectedMemberLabel = (() => {
    const m = memberList.find(x => x.id === form.memberId)
    return m ? `${m.name} ${m.wxid ? '💬' : ''}` : '选择成员'
  })()

  const filterLabel = filterMemberId === 'all'
    ? `全部成员 (${alarms.length})`
    : (() => { const m = memberList.find(x => x.id === filterMemberId); return m ? `${m.name} (${alarms.filter(a => a.memberId === filterMemberId).length})` : ''; })()

  return (
    <div className="feature-panel">
      <div className="feature-header">
        <h2>💊 吃药提醒</h2>
        <span className="feature-slogan">定时提醒 · 按时服药</span>
        <button className="feature-close" onClick={onClose}>✕</button>
      </div>

      <div className="feature-content">
        {notifyPerm !== 'granted' && (
          <div className="notify-banner" onClick={enableNotify}>
            🔔 点击开启浏览器通知，准时收到吃药提醒
          </div>
        )}

        {/* 微信推送配置 */}
        <div className="section">
          <div className="section-header">
            <h3 className="section-title">💬 微信推送</h3>
            <div className="wx-header-right">
              {wxConfig.enabled && <span className="wx-status-on">● 已启用</span>}
              <button className="add-btn" onClick={() => { setShowWxConfig(!showWxConfig); refreshPushLog() }}>
                {showWxConfig ? '收起' : '配置'}
              </button>
            </div>
          </div>
          {showWxConfig && (
            <div className="wx-config-panel">
              <div className="wx-config-row">
                <label>🤖 服务地址</label>
                <input placeholder="http://127.0.0.1:8888" value={wxConfig.baseUrl}
                  onChange={e => setWxConfig({ ...wxConfig, baseUrl: e.target.value })} />
              </div>
              <div className="wx-config-row">
                <label>🔗 接口路径</label>
                <input placeholder="/api/desktop/sendPrivateMsg" value={wxConfig.apiPath}
                  onChange={e => setWxConfig({ ...wxConfig, apiPath: e.target.value })} />
              </div>
              <div className="wx-config-row">
                <label>👤 默认推送 wxid（成员未填 wxid 时使用）</label>
                <input placeholder="wxid_xxxxxxxxxxxxxxxx" value={wxConfig.targetWxid}
                  onChange={e => setWxConfig({ ...wxConfig, targetWxid: e.target.value })} />
              </div>
              <div className="wx-config-row-split">
                <label className="wx-toggle-label">
                  <input type="checkbox" checked={wxConfig.enabled}
                    onChange={e => setWxConfig({ ...wxConfig, enabled: e.target.checked })} />
                  <span>启用微信推送</span>
                </label>
                <div className="wx-config-actions">
                  <button className="wx-test-btn" onClick={handleWxTest}>🔗 测试连接</button>
                  <button className="save-btn-sm" onClick={handleWxSave} style={{marginTop:0}}>💾 保存</button>
                </div>
              </div>
              {wxTestResult && (
                <div className={`wx-test-result${wxTestResult.success ? ' ok' : ' fail'}`}>
                  {wxTestResult.success ? '✅ 连接成功' : `❌ ${wxTestResult.error}`}
                </div>
              )}
              <div className="wx-log-section">
                <div className="wx-log-header">
                  <span className="wx-log-title">📋 推送日志</span>
                  <button className="wx-log-clear" onClick={() => { clearPushLog(); setPushLog([]) }}>清空</button>
                </div>
                <div className="wx-log-list">
                  {pushLog.length === 0 ? (
                    <span className="wx-log-empty">暂无推送记录</span>
                  ) : (
                    pushLog.slice(0, 20).map(entry => (
                      <div key={entry.id} className={`wx-log-item ${entry.type}`}>
                        <span className="wx-log-time">{new Date(entry.time).toLocaleTimeString('zh-CN')}</span>
                        <span className={`wx-log-type-badge ${entry.type}`}>
                          {entry.type === 'success' ? '✓' : entry.type === 'fail' ? '✗' : entry.type === 'error' ? '⚠' : '—'}
                        </span>
                        <span className="wx-log-content">
                          {entry.type === 'success' ? entry.content : entry.type === 'skip' ? entry.reason : `${entry.error || ''} · ${entry.content || ''}`}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* ── 闹钟表单 ── */}
        <div className="section">
          <div className="section-header">
            <h3 className="section-title">⏰ 添加提醒</h3>
            <button className="add-btn" onClick={() => setShowForm(!showForm)}>
              {showForm ? '取消' : '+ 添加'}
            </button>
          </div>
          {showForm && (
            <div className="alarm-form">
              {/* 成员选择 — 自定义下拉 */}
              <div className="alarm-form-row">
                <label>👤 提醒对象</label>
                <div className="custom-select" onClick={() => setMemberDropOpen(true)}>
                  <span className="custom-select-text">{selectedMemberLabel}</span>
                  <span className="custom-select-arrow">▾</span>
                </div>
              </div>

              {/* 成员下拉菜单 + 透明遮罩 */}
              {memberDropOpen && (
                <div className="dropdown-overlay" onClick={() => setMemberDropOpen(false)}>
                  <div className="dropdown-menu" onClick={e => e.stopPropagation()}>
                    <div className="dropdown-title">选择提醒对象</div>
                    {memberList.map(m => (
                      <div key={m.id}
                        className={`dropdown-item${form.memberId === m.id ? ' active' : ''}`}
                        onClick={() => { setForm({ ...form, memberId: m.id }); setMemberDropOpen(false) }}>
                        <span>{m.name}</span>
                        <span className="dropdown-hint">{m.wxid ? '💬 已绑定微信' : '未绑定wxid'}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="alarm-form-row">
                <label>药品名称</label>
                <input placeholder="如：阿莫西林" value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="alarm-form-row">
                <label>剂量</label>
                <input placeholder="如：1 粒 / 10ml" value={form.dosage}
                  onChange={e => setForm({ ...form, dosage: e.target.value })} />
              </div>
              <div className="alarm-form-row-split">
                <div className="alarm-form-row">
                  <label>时间</label>
                  <input type="time" value={form.time}
                    onChange={e => setForm({ ...form, time: e.target.value })} />
                </div>
                <div className="alarm-form-row">
                  <label>重复</label>
                  <select value={form.repeat}
                    onChange={e => setForm({ ...form, repeat: e.target.value })}>
                    <option value="daily">每天</option>
                    <option value="once">仅一次</option>
                    <option value="custom">自定义</option>
                  </select>
                </div>
              </div>
              <button className="save-btn-sm" onClick={handleAdd}>💾 保存提醒</button>
            </div>
          )}
        </div>

        {/* ── 闹钟列表 ── */}
        <div className="section">
          <div className="section-header">
            <h3 className="section-title">📋 提醒列表</h3>
            {/* 成员筛选 — 自定义下拉 */}
            <div className="custom-select filter-select" onClick={() => setFilterDropOpen(true)}>
              <span className="custom-select-text">{filterLabel}</span>
              <span className="custom-select-arrow">▾</span>
            </div>
          </div>

          {/* 筛选下拉 + 透明遮罩 */}
          {filterDropOpen && (
            <div className="dropdown-overlay" onClick={() => setFilterDropOpen(false)}>
              <div className="dropdown-menu" onClick={e => e.stopPropagation()}>
                <div className="dropdown-item" onClick={() => { setFilterMemberId('all'); setFilterDropOpen(false) }}>
                  全部成员 ({alarms.length})
                </div>
                {memberList.map(m => (
                  <div key={m.id} className={`dropdown-item${filterMemberId === m.id ? ' active' : ''}`}
                    onClick={() => { setFilterMemberId(m.id); setFilterDropOpen(false) }}>
                    {m.name} ({alarms.filter(a => a.memberId === m.id).length})
                  </div>
                ))}
              </div>
            </div>
          )}

          {sorted.length === 0 ? (
            <p className="empty-hint">暂无用药提醒，点击上方添加</p>
          ) : (
            <div className="alarm-list">
              {sorted.map(a => (
                <div key={a.id} className={`alarm-item${!a.enabled ? ' disabled' : ''}`}>
                  <label className="alarm-toggle">
                    <input type="checkbox" checked={a.enabled}
                      onChange={() => handleToggle(a.id)} />
                    <span className="toggle-slider" />
                  </label>
                  <span className="alarm-time">{a.time}</span>
                  <span className="alarm-info">
                    <strong>{a.name}</strong>
                    {a.dosage && <small>{a.dosage}</small>}
                  </span>
                  <span className="alarm-member-tag">
                    👤 {a.memberName || getMemberName(a.memberId)} {a.memberWxid ? '💬' : ''}
                  </span>
                  <span className="alarm-repeat">
                    {a.repeat === 'daily' ? '每天' : a.repeat === 'once' ? '一次' : '自定义'}
                  </span>
                  <button className="alarm-del" onClick={() => handleDelete(a.id)}>🗑</button>
                </div>
              ))}
            </div>
          )}
        </div>

        {triggerLog.length > 0 && (
          <div className="section">
            <h3 className="section-title">🔔 提醒记录</h3>
            <div className="log-list">
              {triggerLog.slice(0, 10).map(log => (
                <div key={log.id} className="log-item">
                  <span className="log-time">{new Date(log.time).toLocaleTimeString('zh-CN')}</span>
                  <span className="log-text">{log.title} — {log.body}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
