import { useEffect, useRef, useState } from "react";

/**
 * 实时地图页面（高德地图 JS API 2.0 直连，无后端依赖）
 * - 地图渲染 / POI 搜索 / 地理编码 / 路线规划（驾车·步行·公交）全部由浏览器端 AMap 完成
 * - 高德服务端 key（amap.key）仍在后端仅供 Agent/微信端使用，本页只用 Web 端公开 JS key
 */
declare global {
  interface Window {
    AMap: any;
    _AMapSecurityConfig?: { securityJsCode: string };
  }
}

const AMAP_KEY = import.meta.env.VITE_AMAP_WEB_KEY as string;
const AMAP_SECURITY = import.meta.env.VITE_AMAP_SECURITY_JS_CODE as string | undefined;
const API_BASE = import.meta.env.VITE_API_BASE_URL as string;

/** 高德 JS 脚本只加载一次，全局复用 */
let amapPromise: Promise<any> | null = null;
function loadAmap(): Promise<any> {
  if (window.AMap) return Promise.resolve(window.AMap);
  if (amapPromise) return amapPromise;
  if (!AMAP_KEY || AMAP_KEY.trim() === "" || AMAP_KEY.startsWith("你的")) {
    return Promise.reject(new Error("未配置 VITE_AMAP_WEB_KEY（请在高德控制台申请 Web 端 JS API Key 并填入 .env）"));
  }
  // 安全密钥须在脚本加载前设置
  if (AMAP_SECURITY) window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY };
  amapPromise = new Promise((resolve, reject) => {
    const s = document.createElement("script");
    // 一次性预加载所有用到的插件：运行时 AMap.plugin() 在 Tauri 下可能回调不触发导致按钮卡死
    s.src =
      `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(AMAP_KEY)}` +
      `&plugin=AMap.PlaceSearch,AMap.Geocoder,AMap.Driving,AMap.Walking,AMap.Transfer`;
    s.async = true;
    s.onload = () => resolve(window.AMap);
    s.onerror = () => {
      amapPromise = null;
      reject(new Error("高德地图 JS 加载失败，请检查网络或 Key 配置"));
    };
    document.head.appendChild(s);
  });
  return amapPromise;
}

/** 加载指定 AMap 插件后 resolve；超时保护，避免回调不触发导致 busy 卡死、按钮全部禁用 */
function withPlugin(AMap: any, plugin: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const check = () => {
      const name = plugin.split(".")[1];
      if (typeof AMap?.[name] === "function") resolve();
      else reject(new Error(`高德插件 ${plugin} 加载失败`));
    };
    const timer = setTimeout(() => {
      const name = plugin.split(".")[1];
      if (typeof AMap?.[name] === "function") resolve();
      else reject(new Error(`高德插件 ${plugin} 加载超时，请刷新页面重试`));
    }, 15000);
    try {
      AMap.plugin([plugin], () => {
        clearTimeout(timer);
        check();
      });
    } catch (e) {
      clearTimeout(timer);
      reject(e instanceof Error ? e : new Error(String(e)));
    }
  });
}

interface Poi {
  name: string;
  address?: string;
  location: [number, number]; // [lng, lat]
  tel?: string;
}

function fmtKm(m?: number): string {
  if (m == null || isNaN(m)) return "-";
  return m < 1000 ? `${Math.round(m)}米` : `${(m / 1000).toFixed(1)}公里`;
}
function fmtTime(sec?: number): string {
  if (sec == null || isNaN(sec)) return "-";
  if (sec < 60) return `${Math.round(sec)}秒`;
  if (sec < 3600) return `${Math.round(sec / 60)}分钟`;
  const h = Math.floor(sec / 3600);
  const m = Math.round((sec % 3600) / 60);
  return `${h}小时${m > 0 ? m + "分" : ""}`;
}

/**
 * 高德 REST 接口走后端代理（正向地理编码 + 方向路线）。
 * 实测 AMap.Geocoder.getLocation 与 Driving/Walking/Transfer 在 Tauri 下回调不触发，
 * 仅反向 getAddress / POI 搜索可用；故正向编码与方向都走代理。
 * 后端用 Web服务 key（amap.key）调高德，本函数只把参数转发给 /api/desktop/amap/proxy。
 */
async function amapRest(path: string, params: Record<string, string | number>) {
  const url = new URL(`${API_BASE}/api/desktop/amap/proxy`);
  url.searchParams.set("path", path);
  Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, String(v)));
  const res = await fetch(url.toString());
  if (!res.ok) throw new Error(`高德代理 HTTP ${res.status}`);
  const data = await res.json();
  if (data.status !== "1") throw new Error(`${data.info || "高德接口错误"}（${path}）`);
  return data;
}

/** 高德折线字符串 "lng,lat;lng,lat;..." → [[lng,lat],...] */
function parsePolyline(s: string): [number, number][] {
  return s
    .split(";")
    .filter(Boolean)
    .map((p) => {
      const [x, y] = p.split(",");
      return [parseFloat(x), parseFloat(y)] as [number, number];
    });
}

export default function MapView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<any>(null);
  const markersRef = useRef<any[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState("");
  const [keyword, setKeyword] = useState("");
  const [origin, setOrigin] = useState("");
  const [destination, setDestination] = useState("");
  const [routeType, setRouteType] = useState("driving");
  const [city, setCity] = useState("杭州");
  const [pois, setPois] = useState<Poi[]>([]);
  const [msg, setMsg] = useState("");
  const [routeInfo, setRouteInfo] = useState("");
  const [busy, setBusy] = useState(false);

  // 供事件回调使用的最新 city（避免闭包捕获旧值）
  const cityRef = useRef(city);
  useEffect(() => {
    cityRef.current = city;
  }, [city]);

  // 初始化地图
  useEffect(() => {
    let disposed = false;
    setStatus("loading");
    loadAmap()
      .then((AMap) => {
        if (disposed || !containerRef.current) return;
        const map = new AMap.Map(containerRef.current, {
          zoom: 11,
          center: [120.15, 30.28], // 默认杭州
          resizeEnable: true,
        });
        mapRef.current = map;
        // 点击地图 → 逆地理编码显示地址
        map.on("click", (e: any) => {
          const lng = e.lnglat.getLng();
          const lat = e.lnglat.getLat();
          withPlugin(AMap, "AMap.Geocoder").then(() => {
            const geocoder = new AMap.Geocoder({ city: cityRef.current });
            geocoder.getAddress([lng, lat], (st: string, r: any) => {
              if (st === "complete" && r.regeocode) {
                setMsg(`📍 ${lng.toFixed(6)}, ${lat.toFixed(6)}\n${r.regeocode.formattedAddress || "未知地址"}`);
              }
            });
          });
        });
        setStatus("ready");
      })
      .catch((e) => {
        if (!disposed) {
          setStatus("error");
          setError(String(e?.message || e));
        }
      });
    const onResize = () => mapRef.current?.resize?.();
    window.addEventListener("resize", onResize);
    return () => {
      disposed = true;
      window.removeEventListener("resize", onResize);
      try {
        mapRef.current?.destroy?.();
      } catch {
        /* 忽略销毁异常 */
      }
      mapRef.current = null;
      markersRef.current = [];
    };
  }, []);

  const clearMarkers = () => {
    markersRef.current.forEach((m) => {
      try {
        m.setMap(null);
      } catch {
        /* 忽略 */
      }
    });
    markersRef.current = [];
  };

  /** POI 关键词搜索 → 地图打点 + 右侧列表 */
  const handleSearch = async () => {
    const kw = keyword.trim();
    if (!kw || !mapRef.current) return;
    const AMap = window.AMap;
    setBusy(true);
    setMsg("");
    setPois([]);
    try {
      await withPlugin(AMap, "AMap.PlaceSearch");
      const placeSearch = new AMap.PlaceSearch({ pageSize: 10, city: cityRef.current, map: mapRef.current });
      placeSearch.search(kw, (st: string, result: any) => {
        setBusy(false);
        if (st !== "complete" || !result.poiList?.pois?.length) {
          setMsg("未找到相关地点");
          return;
        }
        clearMarkers();
        const list: Poi[] = [];
        result.poiList.pois.forEach((poi: any, i: number) => {
          const loc: [number, number] = [poi.location.lng, poi.location.lat];
          list.push({ name: poi.name, address: poi.address, location: loc, tel: poi.tel });
          const marker = new AMap.Marker({
            position: loc,
            title: poi.name,
            label: { content: String(i + 1), direction: "center" },
          });
          marker.on("click", () => {
            mapRef.current.setCenter(loc);
            mapRef.current.setZoom(15);
          });
          marker.setMap(mapRef.current);
          markersRef.current.push(marker);
        });
        setPois(list);
        setMsg(`🔍 找到 ${result.poiList.count ?? list.length} 个结果`);
        if (list.length) mapRef.current.setFitView(markersRef.current);
      });
    } catch (e: any) {
      setBusy(false);
      setMsg("搜索失败：" + (e?.message || e));
    }
  };

  /** 地址 → 定位（地理编码） */
  const handleLocate = async () => {
    const addr = keyword.trim();
    if (!addr || !mapRef.current) return;
    const AMap = window.AMap;
    setBusy(true);
    setMsg("");
    try {
      await withPlugin(AMap, "AMap.Geocoder");
      const geocoder = new AMap.Geocoder({ city: cityRef.current });
      geocoder.getLocation(addr, (st: string, result: any) => {
        setBusy(false);
        if (st !== "complete" || !result.geocodes?.length) {
          setMsg("地址解析失败，试试更完整的地址");
          return;
        }
        const gc = result.geocodes[0];
        const loc: [number, number] = [gc.location.lng, gc.location.lat];
        clearMarkers();
        const marker = new AMap.Marker({ position: loc, title: addr });
        marker.setMap(mapRef.current);
        markersRef.current.push(marker);
        mapRef.current.setCenter(loc);
        mapRef.current.setZoom(15);
        setMsg(`📍 ${gc.formattedAddress || addr}`);
      });
    } catch (e: any) {
      setBusy(false);
      setMsg("定位失败：" + (e?.message || e));
    }
  };

  /**
   * 把起点/终点文本解析成 "经度,纬度"（走后端代理）。
   * 实测 AMap.Geocoder.getLocation（正向编码）在 Tauri 下回调不触发（与方向插件同病），
   * 只有反向 getAddress / POI 搜索可用；故正向编码统一走后端代理（服务端 Web服务 key）。
   */
  const resolveLocationText = async (text: string): Promise<string> => {
    const t = text.trim();
    if (/^\d+\.?\d*,\d+\.?\d*$/.test(t)) return t;
    const data = await amapRest("v3/geocode/geo", { address: t, city: cityRef.current });
    const loc = data.geocodes?.[0]?.location as string | undefined;
    if (!loc) throw new Error(`无法解析「${t}」的位置`);
    return loc;
  };

  /** 路线规划（驾车 / 步行 / 公交）：直调方向 REST 接口，自绘折线 */
  const handleRoute = async () => {
    if (!origin.trim() || !destination.trim() || !mapRef.current) return;
    const AMap = window.AMap;
    setBusy(true);
    setMsg("");
    setRouteInfo("");
    try {
      const oLoc = await resolveLocationText(origin);
      const dLoc = await resolveLocationText(destination);
      console.log("[MapView] 规划路线:", routeType, oLoc, "→", dLoc);

      mapRef.current.clearMap(); // 清掉旧路线/标记，避免叠加
      markersRef.current = [];
      const toLngLat = (s: string): [number, number] => {
        const [x, y] = s.split(",");
        return [parseFloat(x), parseFloat(y)];
      };
      const mkO = new AMap.Marker({
        position: toLngLat(oLoc),
        title: origin.trim(),
        label: { content: "起", direction: "center" },
      });
      const mkD = new AMap.Marker({
        position: toLngLat(dLoc),
        title: destination.trim(),
        label: { content: "终", direction: "center" },
      });
      mkO.setMap(mapRef.current);
      mkD.setMap(mapRef.current);
      markersRef.current.push(mkO, mkD);

      let path: string;
      const params: Record<string, string | number> = { origin: oLoc, destination: dLoc, extensions: "all" };
      if (routeType === "walking") {
        path = "v3/direction/walking";
      } else if (routeType === "transit") {
        path = "v3/direction/transit/integrated";
        params.city = cityRef.current;
      } else {
        path = "v3/direction/driving";
        params.strategy = 0;
      }
      const data = await amapRest(path, params);
      console.log("[MapView] 路线结果 status:", data.status, "info:", data.info);

      let distance: number | undefined;
      let duration: number | undefined;
      const polylines: [number, number][][] = [];
      if (routeType === "transit") {
        const plan = data.route?.transits?.[0];
        if (!plan) throw new Error("未找到公交路线");
        distance = plan.distance;
        duration = plan.duration;
        (plan.segments || []).forEach((seg: any) => {
          const bl = seg.bus?.buslines?.[0];
          if (bl?.polyline) polylines.push(parsePolyline(bl.polyline));
          if (seg.walking?.polyline) polylines.push(parsePolyline(seg.walking.polyline));
        });
      } else {
        const plan = data.route?.paths?.[0];
        if (!plan) throw new Error("未找到路线");
        distance = plan.distance;
        duration = plan.duration;
        (plan.steps || []).forEach((s: any) => {
          if (s.polyline) polylines.push(parsePolyline(s.polyline));
        });
      }

      polylines.forEach((pl) => {
        new AMap.Polyline({
          path: pl,
          strokeColor: "#1677ff",
          strokeWeight: 6,
          strokeOpacity: 0.85,
          lineJoin: "round",
        }).setMap(mapRef.current);
      });
      const label = routeType === "driving" ? "🚗 驾车" : routeType === "walking" ? "🚶 步行" : "🚌 公交";
      setRouteInfo(`${label}路线：${fmtKm(distance)} · 预计 ${fmtTime(duration)}`);
      setMsg(`已绘制 ${origin.trim()} → ${destination.trim()}`);
      mapRef.current.setFitView();
    } catch (e: any) {
      console.error("[MapView] 路线规划异常:", e);
      setMsg("路线规划失败：" + (e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  const handleClear = () => {
    mapRef.current?.clearMap?.();
    markersRef.current = [];
    setPois([]);
    setMsg("");
    setRouteInfo("");
  };

  return (
    <div className="map-view">
      <div className="map-toolbar">
        <div className="map-row">
          <input
            className="map-input"
            value={keyword}
            placeholder="搜索地点 / 输入地址后点「定位」"
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
          <button className="map-btn primary" onClick={handleSearch} disabled={busy}>
            🔍 搜索
          </button>
          <button className="map-btn" onClick={handleLocate} disabled={busy}>
            📍 定位
          </button>
        </div>
        <div className="map-row">
          <input
            className="map-input"
            value={origin}
            placeholder="起点（地址或 经度,纬度）"
            onChange={(e) => setOrigin(e.target.value)}
          />
          <input
            className="map-input"
            value={destination}
            placeholder="终点"
            onChange={(e) => setDestination(e.target.value)}
          />
          <select
            className="map-select"
            value={routeType}
            onChange={(e) => setRouteType(e.target.value)}
          >
            <option value="driving">🚗 驾车</option>
            <option value="walking">🚶 步行</option>
            <option value="transit">🚌 公交</option>
          </select>
          {routeType === "transit" && (
            <input
              className="map-input map-input-sm"
              value={city}
              placeholder="城市"
              onChange={(e) => setCity(e.target.value)}
            />
          )}
          <button className="map-btn primary" onClick={handleRoute} disabled={busy}>
            🚀 规划路线
          </button>
          <button className="map-btn" onClick={handleClear}>
            🧹 清除
          </button>
        </div>
        {(msg || routeInfo) && (
          <div className="map-msg">
            {routeInfo && <div className="map-route-info">{routeInfo}</div>}
            {msg && <div className="map-msg-text">{msg}</div>}
          </div>
        )}
      </div>

      <div className="map-body">
        <div ref={containerRef} className="map-container" />
        {status === "loading" && <div className="map-overlay">🌐 地图加载中…</div>}
        {status === "error" && <div className="map-overlay map-error">⚠️ {error}</div>}

        {pois.length > 0 && (
          <div className="map-poi-list">
            <div className="map-poi-title">
              <span>搜索结果（{pois.length}）</span>
              <button className="map-poi-close" title="关闭列表（保留地图标记）" onClick={() => setPois([])}>
                ✕
              </button>
            </div>
            {pois.map((p, i) => (
              <div
                key={i}
                className="map-poi-item"
                onClick={() => {
                  mapRef.current?.setCenter(p.location);
                  mapRef.current?.setZoom(15);
                }}
              >
                <span className="map-poi-idx">{i + 1}</span>
                <div className="map-poi-body">
                  <div className="map-poi-name">{p.name}</div>
                  {p.address && <div className="map-poi-addr">{p.address}</div>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
