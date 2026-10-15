/**
 * 出行模块 API 层（原生 fetch，与项目其他模块一致，无第三方依赖）
 * 后端统一前缀 /api/desktop（WardrobeController / ScenicController / DaolvController）
 */
import { API_BASE_URL as BASE_URL } from '../api/baseUrl';
const DESKTOP_BASE = `${BASE_URL}/api/desktop`;

/** 通用 JSON 请求（GET 不带 body，POST 传 body 对象） */
async function req<T = any>(url: string, options: RequestInit = {}): Promise<T> {
  const resp = await fetch(`${DESKTOP_BASE}${url}`, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

const post = <T = any>(url: string, body: unknown): Promise<T> =>
  req<T>(url, { method: "POST", body: JSON.stringify(body) });
const get = <T = any>(url: string): Promise<T> => req<T>(url);

/** 失败兜底：保持 {code:500, msg} 结构，前端按 res.code 判断 */
const wrap = <T>(p: Promise<T>): Promise<any> =>
  p.catch((e) => ({ code: 500, msg: `请求失败：${e}` }));

// ======================== 出行穿搭 ========================

/** 识别衣物图片（Qwen-VL） */
export const wardrobeRecognize = (imageDataUrl: string): Promise<any> =>
  wrap(post("/wardrobe/recognize", { image: imageDataUrl }));

/** 虚拟试穿（人像 + 衣物列表 → AI试衣 aitryon-plus，支持上衣+下装多件整套） */
export const wardrobeTryOn = (image: string, garments: { image: string; category: string }[]): Promise<any> =>
  wrap(post("/wardrobe/tryon", { image, garments }));

/** 试穿解释（穿搭理由/景点适配/天气适配） */
export const wardrobeTryOnExplain = (travelInfo: any, outfit: string): Promise<any> =>
  wrap(post("/wardrobe/tryon/explain", { travelInfo, outfit }));

/**
 * 行程生成：按城市生成每日景点列表（DeepSeek）。
 * 返回 {code, itinerary:[{day, city, spots:[]}]}；失败 {code:500, msg}
 */
export const outfitPlan = (city: string, days: number, style: string, extra: string): Promise<any> =>
  wrap(post("/outfit/plan", { city, days, style, extra }));

/**
 * 按天生成穿搭方案（DeepSeek）。
 * 返回 {code, plans:[{dayId, dayLabel, plan, recommendedTopItemId, recommendedBottomItemId}]}
 */
export const outfitGenerateMulti = (days: any[], wardrobe: any[]): Promise<any> =>
  wrap(post("/outfit/generate-multi", { days, wardrobe }));

/**
 * 汇总生成完整旅游穿搭手册（DeepSeek）。
 * 返回 {code, manual}
 */
export const outfitManual = (days: any[], outfits: { dayId: string; outfit: string }[]): Promise<any> =>
  wrap(post("/outfit/manual", { days, outfits }));

/**
 * 查询景点间真实交通路线（高德地图）。
 * 返回 {code, routes:[{from, to, transit, driving}]}
 */
export const outfitRoutes = (city: string, legs: { from: string; to: string }[]): Promise<any> =>
  wrap(post("/outfit/routes", { city, legs }));

/** 目的地天气（结构化 temp/condition） */
export const weatherForCity = (city: string): Promise<any> =>
  wrap(get(`/weather/${encodeURIComponent(city.trim())}`));

/**
 * 追问调整穿搭（SSE 流式，POST + fetch 手动解析）。
 * 事件协议与聊天流一致：start → message*（累加）→ done。
 */
export const outfitChatStream = (
  travelInfo: any,
  wardrobe: any[],
  history: { role: string; text: string }[],
  question: string,
  onText: (text: string) => void,
  onDone?: () => void,
): { close: () => void } => {
  const controller = new AbortController();
  let fullText = "";
  (async () => {
    try {
      const res = await fetch(`${DESKTOP_BASE}/outfit/chat-stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ travelInfo, wardrobe, history, question }),
        signal: controller.signal,
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const reader = res.body?.getReader();
      if (!reader) return;
      const decoder = new TextDecoder();
      let buffer = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let sep: number;
        while ((sep = buffer.indexOf("\n\n")) !== -1) {
          const block = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          const { name, data } = parseSseBlock(block);
          if (name === "message") {
            fullText += data;
            onText(fullText);
          } else if (name === "done") {
            onDone?.();
          } else if (name === "error") {
            onText(fullText + `\n[错误] ${data}`);
            onDone?.();
          }
        }
      }
      onDone?.();
    } catch (e) {
      onDone?.();
    }
  })();
  return { close: () => controller.abort() };
};

// ======================== 道旅（酒店 / 机票） ========================

/** 道旅工具列表（酒店+机票，含输入 schema） */
export const daolvTools = (): Promise<any> => wrap(get("/daolv/tools"));

/** 调用道旅工具（tool 为 qualifiedName，如 daolv_SearchHotels） */
export const daolvCall = (tool: string, args: Record<string, unknown>): Promise<any> =>
  wrap(post("/daolv/call", { tool, args }));

// ======================== 景点拍照参考 ========================

/** 景点人像打卡参考（Wikimedia 实景图 + 拍照攻略） */
export const scenicPhotoReference = (scenicName: string, style: string): Promise<any> =>
  wrap(post("/scenic/photo-reference", { scenicName, style }));

// ======================== 周边餐馆查询（美食推荐） ========================

/** 周边餐馆搜索 */
export const restaurantSearch = (params: {
  locationName?: string;
  lon?: number;
  lat?: number;
  city?: string;
  radius?: number;
  preference?: Record<string, unknown>;
  sortType?: string;
}): Promise<any> => wrap(post("/restaurant/search", params));

/** 收藏餐馆 */
export const restaurantFavorite = (body: Record<string, unknown>): Promise<any> =>
  wrap(post("/restaurant/favorite", body));

/** 列出某行程的收藏 */
export const restaurantFavorites = (travelPlanId: string): Promise<any> =>
  wrap(post("/restaurant/favorites", { travelPlanId }));

/** 添加餐馆到每日行程 */
export const restaurantAddToSchedule = (body: Record<string, unknown>): Promise<any> =>
  wrap(post("/restaurant/schedule/addRestaurant", body));

// ======================== 工具 ========================

/** 解析 SSE 数据块 "event: xxx\ndata: xxx" */
function parseSseBlock(block: string): { name: string; data: string } {
  let name = "";
  let data = "";
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) name = line.slice(6).trim();
    else if (line.startsWith("data:")) data += line.slice(5).trim();
  }
  return { name, data };
}
