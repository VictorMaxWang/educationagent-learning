import { useEffect, useRef, useState } from "react";
import { renderMarkdown } from "../../chat/markdown";
import { ipLocation, searchAround } from "../utils/amap.js";

/**
 * 🩺 健康咨询 — 专科 AI 聊天 + 周边医院
 */

const API_BASE = import.meta.env.VITE_API_BASE_URL;

const CATS = [
  { key: "sport", icon: "🏃", label: "运动减重", prompt: "你是一位专业运动教练+体重管理师。定制跑步、力量、燃脂计划，制定减脂增肌目标并追踪进度，矫正久坐体态和不良姿势。" },
  { key: "diet", icon: "🥗", label: "饮食营养", prompt: "你是一位注册营养师。计算每日热量需求，搭配营养均衡的三餐，推荐健康食谱，分析食物成分和改进方向。" },
  { key: "sleep", icon: "🌙", label: "睡眠心理", prompt: "你是一位睡眠+心理健康顾问。分析失眠原因给出改善方案，同时进行情绪疏导、正念冥想指导，缓解焦虑和压力。严重情况建议就医。" },
  { key: "tcm", icon: "🌿", label: "中医养生", prompt: "你是一位中医养生专家。根据体质推荐食疗药膳、穴位按摩，结合二十四节气给出当季养生建议。" },
  { key: "women", icon: "🌸", label: "女性健康", prompt: "你是一位女性健康顾问。解答经期管理、备孕调理、孕期营养、产后恢复、更年期养护等问题。" },
  { key: "hospital", icon: "🏥", label: "周边医院", prompt: "" },
];

const PLACEHOLDERS = {
  sport: "告知身高体重、场地，定制健身减脂计划…",
  diet: "需要食谱、忌口、食材搭配直接描述…",
  sleep: "诉说失眠、熬夜、情绪烦恼…",
  tcm: "描述怕冷、出油、疲惫等体感判断体质…",
  women: "经期、气血、妇科养护相关问题…",
};

const SEARCH_TYPES = [
  { key: '医院', label: '🏥 综合医院' },
  { key: '诊所', label: '🏨 诊所' },
  { key: '药店', label: '💊 药店' },
  { key: '社区卫生服务中心', label: '🏘️ 社区医院' },
];

const RADIUS_OPTIONS = [
  { value: 1000, label: '1 公里' },
  { value: 3000, label: '3 公里' },
  { value: 5000, label: '5 公里' },
  { value: 10000, label: '10 公里' },
];

const formatDistance = (m) => m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`;

export default function HealthConsult({ onClose, activeMember }) {
  const [cat, setCat] = useState(CATS[0]);
  const [msgs, setMsgs] = useState([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const endRef = useRef(null);

  // ── 周边医院状态 ──
  const [hospLoc, setHospLoc] = useState(null);
  const [hospLocating, setHospLocating] = useState(true);
  const [hospLocError, setHospLocError] = useState('');
  const [hospType, setHospType] = useState('医院');
  const [hospRadius, setHospRadius] = useState(3000);
  const [hospResults, setHospResults] = useState(null);
  const [hospLoading, setHospLoading] = useState(false);
  const [hospError, setHospError] = useState('');

  const isHospital = cat.key === "hospital";

  // 初次进入周边医院 Tab 时初始化定位
  useEffect(() => {
    if (!isHospital || hospLoc) return;
    (async () => {
      setHospLocating(true);
      if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            setHospLoc({ lng: pos.coords.longitude, lat: pos.coords.latitude, source: 'GPS' });
            setHospLocating(false);
          },
          async () => {
            const ip = await ipLocation();
            if (ip.success) setHospLoc({ lng: ip.lng, lat: ip.lat, city: ip.city, source: 'IP' });
            else setHospLocError('无法获取位置，请允许 GPS 权限');
            setHospLocating(false);
          },
          { timeout: 10000, enableHighAccuracy: false }
        );
      } else {
        const ip = await ipLocation();
        if (ip.success) setHospLoc({ lng: ip.lng, lat: ip.lat, city: ip.city, source: 'IP' });
        else setHospLocError('无法获取位置，请手动输入地址查询');
        setHospLocating(false);
      }
    })();
  }, [isHospital, hospLoc]);

  // 切出医院 Tab 时不清空搜索结果，保留以便切回查看

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [msgs, loading]);

  const send = async () => {
    const text = input.trim();
    if (!text || loading) return;
    setInput("");
    const userMsg = { role: "user", content: text };
    setMsgs((prev) => [...prev, userMsg]);
    setLoading(true);
    try {
      const fullMsg = `${cat.prompt}\n\n【回复格式】使用标准Markdown，用 **加粗** 和 - 列表，数据用表格。\n\n${text}`;
      const res = await fetch(`${API_BASE}/api/desktop/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: fullMsg, userId: "desktop" }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let full = "";
      setMsgs((prev) => [...prev, { role: "assistant", content: "" }]);
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop();
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const data = JSON.parse(line);
            if (data.delta) {
              full += data.delta;
              setMsgs((prev) => {
                const next = [...prev];
                const last = next[next.length - 1];
                if (last?.role === "assistant") next[next.length - 1] = { ...last, content: full };
                return next;
              });
            }
            if (data.error) {
              setMsgs((prev) => {
                const next = [...prev];
                const last = next[next.length - 1];
                if (last?.role === "assistant") next[next.length - 1] = { ...last, content: (last.content || "") + `\n\n❌ ${data.error}` };
                return next;
              });
            }
          } catch { /* skip */ }
        }
      }
    } catch (e) {
      setMsgs((prev) => [...prev, { role: "assistant", content: `❌ 请求失败：${e.message}` }]);
    } finally {
      setLoading(false);
    }
  };

  const handleHospSearch = async () => {
    if (!hospLoc) return;
    setHospLoading(true);
    setHospError('');
    const res = await searchAround(hospLoc.lng, hospLoc.lat, hospType, hospRadius);
    if (res.success) setHospResults(res);
    else setHospError(res.error || '搜索失败');
    setHospLoading(false);
  };

  return (
    <div className="feature-panel hc-panel">
      <div className="feature-header">
        <h2>🩺 健康咨询</h2>
        <span className="feature-slogan">{cat.label}</span>
        <button className="feature-close" onClick={onClose}>✕</button>
      </div>

      <div className="hc-cats">
        {CATS.map((c) => (
          <button key={c.key} className={`hc-cat${cat.key === c.key ? " active" : ""}`} onClick={() => setCat(c)}>
            {c.icon} {c.label}
          </button>
        ))}
      </div>

      {/* ═══ 周边医院 内容 ═══ */}
      {isHospital ? (
        <div className="hc-chat">
          {/* 定位状态 */}
          <div className="section" style={{ marginBottom: 10 }}>
            <div className="location-bar">
              {hospLocating ? <span className="locating">📍 正在定位…</span>
              : hospLocError ? <span className="loc-error">⚠️ {hospLocError}</span>
              : <span className="loc-ok">📍 {hospLoc?.city || '已定位'} ({hospLoc?.source})</span>}
            </div>
          </div>

          {/* 搜索控件 */}
          <div className="section" style={{ marginBottom: 10 }}>
            <div className="search-controls">
              <div className="search-types">
                {SEARCH_TYPES.map(t => (
                  <button key={t.key}
                    className={`search-type-btn${hospType === t.key ? ' active' : ''}`}
                    onClick={() => setHospType(t.key)}>
                    {t.label}
                  </button>
                ))}
              </div>
              <div className="search-row">
                <select value={hospRadius} onChange={e => setHospRadius(parseInt(e.target.value))}>
                  {RADIUS_OPTIONS.map(r => <option key={r.value} value={r.value}>{r.label}内</option>)}
                </select>
                <button className="save-btn-sm search-btn" onClick={handleHospSearch} disabled={!hospLoc || hospLoading}>
                  {hospLoading ? '⏳ 搜索中…' : '🔍 搜索'}
                </button>
              </div>
            </div>
          </div>

          {hospError && <div className="error-banner" style={{ marginBottom: 8 }}>❌ {hospError}</div>}

          {hospResults && (
            <div className="section" style={{ marginBottom: 0 }}>
              <h3 className="section-title">📋 找到 {hospResults.count} 个结果</h3>
              {hospResults.pois.length === 0 ? (
                <p className="empty-hint">未找到附近{hospType}，尝试扩大搜索范围</p>
              ) : (
                <div className="hospital-list">
                  {hospResults.pois.map(p => (
                    <div key={p.id} className="hospital-card">
                      <div className="hospital-rank"><span className="hospital-distance">{formatDistance(p.distance)}</span></div>
                      <div className="hospital-info">
                        <strong className="hospital-name">{p.name}</strong>
                        <span className="hospital-addr">📍 {p.address || '地址未收录'}</span>
                        {p.type && <span className="hospital-type">{p.type.split(';').slice(0, 2).join(' · ')}</span>}
                      </div>
                      <div className="hospital-actions">
                        {p.phone && <a href={`tel:${p.phone}`} className="hospital-call" title="拨打电话">📞</a>}
                        <a href={`https://uri.amap.com/navigation?to=${p.lng},${p.lat},${encodeURIComponent(p.name)}`}
                          target="_blank" rel="noreferrer" className="hospital-nav" title="导航">🧭</a>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {!hospResults && !hospLoading && (
            <div className="section">
              <div className="map-placeholder">
                <span className="map-placeholder-icon">🗺️</span>
                <p>选择类型和范围，点击搜索查看周边医疗资源</p>
              </div>
            </div>
          )}
        </div>
      ) : (
        <>
          <div className="hc-chat">
            {msgs.length === 0 ? (
              <div className="hc-welcome">
                <div className="hc-welcome-icon">{cat.icon}</div>
                <div className="hc-welcome-title">{cat.label}</div>
                <div className="hc-welcome-sub">{cat.prompt}</div>
              </div>
            ) : (
              msgs.map((m, i) => (
                <div key={i} className={`hc-msg ${m.role}`}>
                  {m.role === "user" ? "👤" : cat.icon}
                  <div className="hc-bubble">
                    {m.role === "assistant" && m.content
                      ? <div dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }} />
                      : m.content}
                  </div>
                </div>
              ))
            )}
            {loading && <div className="hc-msg assistant">{cat.icon}<div className="hc-bubble"><span className="dot" /><span className="dot" /><span className="dot" /></div></div>}
            <div ref={endRef} />
          </div>

          <div className="hc-input-bar">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder={PLACEHOLDERS[cat.key] || "描述你的问题…"}
              disabled={loading}
              rows={1}
            />
            <button className="hc-send" onClick={send} disabled={loading || !input.trim()}>
              {loading ? "…" : "发送"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
