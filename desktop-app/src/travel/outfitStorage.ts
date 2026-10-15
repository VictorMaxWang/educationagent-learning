/**
 * 出行持久化工具（镜像 chatStorage 多会话模式）。
 *  - 衣橱：desktop-user-wardrobe（WardrobeItem[]）
 *  - 出行会话列表：desktop-outfit-sessions（OutfitSession[]，按天行程 + 确认状态）
 *  - 分天试穿数据：desktop-outfit-tryon-${id}（SessionTryOnStore，默认人像 + 每天数据，key=dayId）
 *  - 单会话记录：desktop-outfit-history-${id}（旧单行程聊天，仅兼容保留）
 * 防抖 800ms；衣橱上限 150 件；会话标题 = 首天城市 + 总天数 + 首景点/风格前缀。
 */

import { dataUrlBytes } from "./imageCompress";

export interface WardrobeItem {
  itemId: string;
  imgBase64: string;
  category: string;
  subCategory: string;
  color: string;
  fabric: string;
  season: string[];
  style: string[];
  createTime: number;
}

export interface OutfitChatMsg {
  id: number;
  role: "user" | "bot";
  text: string;
  createTime: number;
}

/** 单天行程（多景点）：AI 生成每日景点列表，穿搭/试穿按天一套 */
export interface TravelDay {
  dayId: string;
  day: number; // 第几天（1 起）
  city: string;
  spots: string[]; // 当天景点列表
  route?: string; // AI 交通建议（景点间交通方式 + 大致耗时）
  temp: string;
  rain: boolean;
  style: string;
}

/** 单套推荐（一套 = 一件上衣 + 一件下装） */
export interface OutfitRec {
  label: string; // 方案一 / 方案二 ...
  topItemId: string;
  bottomItemId: string;
}

/** 单天的穿搭与试穿数据（按天隔离，切换天不丢失） */
export interface SpotTryOnData {
  personImg?: string; // 天级人像覆盖（空则回退会话默认人像）
  selectedItemIds: string[];
  resultImg?: string; // 最近一次试穿结果（穿搭 Tab 展示）
  plan?: string; // 该天 AI 穿搭方案 markdown
  recommendedTopItemId?: string;
  recommendedBottomItemId?: string;
  outfits?: OutfitRec[]; // 多套推荐（每套独立可试穿）
  outfitResults?: Record<string, string>; // 每套方案对应的试穿结果图（key=方案标签，如"方案1"）
  chat?: OutfitChatMsg[]; // 该天追问调整历史
}

/** 会话级试穿存储：默认人像 + 各天数据 */
export interface SessionTryOnStore {
  defaultPersonImg?: string;
  days: Record<string, SpotTryOnData>; // key = dayId
}

/** 旧单行程类型，仅用于旧数据迁移解析 */
export interface OutfitTravelInfo {
  city: string;
  spots: string;
  days: number;
  temp: string;
  rain: boolean;
  style: string;
  useWardrobe: boolean;
}

export interface OutfitSession {
  sessionId: string;
  title: string;
  days: TravelDay[];
  status: "editing" | "confirmed";
  createTime: number;
  updateTime: number;
}

const WARDROBE_KEY = "desktop-user-wardrobe";
const SESSION_LIST_KEY = "desktop-outfit-sessions";
const HISTORY_PREFIX = "desktop-outfit-history-";
const TRYON_PREFIX = "desktop-outfit-tryon-";

const MAX_WARDROBE = 150;
const DEBOUNCE_MS = 800;
/** 单张结果图/人像 base64 持久化上限，超过则本地置空仅留内存态，防 localStorage 溢出 */
const MAX_PERSIST_IMG_BYTES = 1.5 * 1024 * 1024;

const timers = new Map<string, ReturnType<typeof setTimeout>>();

// ======================== IndexedDB 持久化（衣橱图片容量大，避免 localStorage 5MB 上限丢图） ========================

const IDB_NAME = "desktop-outfit-db";
const IDB_VERSION = 1;
const WARDROBE_STORE = "wardrobe";

let idb: IDBDatabase | null = null;

function openIdb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (idb) return resolve(idb);
    try {
      const req = indexedDB.open(IDB_NAME, IDB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(WARDROBE_STORE)) {
          db.createObjectStore(WARDROBE_STORE); // keyPath 缺省，用 put(item, itemId) 手动指定 key
        }
      };
      req.onsuccess = () => {
        idb = req.result;
        resolve(idb);
      };
      req.onerror = () => reject(req.error);
    } catch (e) {
      reject(e);
    }
  });
}

/** 衣橱内存缓存（getWardrobe 同步读；IndexedDB 异步加载完成后刷新订阅者） */
let wardrobeCache: WardrobeItem[] | null = null;
const wardrobeListeners = new Set<() => void>();

/** 订阅衣橱就绪（IndexedDB 异步加载完成后触发，供组件刷新） */
export function onWardrobeReady(fn: () => void): () => void {
  wardrobeListeners.add(fn);
  return () => { wardrobeListeners.delete(fn); };
}

/**
 * 启动时加载衣橱：优先 IndexedDB，空则迁移 localStorage 旧数据。
 * 加载完成后触发所有订阅者。
 */
export async function initWardrobe(): Promise<WardrobeItem[]> {
  try {
    const db = await openIdb();
    const list = await new Promise<WardrobeItem[]>((resolve, reject) => {
      const tx = db.transaction(WARDROBE_STORE, "readonly");
      const req = tx.objectStore(WARDROBE_STORE).getAll();
      req.onsuccess = () => resolve((req.result as WardrobeItem[]) || []);
      req.onerror = () => reject(req.error);
    });
    wardrobeCache = list.length ? list : readWardrobeLocal();
    if (!list.length && wardrobeCache.length) writeWardrobeIdb(wardrobeCache); // 旧数据迁移
  } catch {
    wardrobeCache = readWardrobeLocal(); // IndexedDB 不可用时回退 localStorage
  }
  wardrobeListeners.forEach((fn) => fn());
  return wardrobeCache;
}

function readWardrobeLocal(): WardrobeItem[] {
  try {
    const raw = localStorage.getItem(WARDROBE_KEY);
    if (!raw) return [];
    const list = JSON.parse(raw);
    return Array.isArray(list) ? list : [];
  } catch { return []; }
}

function writeWardrobeIdb(list: WardrobeItem[]) {
  try {
    openIdb().then((db) => {
      const tx = db.transaction(WARDROBE_STORE, "readwrite");
      const store = tx.objectStore(WARDROBE_STORE);
      store.clear();
      list.slice(-MAX_WARDROBE).forEach((item) => store.put(item, item.itemId));
    }).catch(() => { /* 忽略 */ });
  } catch { /* 忽略 */ }
}

// ======================== 衣橱 ========================

export function getWardrobe(): WardrobeItem[] {
  return wardrobeCache ?? readWardrobeLocal();
}

function persistWardrobe(list: WardrobeItem[]) {
  const trimmed = list.slice(-MAX_WARDROBE);
  // 主存 IndexedDB（容量大，图片完整保留）
  writeWardrobeIdb(trimmed);
  // 同步镜像一份到 localStorage 供旧逻辑兜底（超限去图也不影响 IndexedDB 主数据）
  try {
    localStorage.setItem(WARDROBE_KEY, JSON.stringify(trimmed));
  } catch {
    try {
      const texts = trimmed.map((i) => ({ ...i, imgBase64: "" }));
      localStorage.setItem(WARDROBE_KEY, JSON.stringify(texts));
    } catch { /* 忽略 */ }
  }
}

export function saveWardrobe(list: WardrobeItem[]) {
  wardrobeCache = list.slice(-MAX_WARDROBE); // 立即更新内存缓存，getWardrobe 同步可见
  const key = "wardrobe";
  const existing = timers.get(key);
  if (existing) clearTimeout(existing);
  timers.set(
    key,
    setTimeout(() => {
      timers.delete(key);
      persistWardrobe(list);
    }, DEBOUNCE_MS),
  );
}

export function addWardrobeItem(item: WardrobeItem): WardrobeItem[] {
  const next = [item, ...getWardrobe()];
  saveWardrobe(next);
  return next;
}

export function removeWardrobeItem(itemId: string): WardrobeItem[] {
  const next = getWardrobe().filter((i) => i.itemId !== itemId);
  saveWardrobe(next);
  return next;
}

/** 更新单品字段（如手动改品类） */
export function updateWardrobeItem(itemId: string, patch: Partial<WardrobeItem>): WardrobeItem[] {
  const next = getWardrobe().map((i) => (i.itemId === itemId ? { ...i, ...patch } : i));
  saveWardrobe(next);
  return next;
}

// ======================== 出行会话 ========================

/** 生成空白天卡片 */
export function blankDay(prefix: string, dayIndex: number): TravelDay {
  return {
    dayId: `${prefix}-day-${Date.now().toString(36)}`,
    day: dayIndex,
    city: "",
    spots: [],
    route: "",
    temp: "",
    rain: false,
    style: "舒适",
  };
}

/** 会话标题：`首天城市 总天数日｜首景点或风格`（如 南京 3日｜音乐台） */
function buildTitle(days: TravelDay[]): string {
  const first = days && days.length ? days[0] : null;
  if (!first) return "出行";
  const base = `${first.city || "出行"} ${days.length || 1}日`;
  const firstSpot = (first.spots && first.spots[0]) || "";
  const prefix = firstSpot || (first.style || "").trim();
  return prefix ? `${base}｜${prefix}` : base;
}

/** 旧 tryOn store：把 spots-keyed 数据按 spotId→dayId 映射 remap 为 days-keyed（每 day 取第一处） */
function remapTryOnStore(sessionId: string, spotToDayMap: Record<string, string>) {
  try {
    const raw = localStorage.getItem(TRYON_PREFIX + sessionId);
    if (!raw) return;
    const store = JSON.parse(raw);
    if (!store || Array.isArray(store.days)) return; // 已是新格式
    const oldSpots = store.spots || {};
    const days: Record<string, SpotTryOnData> = {};
    for (const [spotId, data] of Object.entries(oldSpots)) {
      const dayId = spotToDayMap[spotId];
      if (dayId && !days[dayId]) days[dayId] = data as SpotTryOnData;
    }
    localStorage.setItem(TRYON_PREFIX + sessionId, JSON.stringify(stripOversizedImages({ defaultPersonImg: store.defaultPersonImg || "", days })));
  } catch { /* 忽略 */ }
}

/** 旧数据迁移：travelInfo 单行程 / 旧 spots[]（一卡一景点）→ days[]；并 remap 该会话 tryOn（幂等） */
function migrateSession(s: any): OutfitSession {
  const base = {
    sessionId: String(s?.sessionId || ""),
    title: String(s?.title || "出行"),
    createTime: s?.createTime || Date.now(),
    updateTime: s?.updateTime || Date.now(),
  };
  // 已是新格式
  if (s && Array.isArray(s.days)) {
    return { ...base, days: s.days, status: s.status === "confirmed" ? "confirmed" : "editing" };
  }
  let days: TravelDay[] = [];
  const spotToDayMap: Record<string, string> = {};
  if (s && !Array.isArray(s.spots) && s.travelInfo) {
    // 最旧版：单 travelInfo（spots 为自由文本）
    const t = s.travelInfo || {};
    days = [{
      dayId: `${base.sessionId}-day-1`,
      day: 1,
      city: String(t.city || ""),
      spots: String(t.spots || "").split(/[、，,/｜\s]+/).filter(Boolean),
      temp: String(t.temp || ""),
      rain: !!t.rain,
      style: String(t.style || "舒适"),
    }];
  } else {
    // 上一版：spots[] 一卡一景点，数组顺序 = 天数
    const oldSpots: any[] = Array.isArray(s?.spots) ? s.spots : [];
    days = oldSpots.map((sp, i) => {
      const dayId = `${base.sessionId}-day-${i + 1}`;
      if (sp && sp.spotId) spotToDayMap[sp.spotId] = dayId;
      return {
        dayId,
        day: i + 1,
        city: String(sp?.city || ""),
        spots: sp?.name ? [String(sp.name)] : [],
        temp: String(sp?.temp || ""),
        rain: !!sp?.rain,
        style: String(sp?.style || "舒适"),
      };
    });
  }
  if (Object.keys(spotToDayMap).length) {
    remapTryOnStore(base.sessionId, spotToDayMap);
  }
  return { ...base, days, status: "editing" };
}

export function getOutfitSessions(): OutfitSession[] {
  try {
    const raw = localStorage.getItem(SESSION_LIST_KEY);
    if (!raw) return [];
    const list = JSON.parse(raw);
    return Array.isArray(list) ? list.map(migrateSession) : [];
  } catch {
    return [];
  }
}

function persistSessions(list: OutfitSession[]) {
  try {
    localStorage.setItem(SESSION_LIST_KEY, JSON.stringify(list));
  } catch { /* 忽略 */ }
}

export function createOutfitSession(days?: TravelDay[]): OutfitSession {
  const id = Date.now().toString();
  const list: TravelDay[] = days && days.length ? days : [blankDay(id, 1)];
  const s: OutfitSession = {
    sessionId: id,
    title: buildTitle(list),
    days: list,
    status: "editing",
    createTime: Date.now(),
    updateTime: Date.now(),
  };
  const sessions = getOutfitSessions();
  sessions.unshift(s);
  persistSessions(sessions);
  try {
    localStorage.setItem(HISTORY_PREFIX + id, JSON.stringify([]));
  } catch { /* 忽略 */ }
  return s;
}

/** 更新某会话的天列表（同时刷新标题），删除某天时需另行清理该天 tryOn */
export function updateSessionDays(sessionId: string, days: TravelDay[]) {
  const sessions = getOutfitSessions();
  const idx = sessions.findIndex((s) => s.sessionId === sessionId);
  if (idx < 0) return;
  sessions[idx].days = days;
  sessions[idx].title = buildTitle(days);
  sessions[idx].updateTime = Date.now();
  persistSessions(sessions);
}

/** 行程确认 / 解锁 */
export function setOutfitSessionStatus(sessionId: string, status: "editing" | "confirmed") {
  const sessions = getOutfitSessions();
  const idx = sessions.findIndex((s) => s.sessionId === sessionId);
  if (idx < 0) return;
  sessions[idx].status = status;
  sessions[idx].updateTime = Date.now();
  persistSessions(sessions);
}

export function deleteOutfitSession(sessionId: string) {
  try {
    localStorage.removeItem(HISTORY_PREFIX + sessionId);
  } catch { /* 忽略 */ }
  deleteSessionTryOn(sessionId);
  persistSessions(getOutfitSessions().filter((s) => s.sessionId !== sessionId));
}

// ======================== 旧单行程聊天（兼容保留，新流程不用） ========================

export function getOutfitHistory(sessionId: string): OutfitChatMsg[] {
  try {
    const raw = localStorage.getItem(HISTORY_PREFIX + sessionId);
    if (!raw) return [];
    const list = JSON.parse(raw);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export function saveOutfitHistory(sessionId: string, msgs: OutfitChatMsg[]) {
  const existing = timers.get(sessionId);
  if (existing) clearTimeout(existing);
  timers.set(
    sessionId,
    setTimeout(() => {
      timers.delete(sessionId);
      try {
        localStorage.setItem(HISTORY_PREFIX + sessionId, JSON.stringify(msgs.slice(-100)));
      } catch { /* 忽略 */ }
    }, DEBOUNCE_MS),
  );
}

// ======================== 分天试穿数据 ========================

export function getSessionTryOn(sessionId: string): SessionTryOnStore {
  try {
    const raw = localStorage.getItem(TRYON_PREFIX + sessionId);
    if (!raw) return { days: {} };
    const store = JSON.parse(raw);
    return {
      defaultPersonImg: store?.defaultPersonImg || "",
      days: store?.days || {},
    };
  } catch {
    return { days: {} };
  }
}

/** 剥掉单张超限图（>1.5MB）的本地 base64，仅留内存态，防溢出 */
function stripOversizedImages(store: SessionTryOnStore): SessionTryOnStore {
  const out: SessionTryOnStore = { days: {} };
  for (const [dayId, d] of Object.entries(store.days || {})) {
    const day: SpotTryOnData = { ...d };
    if (day.resultImg && dataUrlBytes(day.resultImg) > MAX_PERSIST_IMG_BYTES) day.resultImg = "";
    if (day.personImg && dataUrlBytes(day.personImg) > MAX_PERSIST_IMG_BYTES) day.personImg = "";
    out.days[dayId] = day;
  }
  if (store.defaultPersonImg && dataUrlBytes(store.defaultPersonImg) > MAX_PERSIST_IMG_BYTES) {
    out.defaultPersonImg = "";
  } else {
    out.defaultPersonImg = store.defaultPersonImg;
  }
  return out;
}

/** 兜底：整体超限时全部图片置空，仅保留文字数据 */
function stripAllImages(store: SessionTryOnStore): SessionTryOnStore {
  const out: SessionTryOnStore = { days: {} };
  for (const [dayId, d] of Object.entries(store.days || {})) {
    out.days[dayId] = { ...d, personImg: "", resultImg: "" };
  }
  out.defaultPersonImg = "";
  return out;
}

function persistTryOn(sessionId: string, store: SessionTryOnStore) {
  try {
    localStorage.setItem(TRYON_PREFIX + sessionId, JSON.stringify(stripOversizedImages(store)));
  } catch {
    try {
      localStorage.setItem(TRYON_PREFIX + sessionId, JSON.stringify(stripAllImages(store)));
    } catch { /* 忽略 */ }
  }
}

export function saveSessionTryOn(sessionId: string, store: SessionTryOnStore) {
  const key = "tryon:" + sessionId;
  const existing = timers.get(key);
  if (existing) clearTimeout(existing);
  timers.set(
    key,
    setTimeout(() => {
      timers.delete(key);
      persistTryOn(sessionId, store);
    }, DEBOUNCE_MS),
  );
}

export function deleteSessionTryOn(sessionId: string) {
  try {
    localStorage.removeItem(TRYON_PREFIX + sessionId);
  } catch { /* 忽略 */ }
}

/** 删除某天时同步清理其全部试穿数据（人像/试穿图/对话） */
export function removeDayTryOn(sessionId: string, dayId: string) {
  const store = getSessionTryOn(sessionId);
  if (store.days[dayId]) {
    delete store.days[dayId];
    saveSessionTryOn(sessionId, store);
  }
}
