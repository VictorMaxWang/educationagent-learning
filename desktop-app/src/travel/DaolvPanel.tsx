import { useEffect, useState } from "react";
import { daolvTools, daolvCall } from "./request";

interface SchemaProp {
  type?: string;
  description?: string;
  enum?: string[];
  properties?: Record<string, SchemaProp>;
}

interface DaolvTool {
  source: string;
  name: string;
  qualifiedName: string;
  description: string;
  inputSchema?: { properties?: Record<string, SchemaProp> };
  required?: string[];
}

interface FieldDef {
  path: string; // 顶层键 或 "父.子"
  label: string;
  type: string;
  enum?: string[];
  required: boolean;
}

interface FieldGroup {
  title: string;
  items: FieldDef[];
}

interface TagItem {
  name: string;
  category: string;
  description?: string | null;
}

interface TagGroup {
  category: string;
  items: TagItem[];
}

interface HotelInfo {
  hotelId: number;
  name: string;
  nameEn: string;
  starRating?: number;
  priceMsg: string;
  address: string;
  distance?: number | null;
  tags: string[];
  imageUrl: string;
  bookingUrl: string;
  description: string;
  amenities: string[];
}

const DAOLV_TAGS_KEY = "desktop-daolv-tags";
const TAGS_TTL_MS = 60 * 60 * 1000; // 标签字典缓存 1 小时
const FORMS_KEY = "desktop-daolv-forms"; // 各工具表单持久化（切换/卸载不丢）
const SELECTED_TAGS_KEY = "desktop-daolv-selected-tags"; // 酒店标签勾选持久化
const RESULTS_KEY = "desktop-daolv-results"; // 查询结果持久化
const TAB_KEY = "desktop-daolv-tab"; // 当前工具 Tab 持久化

/** place 类型的固定枚举（只能选这些，不能填地名） */
const PLACE_TYPES = ["城市", "机场", "景点", "火车站", "地铁站", "酒店", "区/县", "详细地址"];

const toolLabel = (t: DaolvTool) => {
  if (t.name === "searchHotels") return "搜索酒店";
  if (t.name === "getHotelDetail") return "酒店详情";
  if (t.name === "getHotelSearchTags") return "酒店标签";
  return t.name;
};

/** 是否为"必须命中标签"字段（弹窗多选，不用手动打字） */
function isTagField(f: FieldDef): boolean {
  return /requiredtags|命中标签/i.test(f.path + f.label);
}

/** 每个工具的分组规则（按字段 label/path 关键词匹配） */
function fieldGroupsFor(tool: DaolvTool): { title: string; keywords: string[] }[] {
  if (tool.name === "getHotelDetail") {
    return [
      { title: "📅 日期", keywords: ["入住", "离店", "checkin", "checkout", "date"] },
      { title: "🏨 酒店标识", keywords: ["酒店id", "酒店名称", "hotelid", "hotelname", "名称", "name", "id"] },
      { title: "⚙️ 基础配置", keywords: ["国家", "country", "币种", "currency", "货币"] },
      { title: "👥 入住人数", keywords: ["成人", "adult", "儿童", "child", "年龄", "age", "房间", "room"] },
    ];
  }
  // searchHotels / 其他酒店工具
  return [
    { title: "🏨 基础入住信息", keywords: ["成人", "adult", "入住日期", "checkin", "入住晚数", "晚数", "night", "国家", "country"] },
    { title: "🔍 筛选条件", keywords: ["距离", "distance", "星级", "star", "价格", "price", "预算", "品牌", "brand", "标签", "tag"] },
    { title: "📍 搜索目标", keywords: ["地点", "place", "城市", "city"] },
    { title: "🔢 返回设置", keywords: ["数量", "limit", "count"] },
  ];
}

const HIDE_KEYWORDS = ["意图", "综合多轮", "userintent"];

function textOf(f: FieldDef): string {
  return (f.label + " " + f.path).toLowerCase();
}

function groupFields(tool: DaolvTool, fields: FieldDef[]): FieldGroup[] {
  const groups = fieldGroupsFor(tool);
  const buckets: FieldGroup[] = groups.map((g) => ({ title: g.title, items: [] }));
  const used = new Set<FieldDef>();
  for (const f of fields) {
    const t = textOf(f);
    if (HIDE_KEYWORDS.some((k) => t.includes(k))) continue;
    for (let i = 0; i < groups.length; i++) {
      if (groups[i].keywords.some((k) => t.includes(k))) {
        buckets[i].items.push(f);
        used.add(f);
        break;
      }
    }
  }
  const rest = fields.filter((f) => !used.has(f) && !HIDE_KEYWORDS.some((k) => textOf(f).includes(k)));
  if (rest.length) buckets.push({ title: "其他", items: rest });
  return buckets.filter((b) => b.items.length > 0);
}

/** 按 category 分组标签 */
function groupTags(tags: TagItem[]): TagGroup[] {
  const map = new Map<string, TagItem[]>();
  for (const t of tags) {
    const cat = t.category || "其他";
    if (!map.has(cat)) map.set(cat, []);
    map.get(cat)!.push(t);
  }
  return Array.from(map.entries()).map(([category, items]) => ({ category, items }));
}

/**
 * 道旅酒店查询面板（仅酒店）。
 * 搜索酒店/详情：分组表单；酒店标签：分类折叠多选 + 缓存；搜索页标签走弹窗多选。
 */
export default function DaolvPanel() {
  const [tools, setTools] = useState<DaolvTool[]>([]);
  const [selTool, setSelTool] = useState<DaolvTool | null>(null);
  // 每个工具独立的表单（切换工具保留，切回恢复）
  const [formsByTool, setFormsByTool] = useState<Record<string, Record<string, string>>>({});
  const [result, setResult] = useState("");
  const [hotels, setHotels] = useState<HotelInfo[] | null>(null); // 搜索酒店结果卡片
  const [resultMsg, setResultMsg] = useState(""); // 返回 message
  const [loading, setLoading] = useState(false);

  // 标签字典
  const [tags, setTags] = useState<TagItem[]>([]);
  const [tagsLoading, setTagsLoading] = useState(false);
  const [selectedTags, setSelectedTags] = useState<string[]>([]); // 酒店标签 Tab 选中
  const [expandedCat, setExpandedCat] = useState<string>("");
  // 标签弹窗（搜索酒店页）
  const [modalOpen, setModalOpen] = useState(false);
  const [modalSelected, setModalSelected] = useState<string[]>([]);
  // 每张酒店卡片的展开详情
  const [openDetailId, setOpenDetailId] = useState<number | null>(null);
  const [detailResult, setDetailResult] = useState<Record<number, string>>({});
  const [detailLoading, setDetailLoading] = useState<number | null>(null);

  // 恢复上次的表单 / 标签 / 查询结果（组件卸载/刷新后切回不丢）
  useEffect(() => {
    try {
      const raw = localStorage.getItem(FORMS_KEY);
      if (raw) setFormsByTool(JSON.parse(raw));
    } catch { /* 忽略损坏缓存 */ }
    try {
      const raw = localStorage.getItem(SELECTED_TAGS_KEY);
      if (raw) setSelectedTags(JSON.parse(raw));
    } catch { /* 忽略损坏缓存 */ }
    try {
      const raw = localStorage.getItem(RESULTS_KEY);
      if (raw) {
        const snap = JSON.parse(raw);
        if (Array.isArray(snap.hotels)) setHotels(snap.hotels);
        if (typeof snap.result === "string") setResult(snap.result);
        if (typeof snap.resultMsg === "string") setResultMsg(snap.resultMsg);
        if (snap.detailResult && typeof snap.detailResult === "object") setDetailResult(snap.detailResult);
      }
    } catch { /* 忽略损坏缓存 */ }
  }, []);

  // 查询结果变更时持久化
  useEffect(() => {
    try {
      localStorage.setItem(RESULTS_KEY, JSON.stringify({ hotels, result, resultMsg, detailResult }));
    } catch { /* 存储满忽略 */ }
  }, [hotels, result, resultMsg, detailResult]);

  useEffect(() => {
    daolvTools().then((t) => {
      if (t?.code === 200 && Array.isArray(t.tools)) {
        const hotelTools = t.tools.filter((x: any) => x.source === "daolv");
        setTools(hotelTools);
        if (hotelTools.length) {
          let saved = "";
          try { saved = localStorage.getItem(TAB_KEY) || ""; } catch { /* 忽略 */ }
          const initial = hotelTools.find((x: any) => x.name === saved)
            || hotelTools.find((x: any) => x.name === "searchHotels")
            || hotelTools[0];
          selectTool(initial);
        }
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const selectTool = (tool: DaolvTool) => {
    setSelTool(tool);
    try { localStorage.setItem(TAB_KEY, tool.name); } catch { /* 忽略 */ }
    if (tool.name === "getHotelSearchTags") {
      // 标签页：加载字典（带缓存）
      loadTags();
    }
  };

  /** 加载标签字典（localStorage 缓存 1 小时） */
  const loadTags = async (): Promise<TagItem[]> => {
    try {
      const raw = localStorage.getItem(DAOLV_TAGS_KEY);
      if (raw) {
        const cached = JSON.parse(raw);
        if (cached && Array.isArray(cached.tags) && cached.time && Date.now() - cached.time < TAGS_TTL_MS) {
          setTags(cached.tags);
          return cached.tags;
        }
      }
    } catch { /* 缓存损坏忽略 */ }
    const tagTool = tools.find((x) => x.name === "getHotelSearchTags");
    if (!tagTool) return [];
    setTagsLoading(true);
    try {
      const res = await daolvCall(tagTool.qualifiedName, {});
      const list = parseTags(res?.result);
      if (list.length) {
        setTags(list);
        try {
          localStorage.setItem(DAOLV_TAGS_KEY, JSON.stringify({ time: Date.now(), tags: list }));
        } catch { /* 存储满忽略 */ }
      }
      return list;
    } finally {
      setTagsLoading(false);
    }
  };

  /** 当前工具的表单（切换工具时各工具独立保存） */
  const form = selTool ? (formsByTool[selTool.qualifiedName] || {}) : {};
  const fields = selTool ? flattenFields(selTool) : [];
  const groups = selTool && selTool.name !== "getHotelSearchTags" ? groupFields(selTool, fields) : [];
  const tagGroups = groupTags(tags);

  /** 更新当前工具表单字段（同步持久化到 localStorage） */
  const updateForm = (path: string, value: string) => {
    if (!selTool) return;
    setFormsByTool((prev) => {
      const next = { ...prev, [selTool.qualifiedName]: { ...(prev[selTool.qualifiedName] || {}), [path]: value } };
      try {
        localStorage.setItem(FORMS_KEY, JSON.stringify(next));
      } catch { /* 存储满忽略 */ }
      return next;
    });
  };

  const run = async () => {
    if (!selTool) return;
    const visibleFields = groups.flatMap((g) => g.items);
    for (const f of visibleFields) {
      if (f.required && !(form[f.path] || "").trim()) {
        setResult(`⚠️ 缺少必填参数：${f.label}`);
        return;
      }
    }
    setLoading(true);
    setResult("");
    setHotels(null);
    const args = buildArgs(visibleFields, form);
    // 隐藏的必填意图字段（综合意图）自动生成，用户无需手填
    const intentField = fields.find((f) => HIDE_KEYWORDS.some((k) => textOf(f).includes(k)));
    if (intentField) {
      args[intentField.path] = buildIntent(visibleFields, form);
    }
    const res = await daolvCall(selTool.qualifiedName, args);
    if (res?.code === 200) {
      setResult(res.result || "");
      setHotels(parseHotels(res.result));
      setResultMsg(parseMessage(res.result));
    } else {
      setResult(res?.msg || "调用失败");
      setHotels(null);
      setResultMsg("");
    }
    setLoading(false);
  };

  // ---- 酒店标签页 ----
  const toggleTag = (name: string) => {
    setSelectedTags((prev) => {
      const next = prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name];
      try { localStorage.setItem(SELECTED_TAGS_KEY, JSON.stringify(next)); } catch { /* 忽略 */ }
      return next;
    });
  };

  const clearSelectedTags = () => {
    setSelectedTags([]);
    try { localStorage.setItem(SELECTED_TAGS_KEY, JSON.stringify([])); } catch { /* 忽略 */ }
  };

  /** 确认填充到搜索页 */
  const confirmToSearch = () => {
    if (!selectedTags.length) return;
    switchToSearchWithTags(selectedTags);
  };

  const switchToSearchWithTags = (tagList: string[]) => {
    const tool = tools.find((x) => x.name === "searchHotels") || tools[0];
    if (!tool) return;
    setSelTool(tool);
    // 保留搜索酒店页已填字段，只更新标签
    const init = { ...(formsByTool[tool.qualifiedName] || {}) };
    const tagField = flattenFields(tool).find(isTagField);
    if (tagField) init[tagField.path] = tagList.join(",");
    setFormsByTool((prev) => {
      const next = { ...prev, [tool.qualifiedName]: init };
      try { localStorage.setItem(FORMS_KEY, JSON.stringify(next)); } catch { /* 忽略 */ }
      return next;
    });
    setResult("");
    setHotels(null);
  };

  // ---- 标签弹窗（搜索酒店页） ----
  const openTagModal = async () => {
    if (!tags.length) await loadTags();
    setModalSelected((form[tagFieldPath()] || "").split(/[，,]/).map((s) => s.trim()).filter(Boolean));
    setModalOpen(true);
  };
  const tagFieldPath = () => {
    const tool = selTool && selTool.name === "searchHotels" ? selTool : tools.find((x) => x.name === "searchHotels");
    if (!tool) return "";
    return flattenFields(tool).find(isTagField)?.path || "";
  };
  const toggleModalTag = (name: string) => {
    setModalSelected((prev) => (prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name]));
  };
  const confirmModal = () => {
    const p = tagFieldPath();
    if (p) updateForm(p, modalSelected.join(","));
    setModalOpen(false);
  };

  /** 在酒店卡片内联加载完整详情（getHotelDetail） */
  const loadDetail = async (hotel: HotelInfo, idx: number) => {
    const detailTool = tools.find((x) => x.name === "getHotelDetail");
    if (!detailTool) {
      setDetailResult((prev) => ({ ...prev, [idx]: "未找到酒店详情工具" }));
      return;
    }
    setDetailLoading(idx);
    const urlParams = parseBookingUrl(hotel.bookingUrl);
    const args = buildDetailArgs(detailTool, hotel, urlParams);
    const detailFields = flattenFields(detailTool);
    const intentField = detailFields.find((f) => HIDE_KEYWORDS.some((k) => textOf(f).includes(k)));
    if (intentField) args[intentField.path] = buildDetailIntent(hotel, urlParams);
    const res = await daolvCall(detailTool.qualifiedName, args);
    setDetailResult((prev) => ({
      ...prev,
      [idx]: res?.code === 200 ? (res.result || "（无返回）") : (res?.msg || "调用失败"),
    }));
    setDetailLoading(null);
  };

  return (
    <div className="daolv-panel">
      <div className="daolv-head">
        <span className="daolv-title">道旅・酒店查询工具</span>
      </div>

      {!tools.length ? (
        <div className="daolv-hint">道旅酒店工具加载中…（未配置或未就绪时为空）</div>
      ) : (
        selTool && (
          <>
            <div className="daolv-tools">
              {tools.map((t) => (
                <button
                  key={t.qualifiedName}
                  className={`btn tool-btn daolv-tool ${selTool.qualifiedName === t.qualifiedName ? "daolv-tool-active" : ""}`}
                  onClick={() => selectTool(t)}
                >
                  {toolLabel(t)}
                </button>
              ))}
            </div>

            {/* ===== 酒店标签页：分类折叠多选 ===== */}
            {selTool.name === "getHotelSearchTags" ? (
              <div className="daolv-tags">
                {tagsLoading && tags.length === 0 && <div className="daolv-hint">正在加载标签字典…</div>}
                {tags.length === 0 && !tagsLoading && (
                  <div className="daolv-hint">标签字典为空，点击「查询」重新加载，或稍后再试</div>
                )}
                {tagGroups.map((g) => {
                  const open = expandedCat === g.category;
                  const checkedCount = g.items.filter((t) => selectedTags.includes(t.name)).length;
                  return (
                    <div className="daolv-tag-group" key={g.category}>
                      <button className="daolv-tag-group-head" onClick={() => setExpandedCat(open ? "" : g.category)}>
                        <span>{g.category}</span>
                        <span className="daolv-tag-count">{checkedCount}/{g.items.length} {open ? "▾" : "▸"}</span>
                      </button>
                      {open && (
                        <div className="daolv-tag-list">
                          {g.items.map((t) => (
                            <label className="daolv-tag-item" key={t.name}>
                              <input
                                type="checkbox"
                                checked={selectedTags.includes(t.name)}
                                onChange={() => toggleTag(t.name)}
                              />
                              <span>{t.name}</span>
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}

                <div className="daolv-tag-actions">
                  <button className="btn tool-btn" onClick={clearSelectedTags}>清空选中</button>
                  <button className="btn send-btn" disabled={!selectedTags.length} onClick={confirmToSearch}>
                    确认填充到搜索页（{selectedTags.length}）
                  </button>
                </div>
                {selectedTags.length > 0 && (
                  <div className="daolv-tag-summary">已选：{selectedTags.join("、")}</div>
                )}
              </div>
            ) : (
              /* ===== 搜索酒店 / 酒店详情：分组表单 ===== */
              <>
                {selTool.description && <div className="daolv-desc">{selTool.description}</div>}

                {groups.map((g) => (
                  <div className="daolv-group" key={g.title}>
                    <div className="daolv-group-title">{g.title}</div>
                    <div className="ofit-form">
                      {g.items.map((f) => (
                        <label className={`ofit-field ${f.required ? "" : "ofit-field-optional"}`} key={f.path}>
                          <span className="ofit-label">
                            {f.label} {f.required ? " *" : ""}
                          </span>
                          {isTagField(f) ? (
                            <div className="daolv-tag-pick">
                              <button className="btn tool-btn" type="button" onClick={openTagModal}>选择标签</button>
                              {form[f.path] && <span className="daolv-tag-echo">已选 {form[f.path].split(/[，,]/).length} 个</span>}
                            </div>
                          ) : (
                            renderField(f, form[f.path] || "", (v) => updateForm(f.path, v))
                          )}
                        </label>
                      ))}
                    </div>
                  </div>
                ))}

                <div className="mg-actions">
                  <button className="btn send-btn" disabled={loading} onClick={run}>
                    {loading ? "查询中…" : "🔍 查询"}
                  </button>
                </div>

                {selTool.name === "searchHotels" && resultMsg && <div className="daolv-hint">📊 {resultMsg}</div>}

                {selTool.name === "searchHotels" && (hotels && hotels.length > 0 ? (
                  <div className="daolv-hotels">
                    {hotels.map((h, i) => (
                      <div className="daolv-hotel-card" key={i}>
                        {h.imageUrl && <img className="daolv-hotel-img" src={normalizeImageUrl(h.imageUrl)} alt={h.name} loading="lazy" referrerPolicy="no-referrer" />}
                        <div className="daolv-hotel-info">
                          <div className="daolv-hotel-name">
                            {h.name || "未命名酒店"}
                            {h.nameEn && <span className="daolv-hotel-en">{h.nameEn}</span>}
                          </div>
                          <div className="daolv-hotel-sub">
                            {h.starRating ? <span className="daolv-hotel-stars">{"★".repeat(Math.max(1, Math.round(h.starRating)))}</span> : null}
                            {h.priceMsg && <span className="daolv-hotel-price">💰 {h.priceMsg}</span>}
                          </div>
                          {h.address && <div className="daolv-hotel-addr">📍 {h.address}{h.distance ? ` · 距目标 ${h.distance} 米` : ""}</div>}
                          {h.tags.length > 0 && (
                            <div className="daolv-hotel-tags">
                              {h.tags.slice(0, 6).map((t) => <span className="mg-word" key={t}>{t}</span>)}
                            </div>
                          )}
                          <div className="daolv-hotel-actions">
                            <button className="btn tool-btn daolv-detail-btn" type="button" onClick={() => setOpenDetailId(openDetailId === i ? null : i)}>
                              {openDetailId === i ? "收起详情" : "查看详情"}
                            </button>
                            {h.bookingUrl && (
                              <a className="daolv-hotel-link" href={h.bookingUrl} target="_blank" rel="noreferrer">去预订 →</a>
                            )}
                          </div>
                          {openDetailId === i && (
                            <div className="daolv-hotel-detail">
                              {h.description && <div className="daolv-hotel-desc">{htmlToText(h.description)}</div>}
                              {h.amenities.length > 0 && (
                                <div className="daolv-hotel-amenities">
                                  <span className="daolv-hotel-amenities-label">设施：</span>
                                  {h.amenities.map((a) => <span className="mg-word" key={a}>{a}</span>)}
                                </div>
                              )}
                              <button className="btn tool-btn" type="button" disabled={detailLoading === i} onClick={() => loadDetail(h, i)}>
                                {detailLoading === i ? "加载中…" : "加载完整详情"}
                              </button>
                              {detailResult[i] && <DetailResult text={detailResult[i]} />}
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  result && <pre className="daolv-result">{formatResult(result)}</pre>
                ))}
              </>
            )}
          </>
        )
      )}

      {/* ===== 标签多选弹窗 ===== */}
      {modalOpen && (
        <div className="daolv-modal">
          <div className="daolv-modal-box">
            <div className="daolv-modal-head">
              <span>选择标签（可多选）</span>
              <button className="btn tool-btn" onClick={() => setModalOpen(false)}>✕</button>
            </div>
            <div className="daolv-modal-body">
              {tags.length === 0 && <div className="daolv-hint">标签字典加载中…</div>}
              {groupTags(tags).map((g) => (
                <div className="daolv-tag-group" key={g.category}>
                  <div className="daolv-tag-group-head">{g.category}</div>
                  <div className="daolv-tag-list">
                    {g.items.map((t) => (
                      <label className="daolv-tag-item" key={t.name}>
                        <input
                          type="checkbox"
                          checked={modalSelected.includes(t.name)}
                          onChange={() => toggleModalTag(t.name)}
                        />
                        <span>{t.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              ))}
            </div>
            <div className="daolv-modal-actions">
              <button className="btn tool-btn" onClick={() => setModalSelected([])}>清空</button>
              <button className="btn send-btn" onClick={confirmModal}>确认（{modalSelected.length}）</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** 图片 URL 规范化：空格编码（URL 中含 "Featured Image" 等带空格路径） */
function normalizeImageUrl(url: string): string {
  return (url || "").trim().replace(/ /g, "%20");
}

/** 解析预订 URL 的查询参数（id/checkInDate/checkOutDate/roomCount/adultCount/childCount） */
function parseBookingUrl(url: string): Record<string, string> {
  const out: Record<string, string> = {};
  try {
    const u = new URL(url);
    u.searchParams.forEach((v, k) => { out[k] = v; });
  } catch { /* 忽略 */ }
  return out;
}

/** HTML 描述 → 纯文本（去掉标签） */
function htmlToText(html: string): string {
  if (!html) return "";
  try {
    const div = document.createElement("div");
    div.innerHTML = html;
    return (div.textContent || "").replace(/\s+/g, " ").trim();
  } catch {
    return html.replace(/<[^>]+>/g, "");
  }
}

/** 详情工具里按关键词找字段路径 */
function findDetailPath(fields: FieldDef[], kws: string[]): string {
  return fields.find((f) => kws.some((k) => textOf(f).includes(k)))?.path || "";
}

/** 从酒店卡片 + 预订 URL 拼 getHotelDetail 参数 */
function buildDetailArgs(tool: DaolvTool, hotel: HotelInfo, urlParams: Record<string, string>): Record<string, unknown> {
  const fields = flattenFields(tool);
  const args: Record<string, unknown> = {};
  const set = (path: string, v: unknown) => {
    if (!path || v === undefined || v === null || v === "") return;
    const dot = path.indexOf(".");
    if (dot >= 0) {
      const p = path.slice(0, dot);
      const c = path.slice(dot + 1);
      const obj = (args[p] as Record<string, unknown>) || {};
      obj[c] = v;
      args[p] = obj;
    } else {
      args[path] = v;
    }
  };
  set(findDetailPath(fields, ["hotelid", "酒店id", "酒店编号"]), hotel.hotelId);
  set(findDetailPath(fields, ["hotelname", "酒店名称", "酒店名"]), hotel.name);
  set(findDetailPath(fields, ["checkin", "入住日期"]), urlParams.checkInDate);
  set(findDetailPath(fields, ["checkout", "离店日期"]), urlParams.checkOutDate);
  set(findDetailPath(fields, ["adult", "成人"]), urlParams.adultCount ? Number(urlParams.adultCount) : 2);
  // 无小孩时不上传 childAgeDetails（避免传 0 触发服务端校验）
  if (urlParams.childCount && Number(urlParams.childCount) > 0) {
    set(findDetailPath(fields, ["child", "儿童", "childcount"]), Number(urlParams.childCount));
  }
  set(findDetailPath(fields, ["room", "房间"]), urlParams.roomCount ? Number(urlParams.roomCount) : 1);
  set(findDetailPath(fields, ["country", "国家"]), "CN");
  set(findDetailPath(fields, ["currency", "币种"]), "CNY");
  return args;
}

/** 详情工具隐藏必填意图字段的自动生成 */
function buildDetailIntent(hotel: HotelInfo, urlParams: Record<string, string>): string {
  const parts: string[] = [];
  if (hotel.name) parts.push(`查看酒店${hotel.name}`);
  if (urlParams.checkInDate) parts.push(`${urlParams.checkInDate}入住`);
  if (urlParams.checkOutDate) parts.push(`${urlParams.checkOutDate}离店`);
  parts.push(`${urlParams.adultCount || "2"}位成人${urlParams.roomCount || "1"}间房`);
  return parts.join("，");
}

/** 常见 key → 中文标签 */
const DETAIL_LABEL_MAP: Record<string, string> = {
  name: "名称", hotelName: "酒店名称", nameEn: "英文名", brand: "品牌",
  address: "地址", starRating: "星级", price: "价格", lowestPrice: "最低价",
  checkInDate: "入住日期", checkOutDate: "离店日期", checkInTime: "入住时间", checkOutTime: "离店时间",
  roomType: "房型", roomTypeName: "房型", roomCount: "房间数", bedType: "床型",
  adultCount: "成人数", childCount: "儿童数", childAgeDetails: "儿童年龄",
  breakfast: "早餐", mealPlan: "餐食", description: "描述", amenities: "设施", tags: "标签",
  phone: "电话", email: "邮箱", website: "官网", countryCode: "国家", currency: "币种",
  imageUrl: "图片", latitude: "纬度", longitude: "经度", distance: "距离", policy: "政策",
};

/** 把嵌套 JSON 拍平成 label/value 行（跳过 success/code/message 元数据与空值） */
function flattenDetailJson(obj: any, prefix = "", out: { label: string; value: string }[] = []): { label: string; value: string }[] {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return out;
  for (const [k, v] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (v === null || v === undefined || v === "") continue;
    if (typeof v === "object" && !Array.isArray(v)) {
      flattenDetailJson(v, path, out);
    } else if (Array.isArray(v)) {
      // 只拍平基本类型数组；对象数组（如房型）由专门区块渲染
      const prims = v.filter((x) => x === null || typeof x !== "object");
      if (prims.length) {
        out.push({ label: path, value: prims.map(String).join("、") });
      }
    } else if (typeof v === "string" && /<[^>]+>/.test(v)) {
      out.push({ label: path, value: htmlToText(v) });
    } else {
      out.push({ label: path, value: String(v) });
    }
  }
  return out;
}

function detailLabel(path: string): string {
  const last = path.split(".").pop() || path;
  return DETAIL_LABEL_MAP[last] || DETAIL_LABEL_MAP[path] || last;
}

/** 完整详情结果渲染：字段行 + 房型卡片；非 JSON 回退原始格式化 */
function DetailResult({ text }: { text: string }) {
  const t = text.trim();
  if (t.startsWith("{") || t.startsWith("[")) {
    try {
      const obj = JSON.parse(t);
      if (obj && typeof obj === "object" && !Array.isArray(obj)) {
        const roomPlans = Array.isArray(obj.roomRatePlans) ? obj.roomRatePlans : [];
        const rows = flattenDetailJson({ ...obj, roomRatePlans: undefined })
          .filter((r) => !["success", "code", "message"].includes(r.label.split(".").pop() || ""))
          .filter((r) => r.label !== "bookingUrl");
        if (rows.length || roomPlans.length) {
          return (
            <div className="daolv-detail-summary">
              {rows.map((r, i) => (
                <div className="daolv-detail-row" key={i}>
                  <span className="daolv-detail-label">{detailLabel(r.label)}</span>
                  <span className="daolv-detail-value">{r.value}</span>
                </div>
              ))}
              {roomPlans.length > 0 && (
                <div className="daolv-roomplans">
                  <div className="daolv-roomplans-title">房型与价格</div>
                  {roomPlans.map((p: any, i: number) => (
                    <div className="daolv-roomplan" key={i}>
                      {p.roomInfo?.images && (
                        <img className="daolv-roomplan-img" src={normalizeImageUrl(String(p.roomInfo.images))} alt={p.roomNameCn || p.roomName} referrerPolicy="no-referrer" />
                      )}
                      <div className="daolv-roomplan-body">
                        <div className="daolv-roomplan-name">{p.roomNameCn || p.roomName || "房型"}</div>
                        <div className="daolv-roomplan-meta">
                          <span className="daolv-roomplan-price">¥{p.averagePrice} {p.currency || "CNY"}</span>
                          {p.bedTypeDescription ? <span> · {p.bedTypeDescription}</span> : null}
                          {p.mealTypeStr ? <span> · {p.mealTypeStr}</span> : null}
                          {p.isOnRequest ? <span className="daolv-roomplan-request"> · 需确认</span> : null}
                        </div>
                        {p.cancelPolicy ? <div className="daolv-roomplan-cancel">{p.cancelPolicy}</div> : null}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        }
      }
    } catch { /* 非 JSON 走回退 */ }
  }
  return <pre className="daolv-result">{formatResult(text)}</pre>;
}

/** 解析返回 JSON 的 message 字段 */
function parseMessage(text: string | undefined): string {
  if (!text) return "";
  try {
    const o = JSON.parse(text);
    return typeof o?.message === "string" ? o.message : "";
  } catch {
    return "";
  }
}

/** 解析 searchHotels 返回 → 酒店卡片列表（非酒店 JSON 返回 null） */
function parseHotels(text: string | undefined): HotelInfo[] | null {
  if (!text) return null;
  const t = text.trim();
  if (!t.startsWith("{") && !t.startsWith("[")) return null;
  try {
    const obj = JSON.parse(t);
    const list = Array.isArray(obj) ? obj : obj?.hotelInformationList;
    if (!Array.isArray(list) || !list.length) return null;
    return list
      .filter((h: any) => h && typeof h === "object")
      .map((h: any) => ({
        hotelId: typeof h.hotelId === "number" ? h.hotelId : Number(h.hotelId) || 0,
        name: String(h.name || ""),
        nameEn: String(h.nameEn || ""),
        starRating: typeof h.starRating === "number" ? h.starRating : undefined,
        priceMsg: String(h.price?.message || ""),
        address: String(h.address || ""),
        distance: h.distanceInMeters,
        tags: Array.isArray(h.tags) ? h.tags.map((x: any) => String(x)) : [],
        imageUrl: String(h.imageUrl || ""),
        bookingUrl: String(h.bookingUrl || ""),
        description: String(h.description || ""),
        amenities: Array.isArray(h.hotelAmenities) ? h.hotelAmenities.map((x: any) => String(x)) : [],
      }));
  } catch {
    return null;
  }
}

/** 解析 getHotelSearchTags 返回 → TagItem[] */
function parseTags(text: string | undefined): TagItem[] {
  if (!text) return [];
  const t = text.trim();
  if (!t.startsWith("{") && !t.startsWith("[")) return [];
  try {
    const obj = JSON.parse(t);
    const arr = Array.isArray(obj) ? obj : (obj.tags as TagItem[]);
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((x) => x && typeof x.name === "string" && x.name)
      .map((x) => ({ name: String(x.name), category: String(x.category || "其他"), description: x.description }));
  } catch {
    return [];
  }
}

/** 展开 schema：嵌套对象拆成「父.子」字段，按描述去重 */
function flattenFields(tool: DaolvTool): FieldDef[] {
  const out: FieldDef[] = [];
  const props = tool.inputSchema?.properties || {};
  const requiredSet = new Set(tool.required || []);
  for (const [key, schema] of Object.entries(props)) {
    if (schema.type === "object" && schema.properties) {
      for (const [sub, subSchema] of Object.entries(schema.properties)) {
        out.push({
          path: `${key}.${sub}`,
          label: subSchema.description || `${key}.${sub}`,
          type: subSchema.type || "string",
          enum: subSchema.enum,
          required: requiredSet.has(key),
        });
      }
    } else {
      out.push({
        path: key,
        label: cleanLabel(key, schema.description || key),
        type: schema.type || "string",
        enum: schema.enum,
        required: requiredSet.has(key),
      });
    }
  }
  const seen = new Set<string>();
  return out.filter((f) => {
    if (seen.has(f.label)) return false;
    seen.add(f.label);
    return true;
  });
}

/** 精简关键字段标签，避免过长的 schema 描述 */
function cleanLabel(key: string, label: string): string {
  if (/placetype/i.test(key)) return "地点类型";
  if (key === "place") return "搜索目标名称";
  if (/country/i.test(key)) return "国家二字码";
  return label;
}

/** 按字段类型渲染输入（日期/数字/枚举/布尔/数组） */
function renderField(f: FieldDef, value: string, onChange: (v: string) => void) {
  const isDate = f.type === "string" && /date|checkin|checkout|入住|离店/i.test(f.path + f.label);
  // place 类型：固定枚举下拉，禁止填地名
  if (/placetype|place\s*的\s*类型/i.test(f.path + f.label)) {
    return (
      <select className="ofit-input" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">— 选择类型 —</option>
        {PLACE_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
      </select>
    );
  }
  if (f.type === "boolean") {
    return (
      <select className="ofit-input" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">—</option>
        <option value="true">是</option>
        <option value="false">否</option>
      </select>
    );
  }
  if (f.enum && f.enum.length) {
    return (
      <select className="ofit-input" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">—</option>
        {f.enum.map((v) => <option key={v} value={v}>{v}</option>)}
      </select>
    );
  }
  return (
    <input
      className="ofit-input"
      value={value}
      type={isDate ? "date" : (f.type === "number" || f.type === "integer" ? "number" : "text")}
      placeholder={f.type === "array" ? "" : ""}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

/** 把扁平表单重建为嵌套 args，并按类型转换；空值不传 */
function buildArgs(fields: FieldDef[], form: Record<string, string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of fields) {
    const v = form[f.path];
    if (!v || v === "") continue;
    const dot = f.path.indexOf(".");
    if (dot >= 0) {
      const parent = f.path.slice(0, dot);
      const child = f.path.slice(dot + 1);
      const obj = (out[parent] as Record<string, unknown>) || {};
      obj[child] = convertValue(f.type, v);
      out[parent] = obj;
    } else {
      out[f.path] = convertValue(f.type, v);
    }
  }
  return out;
}

function convertValue(type: string, v: string): unknown {
  if (type === "number" || type === "integer") {
    const n = Number(v);
    return isNaN(n) ? v : n;
  }
  if (type === "boolean") return v === "true" || v === "1";
  if (type === "array") return v.split(/[，,]/).map((s) => s.trim()).filter(Boolean);
  return v;
}

/** 根据已填字段自动生成 userIntent（综合意图），满足隐藏的必填参数 */
function buildIntent(fields: FieldDef[], form: Record<string, string>): string {
  const get = (kws: string[]) => {
    const f = fields.find((x) => kws.some((k) => textOf(x).includes(k)));
    return f ? (form[f.path] || "").trim() : "";
  };
  const place = get(["目标名称", "place"]) || get(["地点"]);
  const placeType = get(["placetype", "类型"]);
  const checkIn = get(["入住日期", "checkin"]);
  const nights = get(["晚数", "night"]);
  const adults = get(["成人", "adult"]);
  const budget = get(["价格上限", "maxprice"]);
  const stars = get(["星级", "star"]);
  const brand = get(["偏好品牌", "brand"]);
  const distance = get(["直线距离", "distance"]);
  const country = get(["国家", "country"]);
  const limit = get(["数量", "limit"]);
  const tags = get(["命中标签", "requiredtags"]);
  const parts: string[] = [];
  if (adults) parts.push(`${adults}位成人`);
  if (checkIn) parts.push(`${checkIn}入住${nights ? nights + "晚" : ""}`);
  if (place) parts.push(`目的地${place}（${placeType || "未知类型"}）`);
  if (country) parts.push(`中国${country}`);
  if (budget) parts.push(`预算${budget}元/晚`);
  if (stars) parts.push(`${stars}星级`);
  if (brand) parts.push(`${brand}品牌`);
  if (distance) parts.push(`POI周边${distance}米内`);
  if (tags) parts.push(`需包含标签：${tags}`);
  if (limit) parts.push(`返回${limit}条酒店`);
  return parts.length ? parts.join("，") : "用户需要预订酒店";
}

/** 结果若是 JSON 则美化缩进 */
function formatResult(text: string): string {
  if (!text) return "";
  const t = text.trim();
  if (t.startsWith("{") || t.startsWith("[")) {
    try {
      return JSON.stringify(JSON.parse(t), null, 2);
    } catch { /* 非合法 JSON，原样 */ }
  }
  return text;
}
