import { useEffect, useState } from "react";
import { getOutfitSessions, updateSessionDays, type TravelDay } from "./outfitStorage";
import {
  restaurantSearch,
  restaurantFavorite,
  restaurantFavorites,
  restaurantAddToSchedule,
} from "./request";

interface Restaurant {
  poiId?: string;
  name?: string;
  address?: string;
  location?: string;
  distance?: string;
  rating?: number;
  avgCost?: number;
  cuisine?: string[];
  businessTime?: string;
  photos?: string[];
  tel?: string;
}

const CUISINES = ["本地小吃", "火锅", "日料", "西餐", "粤菜", "甜品奶茶", "面食", "烧烤", "川菜", "咖啡"];
const TAGS = ["适合拍照", "本地人常去", "老字号", "夜宵营业", "有包厢", "素食"];
const RADII = [1000, 2000, 3000, 5000];
const PRICES = [
  { label: "不限", value: "" },
  { label: "≤50", value: "50" },
  { label: "50~100", value: "100" },
  { label: "100~200", value: "200" },
  { label: "200+", value: "200+" },
];

/** 行程内景点（带城市，用于中心点定位） */
interface SpotOption {
  label: string;
  name: string;
  city: string;
}

/**
 * 旅行规划 → 周边餐馆查询（美食推荐）。
 * 高德 key 在后端；选行程景点/手动输入定位 → 偏好筛选 → 列表 → 收藏/加入行程/导航。
 */
export default function RestaurantView({
  currentOutfitId,
  onDaysChanged,
}: {
  currentOutfitId: string;
  onDaysChanged?: () => void;
}) {
  const [days, setDays] = useState<TravelDay[]>([]);
  const [spots, setSpots] = useState<SpotOption[]>([]);
  const [centerSpot, setCenterSpot] = useState("");
  const [centerCity, setCenterCity] = useState("");
  const [manualInput, setManualInput] = useState("");

  const [radius, setRadius] = useState(2000);
  const [cuisines, setCuisines] = useState<string[]>([]);
  const [price, setPrice] = useState("");
  const [minScore, setMinScore] = useState("");
  const [tags, setTags] = useState<string[]>([]);
  const [sortType, setSortType] = useState("DISTANCE");
  const [prefOpen, setPrefOpen] = useState(false);

  const [list, setList] = useState<Restaurant[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [msg, setMsg] = useState("");
  const [favorites, setFavorites] = useState<Set<string>>(new Set());

  const [adding, setAdding] = useState<Restaurant | null>(null);
  const [targetDayId, setTargetDayId] = useState("");
  const [targetPeriod, setTargetPeriod] = useState("lunch");

  const prefKey = `desktop-restaurant-pref-${currentOutfitId}`;

  useEffect(() => {
    const sessions = getOutfitSessions();
    const s = sessions.find((x) => x.sessionId === currentOutfitId);
    const ds = s?.days ?? [];
    setDays(ds);
    setTargetDayId(ds[0]?.dayId ?? "");
    const opts: SpotOption[] = [];
    for (const d of ds) {
      for (const sp of d.spots || []) {
        opts.push({ label: `第${d.day}天 · ${sp}`, name: sp, city: d.city || "" });
      }
    }
    setSpots(opts);
    try {
      const saved = localStorage.getItem(prefKey);
      if (saved) {
        const p = JSON.parse(saved);
        if (p.radius) setRadius(p.radius);
        if (p.cuisines) setCuisines(p.cuisines);
        if (p.price) setPrice(p.price);
        if (p.minScore) setMinScore(p.minScore);
        if (p.tags) setTags(p.tags);
        if (p.sortType) setSortType(p.sortType);
      }
    } catch {
      /* 忽略坏偏好 */
    }
    if (currentOutfitId) {
      restaurantFavorites(currentOutfitId).then((res) => {
        if (res?.code === 200 && Array.isArray(res.data)) {
          setFavorites(new Set(res.data.map((f: any) => f.poiId)));
        }
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentOutfitId]);

  const savePref = () => {
    localStorage.setItem(prefKey, JSON.stringify({ radius, cuisines, price, minScore, tags, sortType }));
  };

  const buildPreference = () => {
    const pref: Record<string, unknown> = { cuisineList: cuisines, tagList: tags };
    if (price === "50") pref.maxPrice = 50;
    else if (price === "100") { pref.minPrice = 50; pref.maxPrice = 100; }
    else if (price === "200") { pref.minPrice = 100; pref.maxPrice = 200; }
    else if (price === "200+") pref.minPrice = 200;
    if (minScore) pref.minScore = Number(minScore);
    return pref;
  };

  const doSearch = async () => {
    const locName = centerSpot || manualInput.trim();
    if (!locName) {
      setError("请先选择一个景点或手动输入地点");
      return;
    }
    setLoading(true);
    setError("");
    setMsg("");
    try {
      const res = await restaurantSearch({
        locationName: locName,
        city: centerCity || undefined,
        radius,
        preference: buildPreference(),
        sortType,
      });
      if (res?.code === 200) {
        setList(res.data || []);
        if (!res.data?.length) setError("无匹配餐馆，试试扩大搜索半径或放宽口味偏好");
      } else {
        setError(res?.msg || "搜索失败");
      }
    } finally {
      setLoading(false);
    }
  };

  const toggle = (arr: string[], v: string) =>
    arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v];

  const doFavorite = async (r: Restaurant) => {
    const loc = (r.location || "").split(",");
    const res = await restaurantFavorite({
      travelPlanId: currentOutfitId,
      poiId: r.poiId,
      name: r.name,
      lon: loc[0],
      lat: loc[1],
      avgCost: r.avgCost,
      rating: r.rating,
    });
    setMsg(res?.msg || "操作完成");
    if (r.poiId) setFavorites((prev) => new Set(prev).add(r.poiId!));
  };

  const openAdd = (r: Restaurant) => {
    setAdding(r);
    // 默认放进"用该景点搜到餐馆"的那一天
    const spotDay = days.find((d) => (d.spots || []).includes(centerSpot));
    setTargetDayId(spotDay?.dayId ?? days[0]?.dayId ?? "");
    setTargetPeriod("lunch");
  };

  const confirmAdd = async () => {
    if (!adding) return;
    const res = await restaurantAddToSchedule({
      scheduleId: targetDayId,
      poiId: adding.poiId,
      name: adding.name,
      timePeriod: targetPeriod,
      remark: "",
    });
    // 同步把餐馆名插到"用该景点搜到它"的景点后面（找不到该景点则追加到末尾）
    const label = `${adding.name}（${targetPeriod === "lunch" ? "午餐" : "晚餐"}）`;
    const ds = days.map((d) => {
      if (d.dayId !== targetDayId) return d;
      const spots = [...(d.spots || [])];
      const idx = centerSpot ? spots.findIndex((s) => s === centerSpot) : -1;
      if (idx >= 0) spots.splice(idx + 1, 0, label);
      else spots.push(label);
      return { ...d, spots };
    });
    setDays(ds);
    updateSessionDays(currentOutfitId, ds);
    onDaysChanged?.(); // 通知 MaimemoOutfit 刷新计划视图
    setMsg(res?.msg || "已加入行程");
    setAdding(null);
  };

  const navigate = (r: Restaurant) => {
    const [lng, lat] = (r.location || "").split(",");
    if (!lng || !lat) return;
    window.open(
      `https://uri.amap.com/navigation?to=${lng},${lat},${encodeURIComponent(r.name || "")}&mode=car`,
      "_blank",
    );
  };

  const fmtDist = (d?: string) => {
    const n = Number(d);
    if (!d || isNaN(n)) return "";
    return n < 1000 ? `${n}m` : `${(n / 1000).toFixed(1)}km`;
  };

  return (
    <div className="ofit-section rest-root">
      <div className="ofit-section-title">🍽 周边餐馆查询</div>

      {/* 中心点 */}
      <div className="rest-center">
        {spots.length > 0 && (
          <>
            <label className="rest-label">选择行程内景点定位</label>
            <select
              className="ofit-input rest-select"
              value={centerSpot}
              onChange={(e) => {
                const sp = spots.find((x) => x.name === e.target.value);
                setCenterSpot(e.target.value);
                setCenterCity(sp?.city || "");
              }}
            >
              <option value="">—— 选择景点 ——</option>
              {spots.map((s, i) => (
                <option key={i} value={s.name}>
                  {s.label}
                </option>
              ))}
            </select>
          </>
        )}
        <label className="rest-label">或手动输入地点 / 地址</label>
        <input
          className="ofit-input"
          value={manualInput}
          onChange={(e) => setManualInput(e.target.value)}
          placeholder="如：西湖断桥 / 杭州市西湖区…"
        />
      </div>

      {/* 偏好面板 */}
      <button className="rest-pref-toggle" onClick={() => setPrefOpen((v) => !v)}>
        {prefOpen ? "▾" : "▸"} 偏好筛选（半径 / 菜系 / 人均 / 评分 / 标签）
      </button>
      {prefOpen && (
        <div className="rest-pref">
          <div className="rest-label">搜索半径</div>
          <div className="rest-chips">
            {RADII.map((r) => (
              <button key={r} className={`rest-chip ${radius === r ? "active" : ""}`} onClick={() => setRadius(r)}>
                {r / 1000}km
              </button>
            ))}
          </div>
          <div className="rest-label">菜系</div>
          <div className="rest-chips">
            {CUISINES.map((c) => (
              <button key={c} className={`rest-chip ${cuisines.includes(c) ? "active" : ""}`} onClick={() => setCuisines(toggle(cuisines, c))}>
                {c}
              </button>
            ))}
          </div>
          <div className="rest-label">人均预算</div>
          <div className="rest-chips">
            {PRICES.map((p) => (
              <button key={p.value} className={`rest-chip ${price === p.value ? "active" : ""}`} onClick={() => setPrice(p.value)}>
                {p.label}
              </button>
            ))}
          </div>
          <div className="rest-label">最低评分</div>
          <div className="rest-chips">
            <button className={`rest-chip ${minScore === "" ? "active" : ""}`} onClick={() => setMinScore("")}>不限</button>
            {["4.0", "4.5"].map((s) => (
              <button key={s} className={`rest-chip ${minScore === s ? "active" : ""}`} onClick={() => setMinScore(s)}>
                ≥{s}
              </button>
            ))}
          </div>
          <div className="rest-label">特色标签</div>
          <div className="rest-chips">
            {TAGS.map((t) => (
              <button key={t} className={`rest-chip ${tags.includes(t) ? "active" : ""}`} onClick={() => setTags(toggle(tags, t))}>
                {t}
              </button>
            ))}
          </div>
          <div className="rest-label">排序</div>
          <div className="rest-chips">
            <button className={`rest-chip ${sortType === "DISTANCE" ? "active" : ""}`} onClick={() => setSortType("DISTANCE")}>距离最近</button>
            <button className={`rest-chip ${sortType === "SCORE" ? "active" : ""}`} onClick={() => setSortType("SCORE")}>评分优先</button>
            <button className={`rest-chip ${sortType === "PRICE_ASC" ? "active" : ""}`} onClick={() => setSortType("PRICE_ASC")}>人均低价</button>
          </div>
          <button
            className="btn send-btn rest-search-btn"
            onClick={() => {
              savePref();
              doSearch();
            }}
            disabled={loading}
          >
            {loading ? "搜索中…" : "🔍 搜索附近餐馆"}
          </button>
        </div>
      )}

      {msg && <div className="rest-msg">{msg}</div>}
      {error && <div className="rest-err">{error}</div>}

      {/* 列表 */}
      <div className="rest-list">
        {list.map((r, i) => (
          <div className="rest-card" key={r.poiId || i}>
            {r.photos?.[0] && <img className="rest-photo" src={r.photos[0]} alt="餐馆" />}
            <div className="rest-info">
              <div className="rest-name">{r.name}</div>
              <div className="rest-meta">
                {fmtDist(r.distance) && <span>📍{fmtDist(r.distance)}</span>}
                {(r.rating ?? 0) > 0 && <span className="rest-rating">⭐{r.rating}</span>}
                {(r.avgCost ?? 0) > 0 && <span>💴{r.avgCost}元</span>}
              </div>
              {r.cuisine?.length ? <div className="rest-cuisine">{r.cuisine.join(" · ")}</div> : null}
              {r.address && <div className="rest-addr">📍 {r.address}</div>}
              {r.businessTime && <div className="rest-time">🕐 {r.businessTime}</div>}
              <div className="rest-actions">
                <button
                  className="rest-btn"
                  onClick={() => doFavorite(r)}
                  disabled={favorites.has(r.poiId || "")}
                >
                  {favorites.has(r.poiId || "") ? "★ 已收藏" : "☆ 收藏"}
                </button>
                <button className="rest-btn" onClick={() => openAdd(r)}>
                  加入行程
                </button>
                <button className="rest-btn" onClick={() => navigate(r)}>
                  导航
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* 加入行程弹层 */}
      {adding && (
        <div className="modal-mask">
          <div className="modal">
            <div className="modal-title">加入当日行程</div>
            <div className="modal-body">
              <div className="rest-label">将「{adding.name}」加入：</div>
              <select className="ofit-input" value={targetDayId} onChange={(e) => setTargetDayId(e.target.value)}>
                {days.map((d) => (
                  <option key={d.dayId} value={d.dayId}>
                    第{d.day}天（{d.city || "—"}）
                  </option>
                ))}
              </select>
              <div className="rest-period">
                <button className={`rest-period-btn ${targetPeriod === "lunch" ? "active" : ""}`} onClick={() => setTargetPeriod("lunch")}>
                  <span className="rest-period-ico">🍚</span>午餐
                </button>
                <button className={`rest-period-btn ${targetPeriod === "dinner" ? "active" : ""}`} onClick={() => setTargetPeriod("dinner")}>
                  <span className="rest-period-ico">🍽</span>晚餐
                </button>
              </div>
            </div>
            <div className="rest-modal-btns">
              <button className="rest-modal-btn ghost" onClick={() => setAdding(null)}>
                取消
              </button>
              <button className="rest-modal-btn confirm" onClick={confirmAdd}>
                ✅ 确认加入
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
