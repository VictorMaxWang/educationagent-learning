import { useState, useEffect } from 'react'
import { ipLocation, searchAround } from '../utils/amap.js'

/**
 * 周边医院 / 门诊查询
 * 对接高德地图 Web API
 */

const SEARCH_TYPES = [
  { key: '医院', label: '🏥 综合医院' },
  { key: '诊所', label: '🏨 诊所' },
  { key: '药店', label: '💊 药店' },
  { key: '社区卫生服务中心', label: '🏘️ 社区医院' },
]

const RADIUS_OPTIONS = [
  { value: 1000, label: '1 公里' },
  { value: 3000, label: '3 公里' },
  { value: 5000, label: '5 公里' },
  { value: 10000, label: '10 公里' },
]

export default function NearbyHospitals({ onClose }) {
  const [location, setLocation] = useState(null)
  const [locating, setLocating] = useState(true)
  const [locError, setLocError] = useState('')
  const [searchType, setSearchType] = useState('医院')
  const [radius, setRadius] = useState(3000)
  const [results, setResults] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  // 初始化定位
  useEffect(() => {
    (async () => {
      setLocating(true)
      // 优先浏览器 GPS
      if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            setLocation({ lng: pos.coords.longitude, lat: pos.coords.latitude, source: 'GPS' })
            setLocating(false)
          },
          async () => {
            // GPS 失败，回退 IP 定位
            const ip = await ipLocation()
            if (ip.success) {
              setLocation({ lng: ip.lng, lat: ip.lat, city: ip.city, source: 'IP' })
            } else {
              setLocError('无法获取位置，请允许 GPS 权限或手动输入')
            }
            setLocating(false)
          },
          { timeout: 10000, enableHighAccuracy: false }
        )
      } else {
        const ip = await ipLocation()
        if (ip.success) {
          setLocation({ lng: ip.lng, lat: ip.lat, city: ip.city, source: 'IP' })
        } else {
          setLocError('无法获取位置，请手动输入地址查询')
        }
        setLocating(false)
      }
    })()
  }, [])

  const handleSearch = async () => {
    if (!location) return
    setLoading(true)
    setError('')
    const res = await searchAround(location.lng, location.lat, searchType, radius)
    if (res.success) {
      setResults(res)
    } else {
      setError(res.error || '搜索失败，请检查高德地图 Key 配置')
    }
    setLoading(false)
  }

  const formatDistance = (m) => m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`

  return (
    <div className="feature-panel">
      <div className="feature-header">
        <h2>🗺️ 周边医院</h2>
        <span className="feature-slogan">快速查找 · 就近就医</span>
        <button className="feature-close" onClick={onClose}>✕</button>
      </div>

      <div className="feature-content">
        {/* 定位状态 */}
        <div className="section">
          <div className="location-bar">
            {locating ? (
              <span className="locating">📍 正在定位…</span>
            ) : locError ? (
              <span className="loc-error">⚠️ {locError}</span>
            ) : (
              <span className="loc-ok">
                📍 {location.city || '已定位'} ({location.source})
              </span>
            )}
          </div>
        </div>

        {/* 搜索控件 */}
        <div className="section">
          <div className="search-controls">
            <div className="search-types">
              {SEARCH_TYPES.map(t => (
                <button key={t.key}
                  className={`search-type-btn${searchType === t.key ? ' active' : ''}`}
                  onClick={() => setSearchType(t.key)}>
                  {t.label}
                </button>
              ))}
            </div>
            <div className="search-row">
              <select value={radius} onChange={e => setRadius(parseInt(e.target.value))}>
                {RADIUS_OPTIONS.map(r => (
                  <option key={r.value} value={r.value}>{r.label}内</option>
                ))}
              </select>
              <button className="save-btn-sm search-btn" onClick={handleSearch} disabled={!location || loading}>
                {loading ? '⏳ 搜索中…' : '🔍 搜索'}
              </button>
            </div>
          </div>
        </div>

        {/* 错误提示 */}
        {error && <div className="error-banner">❌ {error}</div>}

        {/* 搜索结果 */}
        {results && (
          <div className="section">
            <h3 className="section-title">
              📋 找到 {results.count} 个结果
            </h3>

            {results.pois.length === 0 ? (
              <p className="empty-hint">未找到附近{searchType}，尝试扩大搜索范围</p>
            ) : (
              <div className="hospital-list">
                {results.pois.map(p => (
                  <div key={p.id} className="hospital-card">
                    <div className="hospital-rank">
                      <span className="hospital-distance">{formatDistance(p.distance)}</span>
                    </div>
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

        {/* 未搜索时的提示 */}
        {!results && !loading && (
          <div className="section">
            <div className="map-placeholder">
              <span className="map-placeholder-icon">🗺️</span>
              <p>选择类型和范围，点击搜索查看周边医疗资源</p>
              <p className="map-note">需要配置高德地图 Web 服务 Key（src/utils/amap.js）</p>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
