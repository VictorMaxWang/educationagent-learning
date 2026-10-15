import { useState } from "react";
import { maimemoLookupWord, maimemoLookupText, maimemoAddWords, maimemoToggleNotebook } from "../../request";

type WordDef = {
  word: string;
  phonetic?: string;
  ukPhonetic?: string;
  usPhonetic?: string;
  meaning?: string;
  partOfSpeech?: string;
  example?: string;
  inLibrary?: boolean;
  inNotebook?: boolean;
  source?: "local" | "ai";
  error?: string;
};

/** 有道免费发音（media 元素播放，无需 CORS）：type=1 英音，type=2 美音 */
function playVoice(word: string, type: 1 | 2) {
  const url = `https://dict.youdao.com/dictvoice?audio=${encodeURIComponent(word)}&type=${type}`;
  new Audio(url)
    .play()
    .catch(() => console.warn("发音播放失败（网络受限？）", url));
}

/**
 * 🔍 查生词 — 单词查释义 + 文本批量提取（M3）。
 * 英/美音标 + 发音播放 + 一键加入词库/生词本 + 同步墨墨。
 */
export default function MaimemoLookup() {
  const [mode, setMode] = useState<"single" | "batch">("single");
  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState("");
  const [resultMsg, setResultMsg] = useState("");

  // 单查
  const [word, setWord] = useState("");
  const [single, setSingle] = useState<WordDef | null>(null);
  const [autoNotebook, setAutoNotebook] = useState(false); // 查词自动加入生词本

  // 批量
  const [text, setText] = useState("");
  const [words, setWords] = useState<WordDef[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [syncToMaimemo, setSyncToMaimemo] = useState(false);

  const toggleSelect = (w: string) => {
    setSelected((prev) => {
      const s = new Set(prev);
      if (s.has(w)) s.delete(w);
      else s.add(w);
      return s;
    });
  };

  const selectAll = () => {
    setSelected((prev) => {
      if (words.length && prev.size === words.length) return new Set();
      return new Set(words.map((w) => w.word));
    });
  };

  const doLookup = async () => {
    if (!word.trim()) return;
    setLoading(true);
    setMsg("");
    setResultMsg("");
    try {
      const res = await maimemoLookupWord(word.trim());
      if (res?.code === 200) {
        setSingle(res);
        // 查词自动加入生词本
        if (autoNotebook && !res.inNotebook) {
          await maimemoToggleNotebook(res.word, true).catch(() => {});
          setSingle({ ...res, inNotebook: true });
        }
        setMsg("");
      } else {
        setSingle(null);
        setMsg(res?.msg || "❌ 查询失败");
      }
    } catch (e) {
      setMsg(`❌ 请求失败：${e}`);
    } finally {
      setLoading(false);
    }
  };

  const doExtract = async () => {
    if (!text.trim()) return;
    setLoading(true);
    setMsg("");
    setResultMsg("");
    try {
      const res = await maimemoLookupText(text, 30);
      if (res?.code === 200) {
        setWords(res.words || []);
        setSelected(new Set());
        setMsg(res.msg || "");
      } else {
        setWords([]);
        setMsg(res?.msg || "❌ 提取失败");
      }
    } catch (e) {
      setMsg(`❌ 请求失败：${e}`);
    } finally {
      setLoading(false);
    }
  };

  const doAdd = async (target: string[]) => {
    if (!target.length) return;
    setLoading(true);
    setResultMsg("");
    try {
      const res = await maimemoAddWords(target, syncToMaimemo, "ocr");
      if (res?.code === 200) {
        setResultMsg(res.msg || "已加入");
        if (mode === "single" && single) setSingle({ ...single, inLibrary: true });
        else setWords((prev) => prev.map((w) => (target.includes(w.word) ? { ...w, inLibrary: true } : w)));
      } else {
        setResultMsg(res?.msg || "❌ 加入失败");
      }
    } catch (e) {
      setResultMsg(`❌ 请求失败：${e}`);
    } finally {
      setLoading(false);
    }
  };

  /** 加入/移出生词本 */
  const doNotebook = async (w: WordDef) => {
    const target = !w.inNotebook;
    try {
      const res = await maimemoToggleNotebook(w.word, target);
      if (res?.code === 200) {
        if (mode === "single" && single?.word === w.word) setSingle({ ...single, inNotebook: target });
        else setWords((prev) => prev.map((x) => (x.word === w.word ? { ...x, inNotebook: target } : x)));
      }
    } catch (e) {
      console.error(e);
    }
  };

  const card = (w: WordDef) => (
    <div className="lk-card">
      <div className="lk-card-head">
        <span className="lk-word">{w.word}</span>
        <span className="lk-voice" onClick={() => playVoice(w.word, 1)} title="英音发音">
          🔊英
        </span>
        <span className="lk-voice" onClick={() => playVoice(w.word, 2)} title="美音发音">
          🔊美
        </span>
        {w.phonetic && <span className="lk-phonetic">{w.phonetic}</span>}
        {w.inLibrary && <span className="lk-badge">已入库</span>}
        {!!w.inNotebook && <span className="lk-badge">📒 生词本</span>}
        {!w.inLibrary && w.source === "ai" && <span className="lk-badge lk-badge-ai">✨ AI 生成</span>}
        {w.error && <span className="lk-badge lk-badge-err">{w.error}</span>}
      </div>
      {(w.ukPhonetic || w.usPhonetic) && (
        <div className="lk-phonetics">
          {w.ukPhonetic && <span className="lk-ipa">英 {w.ukPhonetic}</span>}
          {w.usPhonetic && <span className="lk-ipa">美 {w.usPhonetic}</span>}
        </div>
      )}
      {w.meaning && (
        <div className="lk-meaning">
          <span className="mg-word-pos">{w.partOfSpeech || ""}</span> {w.meaning}
        </div>
      )}
      {w.example && <div className="lk-example">例句：{w.example}</div>}
      <div className="lk-card-ops">
        <button className="btn tool-btn lk-op" onClick={() => doNotebook(w)}>
          {w.inNotebook ? "📓 移出生词本" : "📒 加入生词本"}
        </button>
      </div>
    </div>
  );

  return (
    <div className="mg-panel">
      <div className="mg-header">
        <h2>🔍 查生词</h2>
        <span className="mg-sub">查释义 · 英/美音 · 文本提取 · 一键入库/生词本</span>
      </div>

      <div className="rv-mode">
        <button className={`btn tool-btn ${mode === "single" ? "rv-mode-active" : ""}`} onClick={() => setMode("single")}>
          📝 单词查释义
        </button>
        <button className={`btn tool-btn ${mode === "batch" ? "rv-mode-active" : ""}`} onClick={() => setMode("batch")}>
          📄 文本批量提取
        </button>
      </div>

      {mode === "single" ? (
        <>
          <div className="mg-actions">
            <input
              className="lk-single-input"
              placeholder="输入要查的英文单词…"
              value={word}
              onChange={(e) => setWord(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && doLookup()}
            />
            <button className="btn send-btn" disabled={loading || !word.trim()} onClick={doLookup}>
              {loading ? "查询中…" : "🔍 查询"}
            </button>
          </div>
          <label className="mg-count">
            <input type="checkbox" checked={autoNotebook} onChange={(e) => setAutoNotebook(e.target.checked)} />
            查词自动加入生词本
          </label>
          {single && card(single)}
          {single && (
            <div className="mg-actions">
              <label className="mg-count">
                <input type="checkbox" checked={syncToMaimemo} onChange={(e) => setSyncToMaimemo(e.target.checked)} />
                同时加入墨墨云词本
              </label>
              <button className="btn tool-btn" disabled={loading || single.inLibrary} onClick={() => doAdd([single.word])}>
                {single.inLibrary ? "已在词库" : "➕ 加入词库" + (syncToMaimemo ? "+云词本" : "")}
              </button>
            </div>
          )}
        </>
      ) : (
        <>
          <textarea
            className="lk-batch-textarea"
            placeholder="粘贴一段英文原文，自动提取生词并查释义…"
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows={5}
          />
          <div className="mg-actions">
            <button className="btn send-btn" disabled={loading || !text.trim()} onClick={doExtract}>
              {loading ? "提取中…" : "📄 提取生词"}
            </button>
          </div>
          {words.length > 0 && (
            <>
              <div className="lk-batch-bar">
                <button className="tool-btn" onClick={selectAll}>
                  {selected.size === words.length && words.length > 0 ? "取消全选" : "全选"}
                </button>
                <label className="mg-count">
                  <input type="checkbox" checked={syncToMaimemo} onChange={(e) => setSyncToMaimemo(e.target.checked)} />
                  同时加入墨墨云词本
                </label>
                <button className="btn tool-btn" disabled={loading || !selected.size} onClick={() => doAdd([...selected])}>
                  ➕ 加入词库{selected.size ? `(${selected.size})` : ""}
                  {syncToMaimemo ? "+云词本" : ""}
                </button>
              </div>
              <div className="lk-list">
                {words.map((w, i) => (
                  <div className="lk-row" key={`${w.word}-${i}`}>
                    <input
                      type="checkbox"
                      className="lk-check"
                      checked={selected.has(w.word)}
                      onChange={() => toggleSelect(w.word)}
                      disabled={!!w.error}
                    />
                    <div className="lk-row-main">{card(w)}</div>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}

      {msg && <div className="mg-msg">{msg}</div>}
      {resultMsg && <div className="mg-msg">{resultMsg}</div>}
    </div>
  );
}
