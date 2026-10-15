import { useState, useEffect } from 'react'

import { API_BASE_URL as BACKEND } from '../../api/baseUrl'
const HISTORY_PREFIX = 'newbot_recipe_history_'

function loadHistory(memberId) { try { return JSON.parse(localStorage.getItem(HISTORY_PREFIX + memberId)) || [] } catch { return [] } }
function saveHistory(memberId, data) { try { localStorage.setItem(HISTORY_PREFIX + memberId, JSON.stringify(data)) } catch {} }

const DIET_TEMPLATES = [
  { key: 'lowcal', label: '低卡减脂', icon: '🥗', theme: '轻盈燃脂日', prompt: '低卡减脂，总热量1200-1500大卡，高蛋白低脂肪' },
  { key: 'muscle', label: '增肌高蛋白', icon: '💪', theme: '力量增肌日', prompt: '增肌高蛋白，高碳水，标注蛋白质含量' },
  { key: 'keto', label: '生酮低碳', icon: '🥑', theme: '燃酮代谢日', prompt: '生酮低碳，碳水<30g，高脂肪中等蛋白' },
  { key: 'medit', label: '地中海饮食', icon: '🫒', theme: '地中海风味日', prompt: '地中海饮食，橄榄油、鱼类、全谷物为主' },
  { key: 'vege', label: '素食营养', icon: '🥬', theme: '素食清体日', prompt: '素食营养，保证蛋白质摄入，均衡搭配' },
  { key: 'stomach', label: '养胃食谱', icon: '🍲', theme: '温和养胃日', prompt: '养胃食谱，温和易消化，忌辛辣生冷' },
]

const MEALS = [
  { key: 'breakfast', label: '早餐', icon: '🌅', color: '#fef9e7', accent: '#f9a825' },
  { key: 'lunch', label: '午餐', icon: '☀️', color: '#e8f5e9', accent: '#43a047' },
  { key: 'snack', label: '加餐', icon: '🍌', color: '#fff3e0', accent: '#ef6c00' },
  { key: 'dinner', label: '晚餐', icon: '🌙', color: '#ede7f6', accent: '#5e35b1' },
]

// ═══════════════ B站链接 ── 先走后端代理搜真实视频，搜不到用搜索链接兜底 ═══════════════

async function fetchRealBilibiliUrl(keyword) {
  try {
    const res = await fetch(`${BACKEND}/api/desktop/bilibili/search?keyword=${encodeURIComponent(keyword)}`)
    if (!res.ok) return null
    const json = await res.json()
    if (json.code === 0 && json.data?.url) return json.data.url
    return null
  } catch { return null }
}

function fallbackBilibiliUrl(dishName) {
  return `https://search.bilibili.com/all?keyword=${encodeURIComponent(dishName + ' 做法 教程')}`
}

async function enrichBilibiliUrls(mealData) {
  const allNames = []
  mealData.forEach(day => {
    Object.values(day.meals || {}).forEach(arr => arr.forEach(d => allNames.push(d.name)))
  })
  const uniq = [...new Set(allNames)]
  const map = {}
  await Promise.all(uniq.map(async name => {
    const real = await fetchRealBilibiliUrl(name)
    map[name] = real || fallbackBilibiliUrl(name)
  }))
  // 回填
  mealData.forEach(day => {
    Object.values(day.meals || {}).forEach(arr => arr.forEach(d => {
      d.bilibiliurl = map[d.name] || fallbackBilibiliUrl(d.name)
    }))
  })
  return mealData
}

// ═══════════════ AI Prompt ═══════════════

function buildPrompt(memberName, memberAge, memberGender, calories, allergies, preference, mealDays, theme) {
  const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\//g, '/')
  const profile = [memberName, memberAge && `${memberAge}岁`, memberGender].filter(Boolean).join(' ')
  const constraints = [
    calories && `每日总热量≈${calories}大卡`,
    allergies && `禁忌：${allergies}`,
    preference && `饮食偏好：${preference}`,
  ].filter(Boolean).join('，')
  return [
    `按照${profile}的${constraints || '健康均衡'}，生成一日三餐+加餐健康食谱。`,
    '只输出纯净JSON，禁止任何额外话术、做菜步骤、标点乱码、问号、注释。JSON键名全部小写，固定结构：',
    `{"date":"${today}","theme":"四字到八字食谱主题","createtime":"${Date.now()}","meals":{"breakfast":[{"name":"菜品","ingredients":["食材+克数"],"calories":"纯数字整数","bilibiliurl":"https://search.bilibili.com/all?keyword=菜品+家常做法教程，绝对不能为空"}],"lunch":[],"snack":[],"dinner":[]},"summary":{"totalcal":"全日总热量整数","carb":"碳水克数整数","protein":"蛋白质克数整数","fat":"脂肪克数整数"}}`,
    '全部热量、营养素数值统一为正整数。bilibiliurl完整赋值，禁止空值横线。只输出JSON。',
  ].join('\n')
}

function parseAIResponse(text) {
  try {
    // 去掉 markdown 包裹
    let t = text.replace(/```json\s*/gi, '').replace(/```\s*/g, '').trim()
    // 尝试直接解析
    if (t.startsWith('[')) return JSON.parse(t)
    if (t.startsWith('{')) {
      const parsed = JSON.parse(t)
      return Array.isArray(parsed) ? parsed : [parsed]
    }
    // 提取 JSON 片段
    const arrMatch = t.match(/\[[\s\S]*\]/)
    if (arrMatch) return JSON.parse(arrMatch[0])
    const objMatch = t.match(/\{[\s\S]*\}/)
    if (objMatch) {
      const parsed = JSON.parse(objMatch[0])
      return Array.isArray(parsed) ? parsed : [parsed]
    }
    return null
  } catch (e) {
    console.warn('[MealPlanner] parse error:', e.message)
    return null
  }
}

// ═══════════════ 组件 ═══════════════

export default function MealPlanner({ onClose, activeMember, members = [], activeMemberId = 'default', onSwitchMember, onAddMember }) {
  const memberName = activeMember?.name || '本人'
  const memberAge = activeMember?.age || ''
  const memberGender = activeMember?.gender || ''
  const memberId = activeMemberId || 'default'
  const [calories, setCalories] = useState('')
  const [allergies, setAllergies] = useState('')
  const [preference, setPreference] = useState('')
  const [mealDays, setMealDays] = useState('1')
  const [loading, setLoading] = useState(false)
  const [selectedTemplate, setSelectedTemplate] = useState(null)
  const [mealData, setMealData] = useState(null)
  const [enriching, setEnriching] = useState(false)
  const [history, setHistory] = useState(() => loadHistory(memberId))
  const [viewMode, setViewMode] = useState('generate')

  // 切换成员时清除当前食谱和个性化参数，加载新成员的历史记录
  useEffect(() => {
    setMealData(null)
    setSelectedTemplate(null)
    setCalories('')
    setAllergies('')
    setPreference('')
    setMealDays('1')
    setHistory(loadHistory(memberId))
    setViewMode('generate')
  }, [memberId])

  const theme = DIET_TEMPLATES.find(t => t.key === selectedTemplate)?.theme || '健康食谱'

  // 保存到历史
  const saveToHistory = (data) => {
    const enriched = JSON.parse(JSON.stringify(data))
    const h = loadHistory(memberId)
    h.unshift(...enriched)
    if (h.length > 30) h.length = 30
    saveHistory(memberId, h)
    setHistory(h)
  }

  const deleteHistoryItem = (idx) => {
    const h = loadHistory(memberId); h.splice(idx, 1); saveHistory(memberId, h); setHistory(h)
  }

  const loadHistoryItem = (item) => {
    setMealData([item]); setViewMode('generate')
  }

  const handleGenerate = async () => {
    setLoading(true); setMealData(null)
    const prompt = buildPrompt(memberName, memberAge, memberGender, calories, allergies, preference, mealDays, theme)
    console.log('[MealPlanner] prompt:', prompt.slice(0, 200))
    let fullText = ''

    try {
      const res = await fetch(`${BACKEND}/api/desktop/chat/stream`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: prompt, userId: 'desktop' })
      })
      console.log('[MealPlanner] response status:', res.status)
      if (!res.ok) { setLoading(false); alert('请求失败 HTTP ' + res.status); return }
      const reader = res.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
      while (true) {
        const { done, value } = await reader.read(); if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n'); buffer = lines.pop()
        for (const line of lines) {
          if (!line.trim()) continue
          try {
            const data = JSON.parse(line)
            if (data.delta) fullText += data.delta
            if (data.done) {
              setLoading(false)
              console.log('[MealPlanner] AI raw (first 500):', fullText.slice(0, 500))
              const parsed = parseAIResponse(fullText)
              if (parsed && parsed.length > 0) {
                setMealData(parsed)
                setEnriching(true)
                const enriched = await enrichBilibiliUrls(parsed)
                setMealData([...enriched])
                saveToHistory(enriched)
                setEnriching(false)
              } else {
                console.warn('[MealPlanner] JSON parse failed, raw:', fullText.slice(0, 300))
              }
            }
            if (data.error) { setLoading(false); alert('AI错误: ' + data.error) }
          } catch {}
        }
      }
    } catch (err) { setLoading(false); alert('网络错误: ' + err.message) }
  }

  const applyTemplate = (tpl) => { setSelectedTemplate(tpl.key); setMealData(null) }

  return (
    <div className="feature-panel">
      <div className="feature-header">
        <h2>🍽️ 三餐食谱</h2>
        <span className="feature-slogan">{memberName}的食谱 · 个性定制</span>
        <button className="feature-close" onClick={onClose}>✕</button>
      </div>

      <div className="feature-content">
        <div className="section">
          <h3 className="section-title">📌 快捷模板</h3>
          <div className="template-grid">
            {DIET_TEMPLATES.map(tpl => (
              <button key={tpl.key} className={`template-chip${selectedTemplate === tpl.key ? ' active' : ''}`} onClick={() => applyTemplate(tpl)}>{tpl.icon} {tpl.label}</button>
            ))}
          </div>
        </div>

        {/* 视图切换 */}
        <div className="section">
          <div className="view-toggle">
            <button className={`view-toggle-btn${viewMode === 'generate' ? ' active' : ''}`} onClick={() => setViewMode('generate')}>🤖 生成食谱</button>
            <button className={`view-toggle-btn${viewMode === 'history' ? ' active' : ''}`} onClick={() => { setViewMode('history'); setHistory(loadHistory(memberId)) }}>📋 历史记录 ({history.length})</button>
          </div>
        </div>

        {viewMode === 'generate' && (<>
        <div className="section">
          <h3 className="section-title">⚙️ 个性化参数</h3>
          <div className="meal-form">
            <div className="meal-form-row-split">
              <div className="meal-form-row"><label>🔥 每日热量 (大卡)</label><input type="number" placeholder="如：1500" value={calories} onChange={e => setCalories(e.target.value)} /></div>
              <div className="meal-form-row"><label>📅 天数</label><select value={mealDays} onChange={e => setMealDays(e.target.value)}><option value="1">1 天</option><option value="3">3 天</option><option value="7">7 天</option></select></div>
            </div>
            <div className="meal-form-row"><label>🚫 过敏/忌口</label><input placeholder="如：海鲜、花生、牛奶…" value={allergies} onChange={e => setAllergies(e.target.value)} /></div>
            <div className="meal-form-row"><label>🍜 饮食偏好</label><input placeholder="如：中餐、清淡、喜欢辣…" value={preference} onChange={e => setPreference(e.target.value)} /></div>
            <button className="save-btn-sm generate-btn" onClick={handleGenerate} disabled={loading}>{loading ? '⏳ AI生成中…' : '🤖 AI 生成食谱'}</button>
          </div>
        </div>

        {enriching && <div className="bilibili-loading">🔍 正在匹配B站视频教程…</div>}

        {mealData && mealData.map((day, di) => (
          <div key={di} className="recipe-day">
            <h2 className="recipe-day-title">{day.date || '今日'} · {day.theme || theme}</h2>

            {MEALS.map(meal => {
              const dishes = day.meals?.[meal.key]
              if (!dishes || dishes.length === 0) return null
              const mealCal = dishes.reduce((s, d) => s + (parseInt(d.calories) || 0), 0)

              return (
                <div key={meal.key} className="recipe-card" style={{ background: meal.color }}>
                  <div className="recipe-card-header" style={{ borderLeftColor: meal.accent }}>
                    <span className="recipe-meal-icon">{meal.icon}</span>
                    <strong className="recipe-meal-label">{meal.label}</strong>
                    <span className="recipe-meal-subtotal">{mealCal} kcal</span>
                  </div>
                  <div className="recipe-table-wrap">
                    <table className="recipe-table">
                      <thead><tr><th>菜品</th><th>食材</th><th>热量</th><th>B站教程</th></tr></thead>
                      <tbody>
                        {dishes.map((d, i) => (
                          <tr key={i}>
                            <td className="recipe-dish-name">{d.name}</td>
                            <td className="recipe-ingredients">{(d.ingredients || []).join('、')}</td>
                            <td className="recipe-cal-cell">{d.calories || '-'} kcal</td>
                            <td className="recipe-bilibili-cell">
                              {d.bilibiliurl
                                ? <a href={d.bilibiliurl} target="_blank" rel="noreferrer" className="bilibili-btn">📺 教程</a>
                                : <span className="bilibili-na">—</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                      <tfoot><tr className="recipe-subtotal-row"><td colSpan="2">时段小计</td><td>{mealCal} kcal</td><td></td></tr></tfoot>
                    </table>
                  </div>
                </div>
              )
            })}

            {day.summary && (
              <div className="recipe-summary">
                <h4>全日营养汇总</h4>
                <div className="recipe-summary-grid">
                  <div className="summary-item"><span className="summary-val">{day.summary.totalcal || 0}</span><span className="summary-label">总热量 kcal</span></div>
                  <div className="summary-item"><span className="summary-val">{day.summary.carb || 0}g</span><span className="summary-label">碳水化合物</span></div>
                  <div className="summary-item"><span className="summary-val">{day.summary.protein || 0}g</span><span className="summary-label">蛋白质</span></div>
                  <div className="summary-item"><span className="summary-val">{day.summary.fat || 0}g</span><span className="summary-label">脂肪</span></div>
                </div>
              </div>
            )}
          </div>
        ))}

        {loading && <div className="bubble loading-bubble"><span className="dot" /><span className="dot" /><span className="dot" /></div>}
        </> )}

        {/* ══════ 历史记录 ══════ */}
        {viewMode === 'history' && (
          <div className="section">
            {history.length === 0 ? (
              <p className="empty-hint">暂无历史食谱，生成后自动保存</p>
            ) : (
              <div className="history-recipe-list">
                {history.map((item, idx) => (
                  <div key={idx} className="history-recipe-card" onClick={() => loadHistoryItem(item)}>
                    <div className="history-recipe-left">
                      <span className="history-recipe-date">{item.date || '未知日期'}</span>
                      <span className="history-recipe-theme">{item.theme || '食谱'}</span>
                      {item.summary && <span className="history-recipe-cal">{item.summary.totalcal || 0} kcal</span>}
                    </div>
                    <button className="history-recipe-del" onClick={(e) => { e.stopPropagation(); deleteHistoryItem(idx) }}>🗑</button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
