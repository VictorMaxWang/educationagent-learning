import { useEffect, useMemo, useRef, useState } from "react";
import html2canvas from "html2canvas";
import {
  wardrobeRecognize, outfitPlan, outfitGenerateMulti, outfitManual, outfitRoutes, weatherForCity,
  outfitChatStream, wardrobeTryOn, wardrobeTryOnExplain,
} from "./request";
import { compressImage, dataUrlBytes } from "./imageCompress";
import {
  type WardrobeItem,
  type TravelDay,
  type SpotTryOnData,
  type OutfitRec,
  type SessionTryOnStore,
  type OutfitChatMsg,
  getWardrobe,
  initWardrobe,
  onWardrobeReady,
  addWardrobeItem,
  removeWardrobeItem,
  updateWardrobeItem,
  saveWardrobe,
  getOutfitSessions,
  blankDay,
  getSessionTryOn,
  saveSessionTryOn,
  updateSessionDays,
  setOutfitSessionStatus,
  removeDayTryOn,
} from "./outfitStorage";
import MdRender from "./MdRender";
import DaolvPanel from "./DaolvPanel";
import PhotoGuideModal from "./PhotoGuideModal";
import RestaurantView from "./RestaurantView";

type Props = {
  currentOutfitId: string;
  onSessionsChanged: () => void;
  subview: "plan" | "outfit" | "hotel" | "food";
  onSubviewChange: (k: "plan" | "outfit" | "hotel" | "food") => void;
};

const CATEGORIES = [
  { key: "top", label: "上衣" },
  { key: "bottom", label: "下装" },
  { key: "dress", label: "连衣裙" },
  { key: "coat", label: "外套" },
  { key: "shoes", label: "鞋履" },
  { key: "accessory", label: "配饰" },
];

const EMPTY_TRYON: SpotTryOnData = { selectedItemIds: [] };

/**
 * 👗 出行 — 旅游计划（AI 生成每日行程）+ 穿搭（按天搭配+试穿）。
 * 流程：填城市/天数 → 生成每日行程（可微调）→ 确认全部行程（锁定）→ 按天 Tab 选衣试穿 → 一键生成各天方案 → 汇总完整手册。
 */
export default function MaimemoOutfit({ currentOutfitId, onSessionsChanged, subview, onSubviewChange }: Props) {
  const [wardrobe, setWardrobe] = useState<WardrobeItem[]>(() => getWardrobe());
  const [days, setDays] = useState<TravelDay[]>([]);
  const [status, setStatus] = useState<"editing" | "confirmed">("editing");
  const [activeDayId, setActiveDayId] = useState("");
  const [tryOnStore, setTryOnStore] = useState<SessionTryOnStore>({ days: {} });

  const [collapsedCats, setCollapsedCats] = useState<Record<string, boolean>>({});
  const [uploading, setUploading] = useState(false);
  const [pendingGarments, setPendingGarments] = useState<any[]>([]);
  const [pendingChecked, setPendingChecked] = useState<Record<number, boolean>>({});
  const lastUploadedImgRef = useRef("");
  const [msg, setMsg] = useState("");

  // 旅游计划表单
  const [planForm, setPlanForm] = useState({ city: "", days: 3, style: "舒适", extra: "" });
  const [planning, setPlanning] = useState(false);

  const [generating, setGenerating] = useState(false);
  const [manualLoading, setManualLoading] = useState(false);
  const [manual, setManual] = useState("");
  // 生成失败的 dayId（用于展示可单独重试的失败卡）
  const [failedPlanDayIds, setFailedPlanDayIds] = useState<string[]>([]);

  const [weatherLoading, setWeatherLoading] = useState(false);
  const [tryOnLoading, setTryOnLoading] = useState(false);
  const [tryOnExplain, setTryOnExplain] = useState("");
  // 高德真实路线（按 dayId 存各段公交/驾车）
  const [routesByDay, setRoutesByDay] = useState<Record<string, { from: string; to: string; transit: string; driving: string }[]>>({});
  const [routesLoading, setRoutesLoading] = useState(false);
  // 景点拍照参考弹窗（当前景点名，null 关闭）
  const [photoGuideSpot, setPhotoGuideSpot] = useState<string | null>(null);

  const [followUp, setFollowUp] = useState("");
  const [streaming, setStreaming] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const personInputRef = useRef<HTMLInputElement>(null);
  const defaultPersonInputRef = useRef<HTMLInputElement>(null);
  const chatRef = useRef<HTMLDivElement>(null);
  const exportRef = useRef<HTMLDivElement>(null);
  const lastBotTextRef = useRef("");

  // 衣橱：启动从 IndexedDB 异步加载（图片容量大，不再被 localStorage 5MB 上限清空），加载完刷新
  useEffect(() => {
    const off = onWardrobeReady(() => setWardrobe(getWardrobe()));
    initWardrobe();
    return off;
  }, []);

  // ==================== 会话加载 ====================

  useEffect(() => {
    if (!currentOutfitId) return;
    const s = getOutfitSessions().find((x) => x.sessionId === currentOutfitId);
    if (!s) return;
    setDays(s.days);
    setStatus(s.status);
    setTryOnStore(getSessionTryOn(currentOutfitId));
    setActiveDayId(s.days.length ? s.days[0].dayId : "");
    setManual("");
    setTryOnExplain("");
    setFollowUp("");
    setFailedPlanDayIds([]);
    setRoutesByDay({});
    setPlanForm((p) => ({
      city: s.days[0]?.city || p.city,
      days: Math.max(1, s.days.length),
      style: s.days[0]?.style || p.style,
      extra: p.extra,
    }));
  }, [currentOutfitId]);

  // ==================== 派生状态 ====================

  /** 重新读取当前会话的 days（RestaurantView 加入餐馆后调用，刷新计划视图） */
  const loadDays = () => {
    const s = getOutfitSessions().find((x) => x.sessionId === currentOutfitId);
    if (s) setDays(s.days);
  };

  const activeDay = days.find((d) => d.dayId === activeDayId);
  const activeDayIndex = days.findIndex((d) => d.dayId === activeDayId);
  const activeTryOn: SpotTryOnData = activeDayId ? tryOnStore.days[activeDayId] || EMPTY_TRYON : EMPTY_TRYON;
  const activePersonImg = activeTryOn.personImg || tryOnStore.defaultPersonImg || "";
  const currentTitle = getOutfitSessions().find((s) => s.sessionId === currentOutfitId)?.title || "出行";
  const msgs = activeTryOn.chat || [];

  // 切换天 → 清空全局试穿说明
  useEffect(() => {
    setTryOnExplain("");
  }, [activeDayId]);

  // 追问聊天自动滚动
  useEffect(() => {
    if (chatRef.current) {
      chatRef.current.scrollTo({ top: chatRef.current.scrollHeight, behavior: "smooth" });
    }
  }, [msgs]);

  // ==================== tryOnStore 持久化 ====================

  const persistTryOnStore = (next: SessionTryOnStore) => {
    if (currentOutfitId) saveSessionTryOn(currentOutfitId, next);
  };

  /** 更新当前天数据（功能式 setState，避免流式追问闭包覆盖） */
  const updateActiveDayData = (patch: Partial<SpotTryOnData>) => {
    if (!activeDayId) return;
    setTryOnStore((prev) => {
      const next: SessionTryOnStore = {
        ...prev,
        days: {
          ...prev.days,
          [activeDayId]: { ...(prev.days[activeDayId] || EMPTY_TRYON), ...patch },
        },
      };
      persistTryOnStore(next);
      return next;
    });
  };

  /** 写某天试穿结果（resultKey 存在时同时记录到该方案的 outfitResults） */
  const setDayTryOnResult = (dayId: string, image: string, resultKey?: string) => {
    setTryOnStore((prev) => {
      const base = prev.days[dayId] || EMPTY_TRYON;
      const day = resultKey
        ? { ...base, resultImg: image, outfitResults: { ...(base.outfitResults || {}), [resultKey]: image } }
        : { ...base, resultImg: image };
      const next: SessionTryOnStore = {
        ...prev,
        days: { ...prev.days, [dayId]: day },
      };
      persistTryOnStore(next);
      return next;
    });
  };

  const dayToTravelInfo = (day?: TravelDay) => ({
    city: day?.city || "",
    spots: (day?.spots || []).join("/"),
    days: 1,
    temp: day?.temp || "",
    rain: !!day?.rain,
    style: day?.style || "舒适",
    useWardrobe: true,
  });

  const dayTabLabel = (d: TravelDay): string => {
    const spotsStr = (d.spots || []).join("/");
    const label = `第${d.day}天`;
    if (spotsStr) return `${label} · ${spotsStr.length > 12 ? spotsStr.slice(0, 12) + "…" : spotsStr}`;
    return d.city ? `${label} · ${d.city}` : label;
  };

  // ==================== ① 旅游计划 ====================

  const updateDay = (dayId: string, patch: Partial<TravelDay>) => {
    const old = days.find((d) => d.dayId === dayId);
    const next = days.map((d) => (d.dayId === dayId ? { ...d, ...patch } : d));
    setDays(next);
    if (currentOutfitId) {
      updateSessionDays(currentOutfitId, next);
      onSessionsChanged();
    }
    // 城市/气温/降雨/风格/天数变更 → 该天 AI 方案失效（景点变更不重置方案与温度）
    if (old && tryOnStore.days[dayId]?.plan) {
      const infoChanged =
        (patch.city !== undefined && old.city !== patch.city) ||
        (patch.temp !== undefined && old.temp !== patch.temp) ||
        (patch.rain !== undefined && old.rain !== patch.rain) ||
        (patch.style !== undefined && old.style !== patch.style) ||
        (patch.day !== undefined && old.day !== patch.day);
      if (infoChanged) {
        const nextStore: SessionTryOnStore = {
          ...tryOnStore,
          days: {
            ...tryOnStore.days,
            [dayId]: { ...(tryOnStore.days[dayId] || EMPTY_TRYON), plan: "", recommendedTopItemId: "", recommendedBottomItemId: "" },
          },
        };
        persistTryOnStore(nextStore);
        setTryOnStore(nextStore);
      }
    }
  };

  /** 景点列表编辑（顿号/逗号分隔，不重置气温/降雨/风格） */
  const updateDaySpots = (dayId: string, text: string) => {
    const spots = text.split(/[、，,／\s]+/).map((s) => s.trim()).filter(Boolean);
    updateDay(dayId, { spots });
  };

  const onCityBlur = async (day: TravelDay) => {
    const city = (day.city || "").trim();
    if (!city) return;
    setWeatherLoading(true);
    try {
      const res = await weatherForCity(city);
      if (res?.code === 200 && res.temp != null) {
        updateDay(day.dayId, { temp: String(res.temp) });
        setMsg(`📍 ${city} 当前 ${res.temp}℃${res.condition ? " · " + res.condition : ""}`);
      } else {
        setMsg(res?.msg || `未获取到 ${city} 的天气，请手动填写温度`);
      }
    } catch (e) {
      setMsg(`天气获取失败：${e}`);
    }
    setWeatherLoading(false);
  };

  const fallbackBlankDays = (form: typeof planForm) => {
    const n = Math.max(1, form.days || 1);
    const mapped: TravelDay[] = Array.from({ length: n }, (_, i) => ({
      dayId: `${currentOutfitId}-day-${Date.now().toString(36)}-fb${i}`,
      day: i + 1,
      city: form.city.trim(),
      spots: [],
      temp: "",
      rain: false,
      style: form.style,
    }));
    setDays(mapped);
    if (currentOutfitId) {
      updateSessionDays(currentOutfitId, mapped);
      onSessionsChanged();
    }
    setStatus("editing");
    setActiveDayId(mapped[0]?.dayId || "");
  };

  const generateItinerary = async () => {
    if (!planForm.city.trim()) {
      setMsg("请先填写目的地城市");
      return;
    }
    if (!currentOutfitId) return;
    setPlanning(true);
    setMsg("🗺️ 正在生成每日行程…");
    try {
      const res = await outfitPlan(planForm.city.trim(), planForm.days || 1, planForm.style, planForm.extra);
      if (res?.code === 200 && Array.isArray(res.itinerary) && res.itinerary.length) {
        const mapped: TravelDay[] = res.itinerary.map((it: any, i: number) => ({
          dayId: `${currentOutfitId}-day-${Date.now().toString(36)}-${i}`,
          day: it.day || i + 1,
          city: it.city || planForm.city.trim(),
          spots: Array.isArray(it.spots) ? it.spots.map(String) : [],
          route: typeof it.route === "string" ? it.route : "",
          temp: "",
          rain: false,
          style: planForm.style,
        }));
        setDays(mapped);
        if (currentOutfitId) {
          updateSessionDays(currentOutfitId, mapped);
          onSessionsChanged();
        }
        setStatus("editing");
        setActiveDayId(mapped[0]?.dayId || "");
        setMsg(`✅ 已生成 ${mapped.length} 天行程，可手动微调`);
      } else {
        // 解析失败 → 空白天数卡片，保留城市/天数，无需重填
        fallbackBlankDays(planForm);
        setMsg(res?.msg || "行程生成失败，已生成空白天数卡片，请手动填写景点");
      }
    } catch (e) {
      fallbackBlankDays(planForm);
      setMsg(`行程生成失败：${e}`);
    }
    setPlanning(false);
  };

  /** 查询某天景点间真实交通路线（高德） */
  const queryDayRoutes = async (dayId: string) => {
    const day = days.find((d) => d.dayId === dayId);
    if (!day || !(day.spots || []).length) return;
    const legs = (day.spots || []).slice(0, -1).map((from, i) => ({ from, to: (day.spots || [])[i + 1] }));
    if (!legs.length) {
      setMsg("当天只有一个景点，无需查询路线");
      return;
    }
    setRoutesLoading(true);
    setMsg("🗺️ 正在查询高德真实路线…");
    try {
      const res = await outfitRoutes(day.city, legs);
      if (res?.code === 200 && Array.isArray(res.routes)) {
        setRoutesByDay((prev) => ({ ...prev, [dayId]: res.routes }));
        setMsg(`✅ 已获取第${day.day}天 ${res.routes.length} 段交通路线`);
      } else {
        setMsg(res?.msg || "路线查询失败");
      }
    } catch (e) {
      setMsg(`路线查询失败：${e}`);
    }
    setRoutesLoading(false);
  };

  const addDay = () => {
    if (!currentOutfitId) return;
    const newDay = blankDay(currentOutfitId, days.length + 1);
    const next = [...days, newDay];
    setDays(next);
    updateSessionDays(currentOutfitId, next);
    onSessionsChanged();
  };

  const removeDay = (dayId: string) => {
    if (!currentOutfitId) return;
    const next = days.filter((d) => d.dayId !== dayId);
    setDays(next);
    updateSessionDays(currentOutfitId, next);
    removeDayTryOn(currentOutfitId, dayId); // 同步清理该天全部试穿数据
    onSessionsChanged();
    if (activeDayId === dayId) {
      setActiveDayId(next.length ? next[0].dayId : "");
    }
  };

  const confirmItinerary = () => {
    if (days.some((d) => !d.city.trim() || !(d.spots || []).length)) {
      setMsg("请先补全每天的城市和景点");
      return;
    }
    if (!currentOutfitId) return;
    setStatus("confirmed");
    setOutfitSessionStatus(currentOutfitId, "confirmed");
    onSessionsChanged();
    if (!activeDayId && days.length) setActiveDayId(days[0].dayId);
    setMsg("✅ 行程已确认，进入按天穿搭试穿");
  };

  const unlockItinerary = () => {
    if (!currentOutfitId) return;
    setStatus("editing");
    setOutfitSessionStatus(currentOutfitId, "editing");
    onSessionsChanged();
    setMsg("行程已解锁编辑，改动后需重新确认");
  };

  /** 天状态标记 */
  const dayStatus = (dayId: string): string => {
    const d = tryOnStore.days[dayId];
    if (!d) return "未规划";
    if (d.resultImg) return "试穿完成";
    if (d.plan || (d.selectedItemIds && d.selectedItemIds.length)) return "穿搭匹配中";
    return "未规划";
  };
  const statusCls = (s: string) => (s === "穿搭匹配中" ? "ofit-day-status-mid" : s === "试穿完成" ? "ofit-day-status-done" : "");

  // ==================== 衣橱上传 ====================

  const buildItem = (g: any, img: string): WardrobeItem => ({
    itemId: Date.now().toString() + Math.random().toString(36).slice(2, 6),
    imgBase64: img,
    category: g.category || "top",
    subCategory: g.subCategory || "",
    color: g.color || "",
    fabric: g.fabric || "",
    season: Array.isArray(g.season) ? g.season : [],
    style: Array.isArray(g.style) ? g.style : [],
    createTime: Date.now(),
  });

  const addGarment = (g: any, img: string) => {
    setWardrobe(addWardrobeItem(buildItem(g, img)));
  };

  const onFile = (file: File) => {
    if (!file.type.startsWith("image/")) {
      setMsg("请上传图片文件");
      return;
    }
    const reader = new FileReader();
    reader.onload = async () => {
      const dataUrl = reader.result as string;
      setUploading(true);
      setMsg("🔍 识别衣物中…");
      try {
        // 统一压缩：长边 ≤1280px / JPG 质量 0.7 / 单张 ≤600KB，禁止原图直传
        const compressed = await compressImage(dataUrl);
        const res = await wardrobeRecognize(compressed);
        if (res?.code === 200 && res.data?.garments?.length) {
          lastUploadedImgRef.current = compressed;
          const gs = res.data.garments;
          if (gs.length === 1) {
            addGarment(gs[0], compressed);
            setMsg(`✅ 已识别 ${gs[0].subCategory || gs[0].category}（${gs[0].color || ""}）`);
          } else {
            setPendingGarments(gs);
            setPendingChecked(gs.reduce((acc: Record<number, boolean>, g: any, i: number) => ({ ...acc, [i]: !(g.category === "shoes" || g.category === "accessory") }), {}));
            setMsg(`识别到 ${gs.length} 件衣物，勾选要加入衣橱的（鞋/配饰默认不选）`);
          }
        } else {
          setMsg(res?.msg || "未能识别衣物");
        }
      } catch (e) {
        setMsg(`识别失败：${e}`);
      }
      setUploading(false);
    };
    reader.readAsDataURL(file);
  };

  const confirmPending = () => {
    const toAdd = pendingGarments.filter((_, i) => pendingChecked[i]);
    if (!toAdd.length) {
      setMsg("未勾选任何衣物");
      return;
    }
    // 一次性批量构建所有勾选项并写入，避免防抖写 localStorage 导致逐个加互相覆盖
    const newItems = toAdd.map((g) => buildItem(g, lastUploadedImgRef.current));
    setWardrobe((prev) => {
      const next = [...newItems, ...prev];
      saveWardrobe(next);
      return next;
    });
    setPendingGarments([]);
    setMsg(`✅ 已加入 ${newItems.length} 件衣物到衣橱`);
  };

  const categoryLabel = (key: string) => CATEGORIES.find((c) => c.key === key)?.label || key;

  const deleteItem = (itemId: string) => {
    setWardrobe(removeWardrobeItem(itemId));
  };

  const changeItemCategory = (itemId: string, category: string) => {
    setWardrobe(updateWardrobeItem(itemId, { category }));
  };

  const toggleCat = (key: string) => {
    setCollapsedCats((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const wardrobeStats = CATEGORIES
    .map((c) => ({ ...c, count: wardrobe.filter((i) => i.category === c.key).length }))
    .filter((s) => s.count > 0);

  const isItemUsed = (item: WardrobeItem): boolean => {
    const keywords = [item.subCategory, item.color, item.fabric].filter((k) => k && k.length >= 2);
    if (!keywords.length) return false;
    return days.some((d) => {
      const plan = tryOnStore.days[d.dayId]?.plan || "";
      return keywords.some((k) => plan.includes(k));
    });
  };
  const idleItems = wardrobe.filter((i) => !isItemUsed(i));

  // ==================== ③ 按天穿搭试穿 ====================

  const onDefaultPersonFile = (file: File) => {
    const reader = new FileReader();
    reader.onload = async () => {
      const dataUrl = reader.result as string;
      let compressed = dataUrl;
      try {
        compressed = await compressImage(dataUrl);
      } catch { /* 兜底原图 */ }
      setTryOnStore((prev) => {
        const next = { ...prev, defaultPersonImg: compressed };
        persistTryOnStore(next);
        return next;
      });
      setMsg("✅ 已设置默认全身人像（各天默认复用，可单独更换）");
    };
    reader.readAsDataURL(file);
  };

  const onDayPersonFile = (file: File) => {
    if (!activeDayId) return;
    const reader = new FileReader();
    reader.onload = async () => {
      const dataUrl = reader.result as string;
      let compressed = dataUrl;
      try {
        compressed = await compressImage(dataUrl);
      } catch { /* 兜底原图 */ }
      updateActiveDayData({ personImg: compressed });
      setMsg("✅ 已上传本天全身人像");
    };
    reader.readAsDataURL(file);
  };

  const toggleTryOnItem = (item: WardrobeItem) => {
    if (!activeDayId) return;
    const current = activeTryOn.selectedItemIds || [];
    if (current.includes(item.itemId)) {
      updateActiveDayData({ selectedItemIds: current.filter((id) => id !== item.itemId) });
      return;
    }
    const isTop = item.category === "top" || item.category === "dress" || item.category === "coat";
    const isBottom = item.category === "bottom";
    if (!isTop && !isBottom) {
      setMsg("鞋履/配饰暂不支持试穿，请选上衣/下装/连衣裙/外套");
      return;
    }
    const hasTop = current.some((id) => {
      const it = wardrobe.find((i) => i.itemId === id);
      return !!it && (it.category === "top" || it.category === "dress" || it.category === "coat");
    });
    const hasBottom = current.some((id) => {
      const it = wardrobe.find((i) => i.itemId === id);
      return !!it && it.category === "bottom";
    });
    if (isTop && hasTop) { setMsg("已选一件上衣/连衣裙/外套，先取消再换另一件"); return; }
    if (isBottom && hasBottom) { setMsg("已选一件下装，先取消再换另一件"); return; }
    if (current.length >= 2) { setMsg("最多同时选 1 件上衣 + 1 件下装"); return; }
    updateActiveDayData({ selectedItemIds: [...current, item.itemId] });
  };

  /** 实际执行试穿（显式入参，避免 setState 闭包竞态）；resultKey 指定时结果同时存到该方案 */
  const runTryOn = async (dayId: string, personImg: string, selected: WardrobeItem[], day?: TravelDay, resultKey?: string) => {
    if (selected.some((i) => !i.imgBase64)) {
      setMsg("所选衣物缺少图片，无法试穿（图片可能因存储超限被降级）");
      return;
    }
    const topItems = selected.filter((i) => i.category === "top" || i.category === "dress" || i.category === "coat");
    const bottomItems = selected.filter((i) => i.category === "bottom");
    if (topItems.length > 1 || bottomItems.length > 1) {
      setMsg("上衣/下装各选一件即可");
      return;
    }
    const bothTopBottom = topItems.length > 0 && bottomItems.length > 0;
    if (bothTopBottom && (dataUrlBytes(personImg) > 800 * 1024 || selected.some((i) => dataUrlBytes(i.imgBase64) > 800 * 1024))) {
      setMsg("⚠️ 整套试穿图片过大（>800KB）：请重新上传小于 800KB 的清晰图片（衣橱/人像上传会自动压缩）");
      return;
    }

    const garments = selected.map((i) => ({ image: i.imgBase64, category: i.category }));
    const outfit = selected
      .map((i) => [i.subCategory, i.color, i.fabric, (i.style || []).join("/")].filter((s) => s && s.trim()).join("，"))
      .filter((s) => s)
      .join("；");

    setTryOnLoading(true);
    setTryOnExplain("");
    setMsg("👗 试穿生成中（aitryon-plus，约 30-90 秒）…");
    try {
      const res = await wardrobeTryOn(personImg, garments);
      if (res?.code === 200 && res.image) {
        setDayTryOnResult(dayId, res.image, resultKey);
        setMsg("✅ 试穿效果已生成，正在生成穿搭说明…");
        const ex = await wardrobeTryOnExplain(dayToTravelInfo(day), outfit);
        setTryOnExplain(ex?.code === 200 && ex.text ? ex.text : "");
        setMsg("✅ 试穿效果与说明已生成");
      } else {
        setMsg(res?.msg || "试穿生成失败");
      }
    } catch (e) {
      setMsg(`试穿失败：${e}`);
    }
    setTryOnLoading(false);
  };

  const doTryOn = () => {
    if (!activeDayId) return;
    const data = tryOnStore.days[activeDayId] || EMPTY_TRYON;
    const personImg = data.personImg || tryOnStore.defaultPersonImg || "";
    if (!personImg) {
      setMsg("请先上传全身人像（本天或默认人像）");
      return;
    }
    const selected = wardrobe.filter((i) => (data.selectedItemIds || []).includes(i.itemId));
    if (!selected.length) {
      setMsg("请至少选择一件衣物进行试穿");
      return;
    }
    runTryOn(activeDayId, personImg, selected, activeDay);
  };

  /** 复制前一天穿搭（已选衣物 + 人像） */
  const copyPreviousDay = () => {
    if (!activeDayId) return;
    const idx = days.findIndex((d) => d.dayId === activeDayId);
    if (idx <= 0) {
      setMsg("第 1 天没有前一天可复制");
      return;
    }
    const prev = tryOnStore.days[days[idx - 1].dayId];
    if (!prev || (!(prev.selectedItemIds || []).length && !prev.personImg)) {
      setMsg("前一天还没有可复制的穿搭/人像");
      return;
    }
    updateActiveDayData({
      selectedItemIds: [...(prev.selectedItemIds || [])],
      personImg: prev.personImg || "",
    });
    setMsg("✅ 已复制前一天的穿搭与人像");
  };

  /** 方案卡「去试穿」：切天 + 应用推荐 + 自动生成 */
  const gotoDayTryOn = (dayId: string) => {
    const data = tryOnStore.days[dayId] || EMPTY_TRYON;
    let selIds = data.selectedItemIds || [];
    if (!selIds.length && (data.recommendedTopItemId || data.recommendedBottomItemId)) {
      selIds = [data.recommendedTopItemId, data.recommendedBottomItemId].filter((x): x is string => !!x);
    }
    const personImg = data.personImg || tryOnStore.defaultPersonImg || "";
    setActiveDayId(dayId);
    if (selIds.join(",") !== (data.selectedItemIds || []).join(",")) {
      setTryOnStore((prev) => {
        const next = { ...prev, days: { ...prev.days, [dayId]: { ...(prev.days[dayId] || EMPTY_TRYON), selectedItemIds: selIds } } };
        persistTryOnStore(next);
        return next;
      });
    }
    if (!personImg) {
      setMsg("请先上传该天全身人像（或设置默认人像）");
      return;
    }
    const selected = wardrobe.filter((i) => selIds.includes(i.itemId));
    if (!selected.length) {
      setMsg("推荐/已选衣物不完整，请先挑选上衣/下装");
      return;
    }
    const day = days.find((d) => d.dayId === dayId);
    runTryOn(dayId, personImg, selected, day);
  };

  /** 按指定上衣+下装试穿；resultKey 用于把结果存到对应方案 */
  const tryOnItems = (dayId: string, topId: string, bottomId: string, resultKey: string, label: string) => {
    const data = tryOnStore.days[dayId] || EMPTY_TRYON;
    const selIds = [topId, bottomId].filter((x): x is string => !!x);
    const personImg = data.personImg || tryOnStore.defaultPersonImg || "";
    setActiveDayId(dayId);
    if (selIds.join(",") !== (data.selectedItemIds || []).join(",")) {
      setTryOnStore((prev) => {
        const next = { ...prev, days: { ...prev.days, [dayId]: { ...(prev.days[dayId] || EMPTY_TRYON), selectedItemIds: selIds } } };
        persistTryOnStore(next);
        return next;
      });
    }
    if (!personImg) {
      setMsg("请先上传该天全身人像（或设置默认人像）");
      return;
    }
    const selected = wardrobe.filter((i) => selIds.includes(i.itemId));
    if (!selected.length) {
      setMsg(`${label || "该方案"}的衣物不完整，请先挑选上衣/下装`);
      return;
    }
    const day = days.find((d) => d.dayId === dayId);
    runTryOn(dayId, personImg, selected, day, resultKey);
  };

  /** 按某套推荐方案（方案一/二/三）单独试穿 */
  const tryOutfit = (dayId: string, outfit: OutfitRec) => {
    tryOnItems(dayId, outfit.topItemId, outfit.bottomItemId, outfit.label, outfit.label);
  };

  // ==================== ④ AI 方案 + 手册 ====================

  const generatePlans = async () => {
    if (!days.length) return;
    setGenerating(true);
    setMsg("🤖 正在按天生成穿搭方案…");
    try {
      const res = await outfitGenerateMulti(days, wardrobe);
      if (res?.code === 200 && Array.isArray(res.plans)) {
        setTryOnStore((prev) => {
          const next = { ...prev, days: { ...prev.days } };
          for (const p of res.plans) {
            if (!p.dayId) continue;
            const base = next.days[p.dayId] || EMPTY_TRYON;
            next.days[p.dayId] = {
              ...base,
              plan: p.plan || "",
              recommendedTopItemId: p.recommendedTopItemId || "",
              recommendedBottomItemId: p.recommendedBottomItemId || "",
              outfits: Array.isArray(p.outfits) ? p.outfits : [],
            };
          }
          persistTryOnStore(next);
          return next;
        });
        const failed = res.plans.filter((p: any) => !p.plan).length;
        setFailedPlanDayIds(res.plans.filter((p: any) => !p.plan).map((p: any) => p.dayId).filter(Boolean));
        setMsg(failed
          ? `⚠️ 已生成 ${res.plans.length - failed}/${res.plans.length} 天方案，${failed} 天失败可单独重试`
          : `✅ 各天方案已生成（${res.plans.length} 天）`);
      } else {
        setMsg(res?.msg || "生成失败");
      }
    } catch (e) {
      setMsg(`生成失败：${e}`);
    }
    setGenerating(false);
  };

  /** 单独重试某一天方案（不重跑其他天） */
  const retryDay = async (dayId: string) => {
    const day = days.find((d) => d.dayId === dayId);
    if (!day) return;
    setGenerating(true);
    setMsg(`🔄 正在重新生成第${day.day}天方案…`);
    try {
      const res = await outfitGenerateMulti([day], wardrobe);
      if (res?.code === 200 && Array.isArray(res.plans) && res.plans.length) {
        const p = res.plans[0];
        if (p.dayId) {
          setTryOnStore((prev) => {
            const base = prev.days[p.dayId] || EMPTY_TRYON;
            const next = { ...prev, days: { ...prev.days, [p.dayId]: { ...base, plan: p.plan || "", recommendedTopItemId: p.recommendedTopItemId || "", recommendedBottomItemId: p.recommendedBottomItemId || "", outfits: Array.isArray(p.outfits) ? p.outfits : [] } } };
            persistTryOnStore(next);
            return next;
          });
        }
        setMsg(p.plan ? `✅ 第${day.day}天方案已重新生成` : "该天仍生成失败，请稍后再试");
        if (p.plan) {
          setFailedPlanDayIds((prev) => prev.filter((id) => id !== p.dayId));
        }
      } else {
        setMsg(res?.msg || "重新生成失败");
      }
    } catch (e) {
      setMsg(`重新生成失败：${e}`);
    }
    setGenerating(false);
  };

  const manualGenerate = async () => {
    if (!days.length) return;
    const outfits = days.map((d) => {
      const data = tryOnStore.days[d.dayId];
      const sel = (data?.selectedItemIds || [])
        .map((id) => wardrobe.find((i) => i.itemId === id))
        .filter((x): x is WardrobeItem => !!x);
      const desc = sel
        .map((i) => [i.subCategory, i.color, i.fabric, (i.style || []).join("/")].filter((x) => x && x.trim()).join("，"))
        .filter((x) => x)
        .join("；");
      return { dayId: d.dayId, outfit: desc };
    });
    setManualLoading(true);
    setMsg("📒 正在生成完整旅游穿搭手册…");
    try {
      const res = await outfitManual(days, outfits);
      if (res?.code === 200 && res.manual) {
        setManual(res.manual);
        setMsg("✅ 完整穿搭手册已生成");
      } else {
        setMsg(res?.msg || "手册生成失败");
      }
    } catch (e) {
      setMsg(`手册生成失败：${e}`);
    }
    setManualLoading(false);
  };

  // ==================== 追问调整（绑定当前天） ====================

  const sendFollowUp = () => {
    const q = followUp.trim();
    if (!q || streaming) return;
    if (!activeDayId) { setMsg("请先选择一天"); return; }
    const cur = activeTryOn.chat || [];
    if (!cur.length && !activeTryOn.plan) {
      setMsg("请先为该天生成穿搭方案，再追问调整");
      return;
    }
    const now = Date.now();
    const userMsg: OutfitChatMsg = { id: now, role: "user", text: q, createTime: now };
    const botMsg: OutfitChatMsg = { id: now + 1, role: "bot", text: "", createTime: now + 1 };
    updateActiveDayData({ chat: [...cur, userMsg, botMsg] });
    setFollowUp("");
    setStreaming(true);

    const history = [...cur, userMsg].slice(-6).map((m) => ({
      role: m.role === "user" ? "user" : "assistant",
      text: m.text,
    }));
    outfitChatStream(
      dayToTravelInfo(activeDay),
      wardrobe,
      history,
      q,
      (text) => {
        lastBotTextRef.current = text;
        if (!activeDayId) return;
        setTryOnStore((prev) => {
          const base = prev.days[activeDayId] || EMPTY_TRYON;
          const chat = (base.chat || []).map((m) => (m.id === botMsg.id ? { ...m, text } : m));
          const next = { ...prev, days: { ...prev.days, [activeDayId]: { ...base, chat } } };
          persistTryOnStore(next);
          return next;
        });
      },
      () => {
        setStreaming(false);
        if (!activeDayId) return;
        setTryOnStore((prev) => {
          const base = prev.days[activeDayId] || EMPTY_TRYON;
          const chat = (base.chat || []).map((m) =>
            m.id === botMsg.id && !m.text ? { ...m, text: lastBotTextRef.current || "（无回复）" } : m,
          );
          const next = { ...prev, days: { ...prev.days, [activeDayId]: { ...base, chat } } };
          persistTryOnStore(next);
          return next;
        });
      },
    );
  };

  // ==================== 导出 ====================

  const combinedPlanText = (): string => {
    if (manual) return manual;
    const parts = days
      .map((d) => {
        const data = tryOnStore.days[d.dayId];
        if (!data?.plan) return "";
        return `# 第${d.day}天 · ${d.city}\n\n${data.plan}`;
      })
      .filter((x) => x);
    return parts.join("\n\n---\n\n");
  };

  const exportPlan = () => {
    const text = combinedPlanText();
    if (!text) {
      setMsg("还没有可导出的穿搭方案，请先生成方案或手册");
      return;
    }
    const blob = new Blob([`# ${currentTitle}\n\n${text}`], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${currentTitle.replace(/[\\/:*?"<>|]/g, "_")}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 2000);
    setMsg("✅ 穿搭方案已导出为 Markdown 文件");
  };

  const exportImage = async () => {
    const el = exportRef.current;
    if (!el) return;
    try {
      const canvas = await html2canvas(el, { backgroundColor: "#ffffff", scale: 2 });
      const url = canvas.toDataURL("image/png");
      const a = document.createElement("a");
      a.href = url;
      a.download = `${currentTitle.replace(/[\\/:*?"<>|]/g, "_")}.png`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setMsg("✅ 穿搭方案已导出为图片");
    } catch (e) {
      setMsg(`导出图片失败：${e}`);
    }
  };

  // ==================== 智能推荐 ====================

  const rec = useMemo(() => recommendWardrobeForDay(activeDay, wardrobe), [activeDay, wardrobe]);

  const applyRecommendation = () => {
    if (!activeDayId) return;
    const ids = [rec.topItemId, rec.bottomItemId].filter((x) => !!x);
    if (!ids.length) {
      setMsg("没有可推荐的适配单品（请先上传衣物，或调整该天气温/降雨/风格）");
      return;
    }
    updateActiveDayData({ selectedItemIds: ids });
    setMsg("✅ 已应用推荐穿搭（上衣 + 下装）");
  };

  return (
    <div className="mg-panel">
      <div className="mg-header">
        <h2>👗 出行</h2>
        <span className="mg-sub">{currentTitle} · {subview === "plan" ? "旅游计划" : subview === "outfit" ? "按天穿搭" : subview === "hotel" ? "酒店查询" : "周边餐馆查询"}</span>
      </div>

      <div className="ofit-subview-tabs">
        <button className={`ofit-subview-tab ${subview === "plan" ? "ofit-subview-tab-active" : ""}`} onClick={() => onSubviewChange("plan")}>旅游计划</button>
        <button className={`ofit-subview-tab ${subview === "outfit" ? "ofit-subview-tab-active" : ""}`} onClick={() => onSubviewChange("outfit")}>穿搭</button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {/* ========== 子模块一：旅游计划 ========== */}
      {subview === "plan" && (
        <>
      <div className="ofit-section">
        <div className="ofit-section-title">旅游计划 {status === "confirmed" ? "（已确认）" : "（编辑中）"}</div>

        {status === "editing" ? (
          <>
            <div className="ofit-plan-form">
              <div className="ofit-form">
                <label className="ofit-field">
                  <span className="ofit-label">目的地城市</span>
                  <input className="ofit-input" value={planForm.city}
                    onChange={(e) => setPlanForm((p) => ({ ...p, city: e.target.value }))} placeholder="如：南京" />
                </label>
                <label className="ofit-field">
                  <span className="ofit-label">出行天数</span>
                  <input className="ofit-input" type="number" min={1} max={30} value={planForm.days}
                    onChange={(e) => setPlanForm((p) => ({ ...p, days: parseInt(e.target.value) || 1 }))} />
                </label>
                <label className="ofit-field">
                  <span className="ofit-label">出行风格</span>
                  <select className="ofit-input" value={planForm.style}
                    onChange={(e) => setPlanForm((p) => ({ ...p, style: e.target.value }))}>
                    <option>舒适</option><option>出片拍照</option><option>简约 citywalk</option>
                  </select>
                </label>
                <label className="ofit-field ofit-field-wide">
                  <span className="ofit-label">偏好备注（可选）</span>
                  <input className="ofit-input" value={planForm.extra}
                    onChange={(e) => setPlanForm((p) => ({ ...p, extra: e.target.value }))} placeholder="如：亲子 / 不爬山 / 喜欢博物馆" />
                </label>
              </div>
              <div className="mg-actions">
                <button className="btn send-btn" disabled={planning} onClick={generateItinerary}>
                  {planning ? "生成中…" : "🗺️ 生成每日行程"}
                </button>
                <span className="ofit-hint">按城市 + 天数生成每天景点列表，生成后仍可手动微调</span>
              </div>
            </div>

            {days.map((d) => {
              const st = dayStatus(d.dayId);
              return (
                <div className="ofit-day-card" key={d.dayId}>
                  <div className="ofit-day-card-head">
                    <span className="ofit-day-index">第 {d.day} 天</span>
                    <span className={`ofit-day-status ${statusCls(st)}`}>{st}</span>
                  </div>
                  <div className="ofit-form">
                    <label className="ofit-field">
                      <span className="ofit-label">目的地城市</span>
                      <input className="ofit-input" value={d.city}
                        onChange={(e) => updateDay(d.dayId, { city: e.target.value })}
                        onBlur={() => onCityBlur(d)} placeholder="如：南京" />
                    </label>
                    <label className="ofit-field ofit-field-wide">
                      <span className="ofit-label">当日景点（顿号/逗号分隔）</span>
                      <input className="ofit-input" value={(d.spots || []).join("，")}
                        onChange={(e) => updateDaySpots(d.dayId, e.target.value)} placeholder="如：音乐台，老门东，梧桐大道" />
                    </label>
                    <label className="ofit-field">
                      <span className="ofit-label">当日气温(℃)</span>
                      <input className="ofit-input" value={d.temp}
                        onChange={(e) => updateDay(d.dayId, { temp: e.target.value })} placeholder="输入城市自动获取" />
                    </label>
                    <label className="ofit-field ofit-check">
                      <input type="checkbox" checked={d.rain} onChange={(e) => updateDay(d.dayId, { rain: e.target.checked })} />
                      <span>有降雨</span>
                    </label>
                    <label className="ofit-field">
                      <span className="ofit-label">出行风格</span>
                      <select className="ofit-input" value={d.style} onChange={(e) => updateDay(d.dayId, { style: e.target.value })}>
                        <option>舒适</option><option>出片拍照</option><option>简约 citywalk</option>
                      </select>
                    </label>
                  </div>
                  {(d.spots || []).length > 0 && (
                    <div className="ofit-spot-chips">
                      {(d.spots || []).map((spot) => (
                        <span className="ofit-spot-chip" key={spot}>
                          {spot}
                          <button className="btn tool-btn ofit-photo-btn" type="button" onClick={() => setPhotoGuideSpot(spot)}>
                            📷 拍照参考
                          </button>
                        </span>
                      ))}
                    </div>
                  )}
                  {weatherLoading && <div className="ofit-hint">拉取天气中…</div>}
                  {d.route && <div className="ofit-hint">💡 交通建议：{d.route}</div>}
                  <div className="ofit-day-card-actions">
                    <button className="btn tool-btn" onClick={() => removeDay(d.dayId)}>删除</button>
                  </div>
                </div>
              );
            })}

            <div className="mg-actions">
              <button className="btn tool-btn" onClick={addDay}>＋ 添加一天</button>
            </div>
            <div className="mg-actions">
              <button
                className="btn send-btn"
                disabled={days.some((d) => !d.city.trim() || !(d.spots || []).length)}
                onClick={confirmItinerary}
              >
                确认全部行程
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="ofit-confirmed-summary">
              {days.map((d) => {
                const st = dayStatus(d.dayId);
                return (
                  <div className="ofit-confirmed-row" key={d.dayId}>
                    <span className="ofit-confirmed-idx">第{d.day}天</span>
                    <span className="ofit-confirmed-name">{(d.spots || []).join("/") || d.city}</span>
                    <span className="ofit-confirmed-info">
                      {d.city}{d.temp ? " · " + d.temp + "℃" : ""}{d.rain ? " · 有雨" : ""} · {d.style}
                    </span>
                    <span className={`ofit-day-status ${statusCls(st)}`}>{st}</span>
                  </div>
                );
              })}
            </div>

            {/* 交通路线（AI 建议 + 高德真实路线） */}
            <div className="ofit-routes">
              <div className="ofit-section-title">交通路线</div>
              {days.map((d) => {
                const legs = routesByDay[d.dayId];
                return (
                  <div className="ofit-route-day" key={d.dayId}>
                    <div className="ofit-route-day-head">
                      <span className="ofit-route-day-title">第{d.day}天 · {(d.spots || []).join("/")}</span>
                      <button className="btn tool-btn" disabled={routesLoading} onClick={() => queryDayRoutes(d.dayId)}>
                        {routesLoading ? "查询中…" : "查询真实路线"}
                      </button>
                    </div>
                    {d.route && <div className="ofit-route-ai">💡 AI 建议：{d.route}</div>}
                    {legs && legs.length > 0 && (
                      <div className="ofit-route-legs">
                        {legs.map((leg, i) => (
                          <div className="ofit-route-leg" key={i}>
                            <div className="ofit-route-leg-title">{leg.from} → {leg.to}</div>
                            <div className="ofit-route-leg-body">{leg.transit}</div>
                            <div className="ofit-route-leg-body">{leg.driving}</div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            <div className="mg-actions">
              <button className="btn tool-btn" onClick={unlockItinerary}>编辑行程</button>
            </div>
          </>
        )}
      </div>

        </>
      )}

      {/* ========== 子模块二：穿搭（衣橱 + 按天试穿 + AI 方案） ========== */}
      {subview === "outfit" && (
        <>
      <div className="ofit-section">
        <div className="ofit-section-title">我的衣橱</div>
        <div className="ofit-upload" onDragOver={(e) => e.preventDefault()} onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files?.[0]; if (f) onFile(f); }}>
          <input
            type="file"
            ref={fileInputRef}
            accept="image/*"
            style={{ display: "none" }}
            onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) onFile(f); }}
          />
          <button className="btn send-btn" disabled={uploading} onClick={() => fileInputRef.current?.click()}>
            {uploading ? "识别中…" : "📸 上传衣物"}
          </button>
          <span className="ofit-upload-hint">或拖拽图片到此处 · AI 自动识别品类/颜色/面料/季节 · 自动压缩（长边≤1280px / JPG / ≤600KB）</span>
        </div>

        {pendingGarments.length > 0 && (
          <div className="ofit-pending">
            <div className="ofit-pending-title">识别到 {pendingGarments.length} 件衣物，勾选要加入衣橱的：</div>
            {pendingGarments.map((g, i) => (
              <label className="ofit-pending-item" key={i}>
                <input
                  type="checkbox"
                  checked={!!pendingChecked[i]}
                  onChange={(e) => setPendingChecked((prev) => ({ ...prev, [i]: e.target.checked }))}
                />
                <span>{categoryLabel(g.category)} {g.subCategory}（{g.color} {g.fabric}）</span>
              </label>
            ))}
            <div className="mg-actions">
              <button className="btn send-btn" onClick={confirmPending}>✅ 加入衣橱</button>
              <button className="btn tool-btn" onClick={() => setPendingGarments([])}>取消</button>
            </div>
          </div>
        )}

        {wardrobeStats.length > 0 && (
          <div className="ofit-stats">
            <span className="ofit-stats-total">共 {wardrobe.length} 件：</span>
            {wardrobeStats.map((s) => (
              <span className="mg-word" key={s.key}>{s.label} {s.count}</span>
            ))}
            {idleItems.length > 0 && <span className="mg-word ofit-idle-word">闲置 {idleItems.length} 件</span>}
          </div>
        )}

        {wardrobe.length === 0 && <div className="mg-hint">衣橱为空，先上传几件衣物，再进入按天穿搭试穿</div>}

        {wardrobe.length > 0 && (
          <div className="ofit-wardrobe">
            {CATEGORIES.map((cat) => {
              const items = wardrobe.filter((i) => i.category === cat.key);
              if (!items.length) return null;
              const open = !collapsedCats[cat.key];
              return (
                <div className="ofit-cat" key={cat.key}>
                  <button className="ofit-cat-head" onClick={() => toggleCat(cat.key)}>
                    <span>{cat.label}</span>
                    <span className="ofit-cat-count">{items.length} 件 {open ? "▾" : "▸"}</span>
                  </button>
                  {open && (
                    <div className="ofit-grid">
                      {items.map((item) => (
                        <div className="ofit-item-card" key={item.itemId}>
                          {!isItemUsed(item) && <span className="ofit-item-idle">闲置</span>}
                          <button className="ofit-item-del" onClick={() => deleteItem(item.itemId)} title="删除">🗑️</button>
                          {item.imgBase64 ? (
                            <img className="ofit-item-img" src={item.imgBase64} alt={item.subCategory} loading="eager" />
                          ) : (
                            <div className="ofit-item-noimg">🪡</div>
                          )}
                          <div className="ofit-item-info">
                            <div className="ofit-item-sub">{item.subCategory || item.category}</div>
                            <select
                              className="ofit-item-cat"
                              value={item.category}
                              onChange={(e) => changeItemCategory(item.itemId, e.target.value)}
                              title="手动修改品类"
                            >
                              {CATEGORIES.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
                            </select>
                            <div className="ofit-item-tags">
                              {item.color && <span className="mg-word">{item.color}</span>}
                              {item.fabric && <span className="mg-word">{item.fabric}</span>}
                              {(item.season || []).slice(0, 2).map((s) => <span className="mg-word" key={s}>{seasonLabel(s)}</span>)}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ========== 按天穿搭（虚拟试穿） ========== */}
      <div className="ofit-section">
        <div className="ofit-section-title">穿搭 · 虚拟试穿</div>

        {status !== "confirmed" ? (
          <div className="mg-hint">请先在「旅游计划」模块确认全部行程，再进入按天穿搭试穿</div>
        ) : activeDay ? (
          <>
            <div className="ofit-day-tabs">
              {days.map((d) => (
                <button
                  key={d.dayId}
                  className={`ofit-day-tab ${d.dayId === activeDayId ? "ofit-day-tab-active" : ""}`}
                  onClick={() => setActiveDayId(d.dayId)}
                >
                  {dayTabLabel(d)}
                </button>
              ))}
              {activeDayIndex > 0 && (
                <button className="btn tool-btn ofit-copy-prev" onClick={copyPreviousDay}>复制前一天穿搭</button>
              )}
            </div>
            <div className="mg-hint">当前第 {activeDay.day} 天 · {activeDay.city}（{(activeDay.spots || []).join("、")}），为本天挑选穿搭试穿，完成后切换下一天</div>

            {/* ① 全身人像（默认 + 本天覆盖） */}
            <div className="ofit-tryon-step">
              <span className="ofit-tryon-step-num">①</span>
              <span className="ofit-tryon-step-label">全身人像</span>
              <input
                type="file"
                ref={defaultPersonInputRef}
                accept="image/*"
                style={{ display: "none" }}
                onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) onDefaultPersonFile(f); }}
              />
              <button className="btn tool-btn" onClick={() => defaultPersonInputRef.current?.click()}>
                {tryOnStore.defaultPersonImg ? "更换默认人像" : "上传默认人像"}
              </button>
              <input
                type="file"
                ref={personInputRef}
                accept="image/*"
                style={{ display: "none" }}
                onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) onDayPersonFile(f); }}
              />
              <button className="btn tool-btn" onClick={() => personInputRef.current?.click()}>
                {activeTryOn.personImg ? "更换本天人像" : "上传本天人像"}
              </button>
              {activePersonImg ? (
                <span className="ofit-tryon-status">✅ {activeTryOn.personImg ? "本天人像已设" : "使用默认人像"}</span>
              ) : (
                <span className="ofit-tryon-warn">⚠️ 未上传人像</span>
              )}
            </div>

            {/* ② 选择衣物 */}
            <div className="ofit-tryon-step">
              <span className="ofit-tryon-step-num">②</span>
              <span className="ofit-tryon-step-label">选择衣物</span>
              {activeTryOn.selectedItemIds?.length ? (
                <span className="ofit-tryon-picked">
                  已选 {activeTryOn.selectedItemIds.length} 件：
                  {activeTryOn.selectedItemIds.map((id) => wardrobe.find((i) => i.itemId === id)?.subCategory || "已选").join(" + ")}
                </span>
              ) : (
                <span className="ofit-tryon-warn">⚠️ 未选衣物</span>
              )}
            </div>

            {wardrobe.length === 0 ? (
              <div className="mg-hint">衣橱为空，先去上方「我的衣橱」上传衣物</div>
            ) : (
              <>
                <div className="ofit-tryon-pick-grid">
                  {wardrobe.map((i) => {
                    const isRec = rec.topItemId === i.itemId || rec.bottomItemId === i.itemId;
                    return (
                      <div
                        key={i.itemId}
                        className={`ofit-tryon-pick ${activeTryOn.selectedItemIds?.includes(i.itemId) ? "ofit-tryon-pick-selected" : ""}`}
                        onClick={() => toggleTryOnItem(i)}
                        title={i.subCategory || i.category}
                      >
                        {i.imgBase64 ? (
                          <img className="ofit-tryon-pick-img" src={i.imgBase64} alt={i.subCategory} loading="eager" />
                        ) : (
                          <div className="ofit-item-noimg ofit-tryon-pick-img">🪡</div>
                        )}
                        {isRec && <span className="ofit-rec-badge">推荐</span>}
                        <div className="ofit-tryon-pick-name">{i.subCategory || i.category}</div>
                        <div className="ofit-tryon-pick-sub">{i.color} {i.fabric}</div>
                      </div>
                    );
                  })}
                </div>
                <div className="mg-actions">
                  <button className="btn tool-btn" onClick={applyRecommendation}>
                    ✨ 应用推荐穿搭
                  </button>
                  {activeDay.temp && (
                    <span className="ofit-hint">智能推荐基于本天气温 {activeDay.temp}℃{activeDay.rain ? " + 有雨" : ""} · {activeDay.style} · {dayScenarioHint(activeDay)}</span>
                  )}
                </div>
              </>
            )}

            {/* ③ 生成试穿 */}
            <div className="ofit-tryon-step">
              <span className="ofit-tryon-step-num">③</span>
              <span className="ofit-tryon-step-label">生成试穿效果</span>
              <button className="btn send-btn" disabled={tryOnLoading} onClick={doTryOn}>
                {tryOnLoading ? "试穿生成中…" : "👗 生成试穿效果"}
              </button>
            </div>

            {activePersonImg && (
              <div className="ofit-tryon-preview">
                <img className="ofit-tryon-person" src={activePersonImg} alt="人物照片" loading="eager" />
                <span className="ofit-tryon-preview-label">人物参考图</span>
              </div>
            )}

            {activeTryOn.resultImg && (
              <div className="ofit-tryon-result-area">
                <div className="ofit-tryon-img-wrap">
                  <img className="ofit-tryon-result" src={activeTryOn.resultImg} alt="试穿效果" loading="eager" />
                </div>
                {tryOnExplain && (
                  <div className="ofit-tryon-explain">
                    <div className="ofit-section-title">穿搭说明</div>
                    <MdRender content={tryOnExplain} />
                  </div>
                )}
              </div>
            )}
          </>
        ) : (
          <div className="mg-hint">请先添加并确认行程</div>
        )}
      </div>

      {/* ========== AI 穿搭方案 + 手册 ========== */}
      <div className="ofit-section">
        <div className="ofit-section-title">AI 穿搭方案</div>
        {status === "confirmed" && (
          <div className="mg-actions">
            <button className="btn send-btn" disabled={generating} onClick={generatePlans}>
              {generating ? "生成中…" : "🤖 一键生成各天穿搭方案"}
            </button>
            <button className="btn tool-btn" disabled={manualLoading} onClick={manualGenerate}>
              {manualLoading ? "生成中…" : "📒 生成完整穿搭手册"}
            </button>
            {(combinedPlanText() || manual) && (
              <>
                <button className="btn tool-btn" onClick={exportPlan}>📄 导出 .md</button>
                <button className="btn tool-btn" onClick={exportImage}>🖼️ 导出图片</button>
              </>
            )}
          </div>
        )}
        <div className="mg-hint">确认行程后为每天生成一套推荐穿搭；每个方案可一键「去试穿」自动勾选推荐单品并生成试穿图。</div>

        {/* 每天方案卡 */}
        <div className="ofit-plans">
          {days.map((d) => {
            const data = tryOnStore.days[d.dayId];
            if (!data?.plan && !data?.resultImg && !failedPlanDayIds.includes(d.dayId)) return null;
            const outfits = data?.outfits || [];
            const sections = data?.plan ? splitPlanSections(data.plan) : [];
            return (
              <div className="ofit-plan-card" key={d.dayId}>
                <div className="ofit-plan-head">
                  <span className="ofit-plan-title">第{d.day}天 · {(d.spots || []).join("/")}</span>
                  {!outfits.length && (
                    <button className="btn tool-btn ofit-tryout-btn" onClick={() => gotoDayTryOn(d.dayId)}>去试穿</button>
                  )}
                </div>
                {data.plan ? (
                  sections.length ? (
                    /* 每个方案一段，按钮放在该段标题旁 */
                    <div className="ofit-plan-sections">
                      {sections.filter((s) => !s.header).map((s, i) => (
                        <MdRender key={`lead${i}`} content={s.body} />
                      ))}
                      {sections.filter((s) => !!s.header).map((sec, i) => {
                        const fallback = i < outfits.length ? outfits[i] : undefined;
                        // 结果 key 用方案段自身顺序（第1段=方案1…），保证各段唯一、互不覆盖
                        const resultKey = `方案${i + 1}`;
                        // 优先按该方案正文描述的衣物匹配衣橱，匹配不到才用 AI 推荐编号
                        const matched = matchSectionItems(sec.body, wardrobe, fallback);
                        const topItem = wardrobe.find((w) => w.itemId === matched.topId);
                        const bottomItem = wardrobe.find((w) => w.itemId === matched.bottomId);
                        const clothes = [topItem?.subCategory, bottomItem?.subCategory].filter(Boolean).join(" + ");
                        return (
                          <div className="ofit-plan-sec" key={i}>
                            <div className="ofit-plan-sec-head">
                              <span className="ofit-plan-sec-label">{stripMd(sec.header) || `方案${i + 1}`}</span>
                              {(matched.topId || matched.bottomId) && (
                                <button
                                  className="btn tool-btn ofit-tryout-btn"
                                  onClick={() => tryOnItems(d.dayId, matched.topId, matched.bottomId, resultKey, stripMd(sec.header))}
                                  title={`试穿：${clothes}`}
                                >
                                  去试穿{clothes ? `（${clothes}）` : ""}
                                </button>
                              )}
                            </div>
                            {sec.body && <MdRender content={sec.body} />}
                            {data.outfitResults?.[resultKey] && (
                              <img className="ofit-plan-result" src={data.outfitResults[resultKey]} alt="试穿效果" loading="eager" />
                            )}
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    /* 兼容旧数据 / 未按方案分段：整段渲染 + 尾部按钮 */
                    <>
                      <MdRender content={data.plan} />
                      {outfits.length > 0 && (
                        <div className="ofit-plan-outfits">
                          <span className="ofit-plan-outfits-label">旧版本方案无法按方案拆分，重新生成后可逐方案试穿</span>
                          {outfits.map((o) => (
                            <button key={o.label} className="btn tool-btn ofit-tryout-btn" onClick={() => tryOutfit(d.dayId, o)}>
                              {o.label}·去试穿
                            </button>
                          ))}
                        </div>
                      )}
                      {data.resultImg && <img className="ofit-plan-result" src={data.resultImg} alt="试穿效果" loading="eager" />}
                    </>
                  )
                ) : (
                  <div className="ofit-plan-fail">
                    <span>该天方案生成失败</span>
                    <button className="btn tool-btn" onClick={() => retryDay(d.dayId)}>单独重试</button>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {manual && (
          <div className="ofit-manual">
            <div className="ofit-section-title">📒 完整旅游穿搭手册</div>
            <MdRender content={manual} />
          </div>
        )}

        {/* 追问调整（绑定当前天） */}
        {status === "confirmed" && activeDay && (
          <div className="ofit-followup">
            <input
              className="ofit-input ofit-followup-input"
              value={followUp}
              onChange={(e) => setFollowUp(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && sendFollowUp()}
              placeholder={`追问调整「第${activeDay.day}天」：如「加一件薄外套」「换亮色」…`}
            />
            <button className="btn send-btn" disabled={streaming} onClick={sendFollowUp}>
              {streaming ? "生成中…" : "追问"}
            </button>
          </div>
        )}

        {msgs.length > 0 && (
          <div className="ofit-chat" ref={chatRef}>
            {msgs.map((m) => (
              <div key={m.id} className={m.role === "user" ? "msg-item user-message" : "msg-item bot-message"}>
                {m.role === "bot" ? <MdRender content={m.text} /> : m.text}
              </div>
            ))}
          </div>
        )}
      </div>

        </>
      )}

      {/* ========== 子模块三：酒店/机票查询（道旅） ========== */}
      {subview === "hotel" && (
        <DaolvPanel />
      )}

      {/* ========== 子模块四：周边餐馆查询（美食推荐） ========== */}
      {subview === "food" && (
        <RestaurantView currentOutfitId={currentOutfitId} onDaysChanged={loadDays} />
      )}

      {/* 导出图片隐藏目标 */}
      <div ref={exportRef} className="ofit-export-target" aria-hidden="true">
        <div className="ofit-export-title">👗 {currentTitle}</div>
        {manual ? (
          <div className="ofit-export-plan"><MdRender content={manual} /></div>
        ) : (
          days.map((d) => {
            const data = tryOnStore.days[d.dayId];
            if (!data?.plan) return null;
            return (
              <div className="ofit-export-plan" key={d.dayId}>
                <div className="ofit-export-title">第{d.day}天 · {d.city}</div>
                <MdRender content={data.plan} />
              </div>
            );
          })
        )}
      </div>

      {/* 景点拍照参考弹窗 */}
      {photoGuideSpot && <PhotoGuideModal scenicName={photoGuideSpot} onClose={() => setPhotoGuideSpot(null)} />}
    </div>
  );
}

/** 基于气温/降雨/风格/当日景点场景的智能推荐（过滤鞋配饰；≥33℃ 屏蔽厚外套；户外/室内场景加权） */
function recommendWardrobeForDay(day: TravelDay | undefined, wardrobe: WardrobeItem[]) {
  if (!day) return { recommended: [], topItemId: "", bottomItemId: "" };
  const tempNum = parseInt(day.temp, 10);
  const seasons: string[] = [];
  if (!isNaN(tempNum)) {
    if (tempNum >= 28) seasons.push("summer");
    else if (tempNum >= 20) seasons.push("summer", "spring");
    else if (tempNum >= 12) seasons.push("spring", "autumn");
    else if (tempNum >= 0) seasons.push("autumn", "winter");
    else seasons.push("winter");
  }
  const rain = !!day.rain;
  const style = (day.style || "").trim();
  const spotsText = (day.spots || []).join("");
  const outdoor = /爬山|徒步|栈道|森林|草原|峡谷|郊野|公园|山/.test(spotsText);
  const indoor = /博物馆|美术馆|展馆|剧院|展览|科技馆|艺术馆|图书馆/.test(spotsText);
  const isThickOuter = (i: WardrobeItem) => (i.subCategory || "").includes("大衣") || (i.subCategory || "").includes("羽绒服");
  const isLightWindproof = (i: WardrobeItem) => {
    const f = (i.fabric || "") + (i.subCategory || "");
    return f.includes("防水") || f.includes("冲锋") || f.includes("防风") || f.includes("轻薄") || f.includes("防晒");
  };
  const isOutdoorFit = (i: WardrobeItem) => {
    const t = (i.fabric || "") + (i.style || []).join("");
    return t.includes("防滑") || t.includes("休闲") || t.includes("运动") || t.includes("舒适");
  };
  const isIndoorFit = (i: WardrobeItem) => {
    const t = (i.style || []).join("");
    return t.includes("温柔") || t.includes("简约") || t.includes("通勤") || t.includes("甜美");
  };

  const scored = wardrobe
    // 底层试衣接口不支持鞋履/配饰，直接过滤
    .filter((i) => i.category === "top" || i.category === "dress" || i.category === "coat" || i.category === "bottom")
    .filter((i) => !(tempNum >= 33 && isThickOuter(i)))
    .map((i) => {
      let score = 0;
      if (seasons.length && (i.season || []).some((s) => seasons.includes(s))) score += 3;
      if (rain) {
        if (isLightWindproof(i)) score += 3;
        else if (i.category === "coat") score += 1;
      }
      if (style && (i.style || []).some((s) => s && (s.includes(style) || style.includes(s)))) score += 2;
      if (outdoor && isOutdoorFit(i)) score += 1;
      if (indoor && isIndoorFit(i)) score += 1;
      return { item: i, score };
    })
    .sort((a, b) => b.score - a.score);

  const top = scored.find((s) => s.item.category === "top" || s.item.category === "dress" || s.item.category === "coat");
  const bottom = scored.find((s) => s.item.category === "bottom");
  return {
    recommended: scored,
    topItemId: top ? top.item.itemId : "",
    bottomItemId: bottom ? bottom.item.itemId : "",
  };
}

/** 按「方案N：」标题把方案正文拆段 */
function splitPlanSections(plan: string): { header: string; body: string }[] {
  const headerRe = /^\s*(#{1,3}\s*)?\**\s*方案\s*([一二三四五六123456])\s*[：:、.．]?\s*([^\n]*)/;
  const sections: { header: string; body: string }[] = [];
  let current: { header: string; body: string } | null = null;
  let lead = "";
  for (const line of plan.split("\n")) {
    const m = line.match(headerRe);
    if (m) {
      if (current) sections.push(current);
      current = { header: line.trim(), body: "" };
    } else if (current) {
      current.body += (current.body ? "\n" : "") + line;
    } else if (line.trim()) {
      lead += (lead ? "\n" : "") + line; // 第一个方案标题前的引言段
    }
  }
  if (current) sections.push(current);
  if (lead.trim()) sections.unshift({ header: "", body: lead.trim() });
  return sections;
}

/** 去掉标题的 markdown 标记，仅留文字 */
function stripMd(s: string): string {
  return s.replace(/[#*_`>~]/g, "").trim();
}

/** 按方案正文描述的衣物匹配衣橱；匹配不到才回退 AI 推荐编号（保证所见即所穿） */
function matchSectionItems(body: string, wardrobe: WardrobeItem[], fallback?: OutfitRec): { topId: string; bottomId: string } {
  const text = body || "";
  const topPool = wardrobe.filter((i) => i.category === "top" || i.category === "dress" || i.category === "coat");
  const bottomPool = wardrobe.filter((i) => i.category === "bottom");
  const score = (item: WardrobeItem): number => {
    const keys = [item.subCategory, item.color, item.fabric, ...(item.style || [])].filter((k) => k && k.length >= 2);
    let s = 0;
    for (const k of keys) if (text.includes(k)) s += k.length >= 3 ? 2 : 1;
    return s;
  };
  const bestTop = topPool.map((i) => ({ i, s: score(i) })).filter((x) => x.s > 0).sort((a, b) => b.s - a.s)[0];
  const bestBottom = bottomPool.map((i) => ({ i, s: score(i) })).filter((x) => x.s > 0).sort((a, b) => b.s - a.s)[0];
  return {
    topId: bestTop ? bestTop.i.itemId : (fallback?.topItemId || ""),
    bottomId: bestBottom ? bestBottom.i.itemId : (fallback?.bottomItemId || ""),
  };
}

/** 当日场景提示（户外/室内） */
function dayScenarioHint(day: TravelDay | undefined): string {
  if (!day) return "";
  const spotsText = (day.spots || []).join("");
  if (/爬山|徒步|栈道|森林|草原|峡谷|郊野|公园|山/.test(spotsText)) return "户外徒步场景，偏好休闲舒适/防滑";
  if (/博物馆|美术馆|展馆|剧院|展览|科技馆|艺术馆|图书馆/.test(spotsText)) return "室内展馆场景，偏好温柔简约";
  return "";
}

function seasonLabel(s: string): string {
  return { spring: "春", summer: "夏", autumn: "秋", winter: "冬" }[s] || s;
}
