import { useCallback, useEffect, useRef, useState } from "react";
import {
  maimemoGenerate, maimemoSyncWords,
  maimemoImport, maimemoAddWords, maimemoGetWords,
  maimemoToggleNotebook, maimemoUpdateWordStatus,
} from "../../request";

/**
 * 词库管理（原「词库生成」升级）
 * 4 个页签：生成 / 导入 / 自定义 / 词库列表（含生词本）
 */
type Tab = "gen" | "import" | "custom" | "list";

type Word = {
  id?: number;
  word: string;
  phonetic?: string;
  meaning?: string;
  partOfSpeech?: string;
  example?: string;
  status?: string;
  bookType?: string;
  inNotebook?: number;
};

type SyncResult = { found?: number; total?: number; notFound?: number; error?: string };

type GenResult = {
  code?: number;
  msg?: string;
  words?: { word: string }[];
  syncResult?: SyncResult;
  bookType?: string;
};

const BOOK_TYPES = [
  { code: "cet4", label: "四级" },
  { code: "cet6", label: "六级" },
  { code: "kaoyan", label: "考研英一" },
  { code: "ielts", label: "雅思高频词" },
];
const STATUS_OPTS = ["new", "mastered", "weak", "forgotten"];
const DIFFICULTIES = ["基础", "中等", "难"];

export default function MaimemoWordManage() {
  const [tab, setTab] = useState<Tab>("gen");
  return (
    <div className="wm-wrap">
      <div className="wm-tabs">
        {([
          ["gen", "✨ 生成"],
          ["import", "📥 导入"],
          ["custom", "✏️ 自定义"],
          ["list", "📚 词库列表"],
        ] as [Tab, string][]).map(([k, label]) => (
          <button key={k} className={`wm-tab${tab === k ? " active" : ""}`} onClick={() => setTab(k)}>
            {label}
          </button>
        ))}
      </div>
      {tab === "gen" && <GenTab />}
      {tab === "import" && <ImportTab />}
      {tab === "custom" && <CustomTab />}
      {tab === "list" && <ListTab />}
    </div>
  );
}

/** ===== 生成：AI 按考纲批量生成 ===== */
function GenTab() {
  const [bookType, setBookType] = useState("cet4");
  const [count, setCount] = useState(50);
  const [difficulty, setDifficulty] = useState("中等");
  const [withExamples, setWithExamples] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [result, setResult] = useState<GenResult | null>(null);

  const handleGenerate = async () => {
    if (generating) return;
    setGenerating(true);
    try {
      const n = Math.max(1, Math.min(count || 30, 100));
      const res = await maimemoGenerate({ bookType, count: n, difficulty, withExamples });
      setResult(res);
    } catch (e) {
      console.error("[Maimemo] 生成请求失败:", e);
    } finally {
      setGenerating(false);
    }
  };

  const handleSync = async () => {
    if (!result?.words?.length || syncing) return;
    setSyncing(true);
    try {
      const words = result.words.map((w) => w.word);
      const sr = await maimemoSyncWords(result.bookType || bookType, words);
      setResult((r) => (r ? { ...r, syncResult: sr } : r));
    } catch (e) {
      console.error("[Maimemo] 同步失败:", e);
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div className="wm-card">
      <div className="wm-gen-form">
        <div className="wm-field">
          <label>词库</label>
          <select value={bookType} onChange={(e) => setBookType(e.target.value)}>
            {BOOK_TYPES.map((b) => (
              <option key={b.code} value={b.code}>{b.label}</option>
            ))}
          </select>
        </div>
        <div className="wm-field">
          <label>数量</label>
          <input type="number" value={count} min={1} max={100} onChange={(e) => setCount(Number(e.target.value))} />
        </div>
        <div className="wm-field">
          <label>难度</label>
          <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)}>
            {DIFFICULTIES.map((d) => (
              <option key={d} value={d}>{d}</option>
            ))}
          </select>
        </div>
        <label className="wm-check">
          <input type="checkbox" checked={withExamples} onChange={(e) => setWithExamples(e.target.checked)} /> 含例句
        </label>
        <button className="wm-btn primary" onClick={handleGenerate} disabled={generating}>
          {generating ? "✨ 生成中..." : "🚀 生成词库"}
        </button>
        {!!result?.words?.length && (
          <button className="wm-btn" onClick={handleSync} disabled={syncing}>
            {syncing ? "加入中..." : "📤 加入云词本"}
          </button>
        )}
      </div>
      {result?.msg && <div className="wm-msg">{result.msg}</div>}
      {result?.syncResult && <div className="wm-msg">墨墨云词本：{JSON.stringify(result.syncResult)}</div>}
    </div>
  );
}

/** ===== 导入：文本 / TXT 文件 ===== */
function ImportTab() {
  const [bookType, setBookType] = useState("cet4");
  const [content, setContent] = useState("");
  const [importing, setImporting] = useState(false);
  const [msg, setMsg] = useState("");
  const [dragOver, setDragOver] = useState(false); // 拖拽导入 TXT 高亮
  const fileRef = useRef<HTMLInputElement>(null);

  const doImport = async (text: string) => {
    if (!text.trim()) {
      setMsg("导入内容为空");
      return;
    }
    setImporting(true);
    try {
      const res = await maimemoImport(bookType, text);
      setMsg(res?.msg || "导入完成");
      setContent("");
    } catch (e) {
      console.error("[Maimemo] 导入失败:", e);
      setMsg("导入失败，请检查后端服务");
    } finally {
      setImporting(false);
    }
  };

  /** 读取 TXT/CSV 文件文本并导入（点击选择 / 拖拽 共用） */
  const readFileText = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => doImport(String(reader.result || ""));
    reader.readAsText(file);
  };

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (file) readFileText(file);
  };

  // 拖拽上传：整张导入卡片作为投放区
  const onDragOver = (e: React.DragEvent) => { e.preventDefault(); setDragOver(true); };
  const onDragLeave = () => setDragOver(false);
  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer?.files?.[0];
    if (file) readFileText(file);
  };

  return (
    <div
      className={`wm-card${dragOver ? " wm-card-dragover" : ""}`}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      <div className="wm-field">
        <label>目标词库</label>
        <select value={bookType} onChange={(e) => setBookType(e.target.value)}>
          {BOOK_TYPES.map((b) => (
            <option key={b.code} value={b.code}>{b.label}</option>
          ))}
        </select>
      </div>
      <textarea
        className="wm-textarea"
        rows={8}
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder={"每行一个单词，或：word|释义|音标\n例：\napple|苹果\nbanana|香蕉|/bəˈnɑːnə/"}
      />
      <div className="wm-row">
        <button className="wm-btn primary" onClick={() => doImport(content)} disabled={importing}>
          {importing ? "导入中..." : "📥 导入文本"}
        </button>
        <button className="wm-btn" onClick={() => fileRef.current?.click()}>📄 选择 TXT 文件（或拖拽到此处）</button>
        <input ref={fileRef} type="file" accept=".txt,.csv" style={{ display: "none" }} onChange={handleFile} />
      </div>
      {msg && <div className="wm-msg">{msg}</div>}
    </div>
  );
}

/** ===== 自定义：手动多行添加 ===== */
function CustomTab() {
  const [text, setText] = useState("");
  const [sync, setSync] = useState(false);
  const [adding, setAdding] = useState(false);
  const [msg, setMsg] = useState("");

  const handleAdd = async () => {
    const words = text.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    if (!words.length) return;
    setAdding(true);
    try {
      const res = await maimemoAddWords(words, sync, "local");
      setMsg(res?.msg || "已加入");
      setText("");
    } catch (e) {
      console.error("[Maimemo] 添加失败:", e);
      setMsg("添加失败");
    } finally {
      setAdding(false);
    }
  };

  return (
    <div className="wm-card">
      <textarea
        className="wm-textarea"
        rows={8}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={"每行一个单词，手动添加：\nhello\nworld"}
      />
      <label className="wm-check">
        <input type="checkbox" checked={sync} onChange={(e) => setSync(e.target.checked)} /> 同时加入墨墨云词本
      </label>
      <button className="wm-btn primary" onClick={handleAdd} disabled={adding}>
        {adding ? "加入中..." : "✏️ 加入词库"}
      </button>
      {msg && <div className="wm-msg">{msg}</div>}
    </div>
  );
}

/** ===== 词库列表：过滤 + 生词本 + 状态管理 ===== */
function ListTab() {
  const [bookType, setBookType] = useState("");
  const [status, setStatus] = useState("");
  const [notebook, setNotebook] = useState<"" | "1">("");
  const [words, setWords] = useState<Word[]>([]);
  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await maimemoGetWords({
        bookType: bookType || undefined,
        status: status || undefined,
        notebook: notebook === "" ? undefined : Number(notebook),
      });
      setWords((res?.words as Word[]) || []);
    } catch (e) {
      console.error("[Maimemo] 列表加载失败:", e);
      setMsg("加载失败");
    } finally {
      setLoading(false);
    }
  }, [bookType, status, notebook]);

  useEffect(() => {
    load();
  }, [load]);

  const toggleNotebook = async (w: Word) => {
    const target = !w.inNotebook;
    try {
      const res = await maimemoToggleNotebook(w.word, target);
      if (res?.code === 200) load();
    } catch (e) {
      console.error(e);
    }
  };

  const markStatus = async (w: Word, st: string) => {
    if (!w.id) return;
    try {
      await maimemoUpdateWordStatus(w.id, st);
      load();
    } catch (e) {
      console.error(e);
    }
  };

  return (
    <div className="wm-card">
      <div className="wm-row">
        <select value={bookType} onChange={(e) => setBookType(e.target.value)}>
          <option value="">全部词库</option>
          {BOOK_TYPES.map((b) => (
            <option key={b.code} value={b.code}>{b.label}</option>
          ))}
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">全部状态</option>
          {STATUS_OPTS.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <select value={notebook} onChange={(e) => setNotebook(e.target.value as "" | "1")}>
          <option value="">全部</option>
          <option value="1">仅生词本</option>
        </select>
        <button className="wm-btn primary" onClick={load} disabled={loading}>
          {loading ? "加载中..." : "刷新"}
        </button>
      </div>

      {words.length === 0 ? (
        <div className="wm-empty">词库为空，去「生成 / 导入 / 自定义」添加单词吧</div>
      ) : (
        <div className="wm-list">
          {words.map((w) => (
            <div className="wm-item" key={w.id ?? w.word}>
              <div className="wm-item-head">
                <span className="wm-word">{w.word}</span>
                {w.phonetic && <span className="wm-phonetic">{w.phonetic}</span>}
                <span className="wm-tag">{w.bookType || "—"}</span>
                <span className={`wm-tag status-${w.status}`}>{w.status}</span>
                {!!w.inNotebook && <span className="wm-tag nb">📒 生词本</span>}
              </div>
              {w.meaning && <div className="wm-meaning">{w.meaning}</div>}
              <div className="wm-item-ops">
                <button className="wm-mini" onClick={() => toggleNotebook(w)}>
                  {w.inNotebook ? "移出生词本" : "加入生词本"}
                </button>
                <select className="wm-mini" value={w.status} onChange={(e) => markStatus(w, e.target.value)}>
                  {STATUS_OPTS.map((s) => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                </select>
              </div>
            </div>
          ))}
        </div>
      )}
      {msg && <div className="wm-msg">{msg}</div>}
    </div>
  );
}
