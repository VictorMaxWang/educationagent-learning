import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
} from "react";

/** 图片条目：真实路径（Tauri 原生对话框/拖拽）或 base64（浏览器 dev 兜底） */
type ImageEntry =
  | { kind: "path"; path: string; preview: string }
  | { kind: "base64"; name: string; dataUrl: string };

const MAX_IMAGES = 9;
import { API_BASE_URL as BASE_URL } from '../api/baseUrl';
/**
 * 桌面端统一前缀：VITE_API_BASE_URL 是纯源（http://127.0.0.1:8888），
 * 桌面端接口统一挂在 /api/desktop 下（与 travel/request.ts、chatApi.js 一致）。
 * 缺了它后端 404，且落在 CORS 白名单（/api/**）之外 → 浏览器拦截 preflight → 请求失败。
 */
const DESKTOP_BASE: string = `${BASE_URL}/api/desktop`;

/** Tauri 运行环境判断（与 utils/file.js 一致） */
const isTauri = (): boolean => !!(window as any).__TAURI_INTERNALS__;

/** 通用 JSON POST，失败保持 {code:500, msg} 结构 */
async function postJson(url: string, body: unknown): Promise<any> {
  try {
    const resp = await fetch(`${DESKTOP_BASE}${url}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return await resp.json();
  } catch (e: any) {
    return { code: 500, msg: `请求失败：${e?.message ?? e}` };
  }
}

/** 剥掉 data:image/xxx;base64, 前缀，拿纯 base64 */
function stripDataUrl(dataUrl: string): string {
  const i = dataUrl.indexOf(",");
  return i >= 0 ? dataUrl.slice(i + 1) : dataUrl;
}

/**
 * 生活企划 → 小红书发布
 * 桌面端直连小红书 MCP：填标题/正文/标签 → 选本地图片（原生对话框 / 拖拽）
 * → 调后端 /api/desktop/life/xhs/publish 一键发布图文笔记。
 */
export default function LifeView({ module: _module = "xhs" }: { module?: string }) {
  const [title, setTitle] = useState("");
  const [desc, setDesc] = useState("");
  const [tags, setTags] = useState("");
  const [images, setImages] = useState<ImageEntry[]>([]);
  const [login, setLogin] = useState<{ checking: boolean; loggedIn: boolean; detail: string }>({
    checking: true,
    loggedIn: false,
    detail: "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState("");
  const [dragOver, setDragOver] = useState(false);
  // 定时发布（热门视频汇总）
  const [hot, setHot] = useState<{ enabled: boolean; days: number[]; time: string; nextRun: string }>({
    enabled: false,
    days: [],
    time: "14:00",
    nextRun: "",
  });
  const [hotPreview, setHotPreview] = useState<any[] | null>(null);
  const [hotError, setHotError] = useState("");
  const [hotBusy, setHotBusy] = useState(false);
  const [hotSaving, setHotSaving] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const unlistenRef = useRef<(() => void) | undefined>(undefined);

  /** 查询小红书登录状态 */
  const checkLogin = async () => {
    setLogin((p) => ({ ...p, checking: true }));
    const res = await postJson("/life/xhs/login-status", {});
    setLogin({
      checking: false,
      loggedIn: !!res?.loggedIn,
      detail: res?.result || res?.msg || "",
    });
  };

  /** 真实路径 → 条目（逐个取 base64 预览） */
  const addPaths = async (paths: string[]) => {
    const valid = paths
      .map((p) => (typeof p === "string" ? p.trim() : ""))
      .filter((p) => p !== "");
    if (!valid.length) return;
    const entries: ImageEntry[] = [];
    if (isTauri()) {
      try {
        const { invoke } = await import("@tauri-apps/api/core");
        for (const p of valid) {
          let preview = "";
          try {
            preview = await invoke("read_image_preview", { path: p });
          } catch {
            /* 预览失败不阻断选图 */
          }
          entries.push({ kind: "path", path: p, preview });
        }
      } catch {
        /* invoke 不可用则退化 */
      }
    }
    if (!entries.length) {
      for (const p of valid) entries.push({ kind: "path", path: p, preview: "" });
    }
    setImages((prev) => [...prev, ...entries].slice(0, MAX_IMAGES));
  };

  /** 浏览器 File 列表 → base64 条目 */
  const addFiles = (files: FileList | File[]) => {
    const list = Array.from(files || []);
    for (const f of list) {
      if (!f.type.startsWith("image/")) continue;
      const reader = new FileReader();
      reader.onload = () => {
        const dataUrl = reader.result as string;
        setImages((prev) => {
          if (prev.length >= MAX_IMAGES) return prev;
          return [...prev, { kind: "base64", name: f.name, dataUrl }];
        });
      };
      reader.readAsDataURL(f);
    }
  };

  /** 选择图片：Tauri 弹原生多选对话框，浏览器走隐藏 file input */
  const pickImages = async () => {
    if (isTauri()) {
      try {
        const { invoke } = await import("@tauri-apps/api/core");
        const picks: { path: string; preview: string }[] = await invoke("pick_images");
        const entries: ImageEntry[] = (picks || [])
          .filter((p) => p && p.path)
          .map((p) => ({ kind: "path", path: p.path, preview: p.preview || "" }));
        setImages((prev) => [...prev, ...entries].slice(0, MAX_IMAGES));
      } catch (e: any) {
        setError("选择图片失败：" + (e?.message ?? e));
      }
    } else {
      fileInputRef.current?.click();
    }
  };

  const onInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    e.target.value = "";
    if (files?.length) addFiles(files);
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const files = e.dataTransfer?.files;
    if (files?.length) addFiles(files);
  };

  const removeImage = (idx: number) => setImages((prev) => prev.filter((_, i) => i !== idx));

  /** 发布：路径直接收集；base64 先暂存换路径 → 调发布接口 */
  const publish = async () => {
    if (!title.trim() && !desc.trim()) {
      setError("请填写标题或正文");
      return;
    }
    if (!images.length) {
      setError("请选择至少一张图片");
      return;
    }
    setBusy(true);
    setError("");
    setResult("");
    try {
      let paths: string[] = images
        .filter((i): i is Extract<ImageEntry, { kind: "path" }> => i.kind === "path")
        .map((i) => i.path);
      const b64s = images
        .filter((i) => i.kind === "base64")
        .map((i) => stripDataUrl((i as Extract<ImageEntry, { kind: "base64" }>).dataUrl));
      if (b64s.length) {
        const prep = await postJson("/life/xhs/prepare-images", { images: b64s });
        if (prep?.code !== 200 || !Array.isArray(prep?.paths) || !prep.paths.length) {
          setError(prep?.msg || "图片暂存失败");
          return;
        }
        paths = paths.concat(prep.paths as string[]);
      }
      const res = await postJson("/life/xhs/publish", {
        title: title.trim(),
        desc: desc.trim(),
        tags: tags.trim(),
        images: paths,
      });
      setResult(res?.result || res?.msg || "已提交发布");
      if (res?.code !== 200 && res?.msg) setError(res.msg);
    } finally {
      setBusy(false);
    }
  };

  // ==================== 定时发布（每周热门视频汇总） ====================

  /** 拉取定时发布状态 */
  const loadHotStatus = async () => {
    const res = await postJson("/life/xhs/hot-trend/status", {});
    if (res?.code === 200) {
      setHot({
        enabled: !!res.enabled,
        days: Array.isArray(res.days) ? res.days : [],
        time: res.time || "14:00",
        nextRun: res.nextRun || "",
      });
    }
  };

  /** 保存定时发布设置（周几 + 时分 + 开关） */
  const saveHotSchedule = async () => {
    if (!hot.days.length) {
      setError("请至少选择一个星期几");
      return;
    }
    setHotSaving(true);
    setError("");
    try {
      const res = await postJson("/life/xhs/hot-trend/schedule", {
        days: hot.days,
        time: hot.time,
        enabled: hot.enabled,
      });
      if (res?.code === 200) {
        setHot({
          enabled: !!res.enabled,
          days: res.days || [],
          time: res.time || "14:00",
          nextRun: res.nextRun || "",
        });
      } else {
        setError(res?.msg || "保存失败");
      }
    } finally {
      setHotSaving(false);
    }
  };

  /** 周几 chips 多选切换 */
  const toggleHotDay = (d: number) => {
    setHot((prev) => {
      const days = prev.days.includes(d) ? prev.days.filter((x) => x !== d) : [...prev.days, d].sort((a, b) => a - b);
      return { ...prev, days };
    });
  };

  /** 预览首页热门视频（不发布，自动加载常驻） */
  const doHotPreview = async () => {
    setHotPreview(null);
    setHotError("");
    try {
      const res = await postJson("/life/xhs/hot-trend/preview", {});
      if (res?.code === 200) {
        setHotPreview(res.videos || []);
        if (!res.videos?.length) setHotError("首页暂无视频内容");
      } else {
        setHotError(res?.msg || "预览失败");
      }
    } catch (e: any) {
      setHotError(`加载失败：${e?.message ?? e}`);
    }
  };

  /** 立即发布热门视频汇总 */
  const doHotPublish = async () => {
    setHotBusy(true);
    setError("");
    setResult("");
    try {
      const res = await postJson("/life/xhs/hot-trend/publish", {});
      setResult(res?.result || res?.msg || "已提交");
      if (res?.code !== 200 && res?.msg) setError(res.msg);
    } finally {
      setHotBusy(false);
    }
  };

  // 每 30 秒刷新定时发布状态（自动发布后 nextRun 会推进，避免面板显示旧值）
  useEffect(() => {
    const t = window.setInterval(() => {
      void loadHotStatus();
    }, 30_000);
    return () => window.clearInterval(t);
  }, []);

  // 首次挂载：检查登录状态 + 定时发布状态 + 注册 Tauri 拖拽 + 全局防页面跳转
  useEffect(() => {
    void checkLogin();
    void loadHotStatus();
    void doHotPreview();

    // 全局防拖拽：文件拖到拖拽框之外时阻止浏览器默认"打开文件"跳走
    const prevent = (e: Event) => e.preventDefault();
    window.addEventListener("dragover", prevent);
    window.addEventListener("drop", prevent);

    if (isTauri()) {
      let cancelled = false;
      import("@tauri-apps/api/webview")
        .then(({ getCurrentWebview }) =>
          getCurrentWebview()
            .onDragDropEvent((event) => {
              const t = event.payload.type;
              if (t === "enter" || t === "over") setDragOver(true);
              else if (t === "leave") setDragOver(false);
              else if (t === "drop") {
                setDragOver(false);
                void addPaths(event.payload.paths ?? []);
              }
            })
            .then((fn) => {
              if (!cancelled) unlistenRef.current = fn;
            })
            .catch(() => {}),
        )
        .catch(() => {});
      return () => {
        cancelled = true;
        unlistenRef.current?.();
        window.removeEventListener("dragover", prevent);
        window.removeEventListener("drop", prevent);
      };
    }
    return () => {
      window.removeEventListener("dragover", prevent);
      window.removeEventListener("drop", prevent);
    };
  }, []);

  return (
    <div className="quiz-container quiz-scroll">
      <div className="life-card">
        {/* 头部：标题 + 登录状态 */}
        <div className="life-head">
          <div>
            <h3 className="life-card-title">🌱 小红书发布</h3>
            <p className="life-sub">桌面端直连小红书 MCP：填好内容、选图后一键发布图文笔记</p>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span
              className={`life-status ${login.checking ? "checking" : login.loggedIn ? "ok" : "bad"}`}
              title={login.detail || undefined}
            >
              {login.checking ? "⏳ 检查登录…" : login.loggedIn ? "✓ 已登录" : "✗ 未登录"}
            </span>
            <button className="life-status-refresh" onClick={checkLogin} title="刷新登录状态">
              刷新
            </button>
          </div>
        </div>

        {/* 🔥 热门视频 TOP10：自动加载，常驻展示 */}
        <div className="life-hot-preview">
          <div className="life-hint">🔥 首页热门视频 TOP 10（预览，未发布，封面将 AI 生成）：</div>
          {hotError ? (
            <div className="life-hint" style={{ color: "#DC2626" }}>⚠️ {hotError}</div>
          ) : hotPreview === null ? (
            <div className="life-hint" style={{ color: "var(--text-faint)" }}>⏳ 加载中…</div>
          ) : hotPreview.length === 0 ? (
            <div className="life-hint" style={{ color: "var(--text-faint)" }}>暂无热门视频</div>
          ) : (
            <div className="life-hot-list">
              {hotPreview.slice(0, 10).map((v: any, i: number) => (
                <div className={`life-hot-item ${i < 3 ? "top" : ""}`} key={i}>
                  <span className="life-hot-rank">{i + 1}</span>
                  <span className="life-hot-title">{v.title || "(无标题)"}</span>
                  <span className="life-hot-meta">
                    {v.author ? `@${v.author} ` : ""}赞{v.likedCount || 0} 藏{v.collectedCount || 0}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 标题 */}
        <div className="life-block">
          <div className="life-block-title">标题</div>
          <div className="life-block-box">
            <input
              className="life-input"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="吸引人的标题，可带 emoji，如：📸 周末 citywalk 打卡"
              maxLength={100}
            />
          </div>
        </div>

        {/* 正文 */}
        <div className="life-block">
          <div className="life-block-title">正文</div>
          <div className="life-block-box">
            <textarea
              className="life-textarea"
              value={desc}
              onChange={(e) => setDesc(e.target.value)}
              placeholder="一段话即可，语气轻松真实，如：今天去了xx，真的超好拍…"
            />
          </div>
        </div>

        {/* 标签 */}
        <div className="life-block">
          <div className="life-block-title">标签</div>
          <div className="life-block-box">
            <input
              className="life-input"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              placeholder="#旅行 #美食（空格分隔）"
            />
          </div>
        </div>

        {/* 图片选择 */}
        <div className="life-block">
          <div className="life-block-title">图片（{images.length}/{MAX_IMAGES}）</div>
          <div className="life-block-box">
            <div
              className={`convert-dropzone life-dropzone-compact ${dragOver ? "dragover" : ""}`}
              onClick={pickImages}
              onDragOver={(e) => {
                e.preventDefault();
                setDragOver(true);
              }}
              onDragLeave={() => setDragOver(false)}
              onDrop={onDrop}
              title="点击选择 或 拖拽图片"
            >
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept="image/*"
                style={{ display: "none" }}
                onChange={onInputChange}
              />
              <div className="convert-drop-icon">🖼️</div>
              <div className="convert-drop-title">
                {isTauri() ? "点击选择图片（原生对话框）" : "点击选择 或 拖拽图片"}
              </div>
              <div className="convert-drop-meta">支持多选，最多 {MAX_IMAGES} 张；拖拽图片到此区域也可添加</div>
            </div>

            {images.length > 0 && (
              <div className="life-image-grid">
                {images.map((img, idx) => (
                  <div
                    className="life-image-item"
                    key={img.kind === "path" ? img.path : img.name + idx}
                  >
                    {img.kind === "base64" ? (
                      <img src={img.dataUrl} alt="图片" />
                    ) : img.preview ? (
                      <img src={img.preview} alt="图片" />
                    ) : (
                      <div className="life-img-empty">🖼️</div>
                    )}
                    <button
                      className="life-img-remove"
                      onClick={(e) => {
                        e.stopPropagation();
                        removeImage(idx);
                      }}
                      title="移除"
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* 发布 */}
        <button
          className="quiz-btn primary convert-submit"
          onClick={publish}
          disabled={busy || login.checking}
        >
          {busy ? "⏳ 发布中…" : "📕 一键发布"}
        </button>

        {error && <div className="convert-error">⚠️ {error}</div>}
        {result && (
          <div className="convert-result">
            <div className="life-result">{result}</div>
          </div>
        )}

        {/* ⏰ 定时发布：每周热门视频汇总 */}
        <div className="life-block">
          <div className="life-block-title">
            <span>⏰ 定时发布 · 每周热门视频汇总</span>
            <button
              className={`life-toggle ${hot.enabled ? "on" : ""}`}
              onClick={() => setHot((prev) => ({ ...prev, enabled: !prev.enabled }))}
              title={hot.enabled ? "点击停用" : "点击启用"}
            >
              {hot.enabled ? "🟢 已启用" : "⚪ 已停用"}
            </button>
          </div>
          <div className="life-block-box">
            <div className="life-sched-row">
              <span className="life-hint">选择发布星期（可多选）</span>
              <div className="life-days">
                {(["一", "二", "三", "四", "五", "六", "日"] as const).map((label, idx) => {
                  const d = idx + 1;
                  return (
                    <button
                      key={d}
                      className={`life-day-chip ${hot.days.includes(d) ? "active" : ""}`}
                      onClick={() => toggleHotDay(d)}
                    >
                      周{label}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="life-sched-row">
              <span className="life-hint">选择发布时刻</span>
              <input
                type="time"
                className="life-input life-time-input"
                value={hot.time}
                onChange={(e) => setHot((prev) => ({ ...prev, time: e.target.value }))}
              />
            </div>

            {hot.nextRun && <div className="life-hint">📅 下次执行：{hot.nextRun}</div>}

            <div className="life-actions">
              <button className="quiz-btn" onClick={saveHotSchedule} disabled={hotSaving}>
                {hotSaving ? "保存中…" : "💾 保存设置"}
              </button>
              <button className="quiz-btn primary" onClick={doHotPublish} disabled={hotBusy}>
                {hotBusy ? "发布中…" : "🚀 立即发布"}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
