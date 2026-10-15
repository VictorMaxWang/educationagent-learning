import { useEffect, useRef, useState } from "react";
import { maimemoSpeechPreset, maimemoSpeechEvaluate, maimemoSpeechDialogue } from "../../request";

type PresetItem = { text: string; type: string };
type Result = {
  code: number;
  text: string;
  score: number;
  category: string;
  feedback: string;
  /** 引擎拒绝本次录音（过短/过安静/背景噪音等） */
  rejected?: boolean;
  rejectType?: number;
  msg?: string;
  /** 讯飞结构化数据：三维度 */
  scores?: { total?: number; accuracy?: number; fluency?: number; integrity?: number };
  /** 讯飞逐词得分 */
  perWord?: { word?: string; accuracy?: number; fluency?: number; integrity?: number; total?: number }[];
};
type DialogueLine = { role?: string; text?: string; cn?: string };
type Dialogue = { code?: number; title?: string; scenario?: string; lines?: DialogueLine[]; msg?: string };

/** 情景对话可选场景 */
const SCENARIOS = ["餐厅点餐", "机场值机", "问路", "购物", "酒店入住", "看病挂号"];

/**
 * 🗣 口语练习 — 全宽布局，两种模式：
 *  - 跟读：预设/自定义文本 → 录音 → 讯飞 ISE 评测 + AI 教练反馈
 *  - 情景对话：选场景 → 生成双人剧本（用户扮演 B）→ 逐句跟读评测
 * 左卡片选文本/看剧本，右卡片评测结果。
 */
export default function MaimemoSpeaking() {
  const [mode, setMode] = useState<"repeat" | "dialogue">("repeat");

  // ===== 跟读模式 =====
  const [presets, setPresets] = useState<PresetItem[]>([]);
  const [text, setText] = useState(""); // 当前朗读目标（跟读所选 或 对话当前句）
  const [custom, setCustom] = useState("");

  // ===== 情景对话模式 =====
  const [scenario, setScenario] = useState("");
  const [customScenario, setCustomScenario] = useState("");
  const [dialogueLength, setDialogueLength] = useState<"short" | "standard" | "long">("standard");
  const [dialogue, setDialogue] = useState<Dialogue | null>(null);
  const [loadingDialogue, setLoadingDialogue] = useState(false);
  const [userIdx, setUserIdx] = useState(0); // 用户(B)台词下标

  // ===== 录音 + 评测 =====
  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [duration, setDuration] = useState(0);
  const [audioBase64, setAudioBase64] = useState("");
  const [audioUrl, setAudioUrl] = useState("");
  const [evaluating, setEvaluating] = useState(false);
  const [result, setResult] = useState<Result | null>(null);
  const [msg, setMsg] = useState("");

  // 录音资源引用
  const streamRef = useRef<MediaStream | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const procRef = useRef<ScriptProcessorNode | null>(null);
  const chunksRef = useRef<Float32Array[]>([]);
  const totalRef = useRef(0);
  const rateRef = useRef(48000);
  const startRef = useRef(0);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    maimemoSpeechPreset()
      .then((res) => {
        if (res?.code === 200) setPresets(res.items || []);
      })
      .catch((e) => setMsg("❌ 预设文本加载失败：" + e));
  }, []);

  useEffect(() => () => { stopRecordingInner(); }, []);

  // 用户(B)台词列表
  const userLines = (dialogue?.lines || []).filter((l) => (l.role || "").toUpperCase() === "B");
  const currentLine = userLines[userIdx];

  // 带 B 下标的剧本行（用于高亮当前/已完成）
  const lineViews: { l: DialogueLine; isB: boolean; bIndex: number }[] = [];
  {
    let b = 0;
    for (const l of dialogue?.lines || []) {
      const isB = (l.role || "").toUpperCase() === "B";
      lineViews.push({ l, isB, bIndex: isB ? b++ : -1 });
    }
  }

  /** 加载情景对话，预置第一句用户台词；len 指定篇幅（简短/标准/长篇） */
  const loadDialogue = async (sc: string, len?: "short" | "standard" | "long") => {
    const length = len ?? dialogueLength;
    setScenario(sc);
    setLoadingDialogue(true);
    setMsg("");
    setDialogue(null);
    setUserIdx(0);
    setResult(null);
    setAudioBase64("");
    setAudioUrl("");
    try {
      const res: Dialogue = await maimemoSpeechDialogue(sc, length);
      if (res?.code === 200) {
        setDialogue(res);
        const bs = (res.lines || []).filter((l) => (l.role || "").toUpperCase() === "B");
        if (bs[0]?.text) setText(bs[0].text);
      } else {
        setMsg(res?.msg || "对话生成失败");
      }
    } catch (e) {
      console.error("[Speaking] 对话生成失败:", e);
      setMsg("请求失败，请检查后端服务");
    } finally {
      setLoadingDialogue(false);
    }
  };

  /** 对话下一句 */
  const goNextLine = () => {
    setResult(null);
    setAudioBase64("");
    setAudioUrl("");
    setMsg("");
    if (userIdx + 1 < userLines.length) {
      const next = userIdx + 1;
      setUserIdx(next);
      setText(userLines[next].text || "");
    } else {
      setMsg("🎉 情景对话练习完成！换个场景再来一轮吧");
    }
  };

  // ===== 录音 =====
  const startRecording = async () => {
    setMsg("");
    setResult(null);
    if (!text) { setMsg("⚠️ 请先选择或输入要朗读的文本"); return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const AC: typeof AudioContext = window.AudioContext || (window as any).webkitAudioContext;
      const ctx = new AC();
      const source = ctx.createMediaStreamSource(stream);
      const proc = ctx.createScriptProcessor(4096, 1, 1);
      chunksRef.current = [];
      totalRef.current = 0;
      rateRef.current = ctx.sampleRate || 48000;
      proc.onaudioprocess = (e) => {
        const data = new Float32Array(e.inputBuffer.getChannelData(0));
        chunksRef.current.push(data);
        totalRef.current += data.length;
      };
      const mute = ctx.createGain();
      mute.gain.value = 0;
      source.connect(proc);
      proc.connect(mute);
      mute.connect(ctx.destination);

      streamRef.current = stream;
      ctxRef.current = ctx;
      sourceRef.current = source;
      procRef.current = proc;
      startRef.current = Date.now();
      setElapsed(0);
      setRecording(true);
      timerRef.current = window.setInterval(() => {
        setElapsed(Math.floor((Date.now() - startRef.current) / 1000));
      }, 250);
    } catch (e) {
      setMsg("❌ 无法访问麦克风：" + ((e as Error).message || e) + "（请检查系统/浏览器麦克风权限）");
    }
  };

  const stopRecordingInner = () => {
    try { procRef.current?.disconnect(); } catch { /* ignore */ }
    try { sourceRef.current?.disconnect(); } catch { /* ignore */ }
    streamRef.current?.getTracks().forEach((t) => t.stop());
    try { ctxRef.current?.close(); } catch { /* ignore */ }
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    streamRef.current = null;
    ctxRef.current = null;
    sourceRef.current = null;
    procRef.current = null;
  };

  const stopRecording = () => {
    if (!recording) return;
    const secs = (Date.now() - startRef.current) / 1000;
    stopRecordingInner();
    setRecording(false);
    setDuration(Math.max(1, Math.round(secs)));

    const samples = new Float32Array(totalRef.current);
    let off = 0;
    for (const c of chunksRef.current) { samples.set(c, off); off += c.length; }
    // 首尾静音裁剪：避免开头/结尾过长静音被引擎判 reject_type=128（评测超时/音频异常）
    const trimmed = trimSilence(samples, rateRef.current);
    const pcm16 = resampleTo16k(trimmed, rateRef.current);
    const bytes = floatTo16BitPCM(pcm16);
    setAudioBase64(bytesToBase64(bytes));
    setAudioUrl(pcmToWavUrl(bytes, 16000));
  };

  const doEvaluate = async () => {
    if (!text) { setMsg("⚠️ 请先选择或输入要朗读的文本"); return; }
    if (!audioBase64) { setMsg("⚠️ 请先录音"); return; }
    setEvaluating(true);
    setMsg("");
    try {
      const res = await maimemoSpeechEvaluate({ text, audioBase64 });
      if (res?.code === 200 && res.rejected) {
        // 引擎拒绝：不给误导性分数，提示重录
        setResult(null);
        setMsg(res.msg || "⚠️ 本次录音未被评测通过，请重新录音");
      } else if (res?.code === 200) {
        setResult(res);
        setMsg("");
      } else {
        setMsg("❌ 评测失败：" + (res?.msg || "未知错误"));
      }
    } catch (e) {
      setMsg("❌ 请求失败：" + ((e as Error).message || e) + "（后端可能未配置讯飞凭证）");
    } finally {
      setEvaluating(false);
    }
  };

  const pickPreset = (p: PresetItem) => {
    setText(p.text);
    setCustom("");
    setResult(null);
    setMsg("");
  };

  /** 清除语音：移除当前录音与评测结果；重新录需再点「开始录音」 */
  const clearRecord = () => {
    if (recording) stopRecording();
    setAudioBase64("");
    setAudioUrl("");
    setDuration(0);
    setResult(null);
    setMsg("");
  };

  const onCustom = (v: string) => {
    setCustom(v);
    setText(v.trim());
  };

  const speak = () => {
    if (!text) return;
    try {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = "en-US";
      u.rate = 0.9;
      window.speechSynthesis.cancel();
      window.speechSynthesis.speak(u);
    } catch { /* 忽略 */ }
  };

  const scoreCls =
    result && result.score >= 85 ? "sp-score-good" :
    result && result.score >= 70 ? "sp-score-mid" : "sp-score-low";
  const scoreLevel =
    result ? (result.score >= 85 ? "优秀" : result.score >= 70 ? "良好" : "待提升") : "";

  // 录音 + 评测按钮（两种模式共用）
  const recordControls = (
    <>
      <div className="sp-record-row">
        {!recording ? (
          <button className="btn sp-record-btn" onClick={startRecording} disabled={evaluating}>🎙 开始录音</button>
        ) : (
          <button className="btn sp-record-btn sp-recording" onClick={stopRecording}>⏹ 停止 ({elapsed}s)</button>
        )}
        {audioUrl && <audio className="sp-audio" controls src={audioUrl} />}
        {!recording && audioUrl && (
          <button className="sp-clear-record" onClick={clearRecord} disabled={evaluating} title="清除语音，重新录音">
            ✕
          </button>
        )}
      </div>
      {recording && <div className="sp-hint">🔴 正在录音…请大声朗读目标文本</div>}
      {!recording && audioUrl && <div className="sp-hint">录音完成（{duration}s）· 可先试听再评测</div>}
      <div className="sp-eval">
        <button className="btn send-btn" onClick={doEvaluate} disabled={evaluating || recording || !audioBase64}>
          {evaluating ? "⏳ 评测中…" : "📊 开始评测"}
        </button>
      </div>
    </>
  );

  // 评测结果卡片（两种模式共用）：讯飞结构化数据 + LLM 人话讲解
  const resultCard = result ? (
    <div className="sp-result">
      <div className="sp-score-row">
        <div className={`sp-score ${scoreCls}`}>
          {result.score}
          <small>分</small>
        </div>
        <div className="sp-score-side">
          <div className="sp-cat">{result.category === "read_word" ? "📄 单词" : "📄 句子"} 跟读</div>
          <div className="sp-level">{scoreLevel}</div>
        </div>
      </div>

      {/* 讯飞三维度分项 */}
      {result.scores && (
        <div className="sp-dims">
          <div className="sp-dim"><span>准确</span><b>{(result.scores.accuracy ?? 0).toFixed(2)}</b></div>
          <div className="sp-dim"><span>流利</span><b>{(result.scores.fluency ?? 0).toFixed(2)}</b></div>
          <div className="sp-dim"><span>完整</span><b>{(result.scores.integrity ?? 0).toFixed(2)}</b></div>
        </div>
      )}

      {/* 讯飞逐词评分（相对弱词标红） */}
      {result.perWord && result.perWord.length > 0 && (
        <div className="sp-words">
          <div className="sp-fb-title">🔤 逐词评分（讯飞）</div>
          <div className="sp-word-chips">
            {(() => {
              const maxAcc = Math.max(...result.perWord!.map((w) => w.accuracy ?? 0));
              return result.perWord!.map((w, i) => {
                const a = w.accuracy ?? 0;
                const weak = maxAcc > 0 && a < maxAcc * 0.65;
                return (
                  <span key={i} className={`sp-word-chip${weak ? " weak" : ""}`}>
                    {w.word}
                    <em>{a.toFixed(1)}</em>
                  </span>
                );
              });
            })()}
          </div>
        </div>
      )}

      {/* LLM 人话版讲解（不重新打分，只翻译讯飞数据） */}
      <div className="sp-fb">
        <div className="sp-fb-title">💬 人话讲解（AI 教练）</div>
        <div className="sp-fb-body">{renderFeedback(result.feedback || "")}</div>
      </div>
    </div>
  ) : null;

  return (
    <div className="sp-wrap">
      <div className="sp-header">
        <h2>🗣 口语练习</h2>
        <span className="sp-sub">跟读单词/句子 · 情景对话 · 讯飞 ISE 评测 + AI 教练反馈</span>
      </div>

      {/* 模式切换 */}
      <div className="rv-mode">
        <button className={`btn tool-btn ${mode === "repeat" ? "rv-mode-active" : ""}`} onClick={() => setMode("repeat")}>
          🎙 跟读
        </button>
        <button className={`btn tool-btn ${mode === "dialogue" ? "rv-mode-active" : ""}`} onClick={() => setMode("dialogue")}>
          🎬 情景对话
        </button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {/* ===== 跟读模式：左选文本/录音，右评测 ===== */}
      {mode === "repeat" ? (
        <div className="sp-cards">
          <div className="sp-card">
            <div className="sp-card-title">📚 练习文本</div>
            <div className="sp-preset">
              <div className="sp-label">选择练习文本</div>
              {presets.length === 0 ? (
                <div className="mg-loading">加载中…</div>
              ) : (
                <div className="sp-chips">
                  {presets.map((p, i) => (
                    <button
                      key={i}
                      className={`sp-chip ${text === p.text ? "sp-chip-active" : ""}`}
                      onClick={() => pickPreset(p)}
                    >
                      {p.text}
                      <em>{p.type}</em>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="sp-custom">
              <div className="sp-label">✏️ 或输入自定义文本</div>
              <input className="sp-input" value={custom} onChange={(e) => onCustom(e.target.value)} placeholder="输入想朗读的英文单词或句子…" />
            </div>
            <div className="sp-target">
              <span className="sp-target-label">🎯 朗读目标</span>
              <span className="sp-target-text">{text || "—"}</span>
              <button className="btn tool-btn sp-listen" onClick={speak} disabled={!text} title="听标准发音">🔊 听发音</button>
            </div>
            {recordControls}
          </div>

          <div className="sp-card">
            <div className="sp-card-title">📊 评测结果</div>
            {resultCard ? (
              resultCard
            ) : (
              <div className="sp-card-empty">录音并评测后，这里展示得分与教练反馈</div>
            )}
          </div>
        </div>
      ) : (
        /* ===== 情景对话模式 ===== */
        <>
          <div className="sp-card">
            <div className="sp-card-title sp-card-title-row">
              <span>🎬 选择情景</span>
              {scenario && (
                <button className="sp-regenerate" onClick={() => loadDialogue(scenario)} disabled={loadingDialogue}>
                  🔄 重新生成
                </button>
              )}
            </div>
            <div className="sp-chips">
              {SCENARIOS.map((sc) => (
                <button
                  key={sc}
                  className={`sp-chip ${scenario === sc ? "sp-chip-active" : ""}`}
                  onClick={() => loadDialogue(sc)}
                  disabled={loadingDialogue}
                >
                  {sc}
                </button>
              ))}
            </div>

            {/* 篇幅选择：简短 / 标准 / 长篇 */}
            <div className="sp-length-row">
              <span className="sp-label">📏 篇幅</span>
              <div className="sp-chips">
                {(["short", "standard", "long"] as const).map((len) => (
                  <button
                    key={len}
                    className={`sp-chip ${dialogueLength === len ? "sp-chip-active" : ""}`}
                    onClick={() => {
                      setDialogueLength(len);
                      if (scenario) loadDialogue(scenario, len);
                    }}
                    disabled={loadingDialogue}
                  >
                    {len === "short" ? "简短" : len === "long" ? "长篇" : "标准"}
                  </button>
                ))}
              </div>
            </div>

            {/* 自定义情景 */}
            <div className="sp-custom-scenario">
              <input
                className="sp-input"
                placeholder="或输入自定义情景，如：咖啡店点单、医院挂号…"
                value={customScenario}
                onChange={(e) => setCustomScenario(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    const s = customScenario.trim();
                    if (s) loadDialogue(s);
                  }
                }}
              />
              <button
                className="sp-regenerate"
                onClick={() => {
                  const s = customScenario.trim();
                  if (s) loadDialogue(s);
                }}
                disabled={loadingDialogue || !customScenario.trim()}
              >
                🎬 生成
              </button>
            </div>
          </div>

          {loadingDialogue && <div className="mg-loading">✨ 生成情景对话中…</div>}

          {dialogue && (
            <div className="sp-cards">
              {/* 左：对话剧本 */}
              <div className="sp-card">
                <div className="sp-card-title">📜 {dialogue.title || scenario}</div>
                <div className="sp-dialogue-lines">
                  {lineViews.map(({ l, isB, bIndex }, i) => {
                    const isCurrent = isB && bIndex === userIdx;
                    const isDone = isB && bIndex < userIdx;
                    return (
                      <div
                        key={i}
                        className={`sp-dl-line ${isB ? "sp-dl-b" : "sp-dl-a"} ${isCurrent ? "sp-dl-current" : ""} ${isDone ? "sp-dl-done" : ""}`}
                      >
                        <span className="sp-dl-role">{l.role}:</span>
                        <span className="sp-dl-text">{l.text}</span>
                        {l.cn && <span className="sp-dl-cn">{l.cn}</span>}
                      </div>
                    );
                  })}
                </div>
              </div>

              {/* 右：当前句跟读 */}
              <div className="sp-card">
                <div className="sp-card-title">
                  🎤 跟读第 {Math.min(userIdx + 1, userLines.length)} / {userLines.length} 句
                </div>
                {currentLine && (
                  <>
                    <div className="sp-target sp-dialogue-target">
                      <span className="sp-target-text">{currentLine.text}</span>
                      {currentLine.cn && <span className="sp-dl-cn">（{currentLine.cn}）</span>}
                      <button className="btn tool-btn sp-listen" onClick={speak} disabled={!text}>🔊 听发音</button>
                    </div>
                    {recordControls}
                    {resultCard}
                    {result && (
                      <div className="sp-dialogue-next">
                        <button className="btn send-btn" onClick={goNextLine}>
                          {userIdx + 1 >= userLines.length ? "完成对话" : "下一句 →"}
                        </button>
                      </div>
                    )}
                  </>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/* ===== 纯函数工具 ===== */

/** 裁剪首尾静音（音量低于阈值），前后各留 100ms 缓冲，避免裁掉语音起止 */
function trimSilence(samples: Float32Array, sampleRate: number): Float32Array {
  const threshold = 0.008;
  const pad = Math.max(1, Math.floor(0.1 * sampleRate)); // 100ms
  let start = -1;
  let end = -1;
  for (let i = 0; i < samples.length; i++) {
    if (Math.abs(samples[i]) > threshold) { start = i; break; }
  }
  if (start < 0) return samples; // 全静音，原样返回
  for (let i = samples.length - 1; i >= 0; i--) {
    if (Math.abs(samples[i]) > threshold) { end = i + 1; break; }
  }
  start = Math.max(0, start - pad);
  end = Math.min(samples.length, end + pad);
  return samples.slice(start, end);
}

function resampleTo16k(samples: Float32Array, sourceRate: number): Float32Array {
  const TARGET = 16000;
  if (!sourceRate || sourceRate <= TARGET) return samples;
  const ratio = sourceRate / TARGET;
  const outLen = Math.floor(samples.length / ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const pos = i * ratio;
    const i0 = Math.floor(pos);
    const i1 = Math.min(i0 + 1, samples.length - 1);
    const frac = pos - i0;
    out[i] = samples[i0] * (1 - frac) + samples[i1] * frac;
  }
  return out;
}

function floatTo16BitPCM(input: Float32Array): Uint8Array {
  const buf = new ArrayBuffer(input.length * 2);
  const view = new DataView(buf);
  for (let i = 0; i < input.length; i++) {
    let s = Math.max(-1, Math.min(1, input[i]));
    view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buf);
}

function bytesToBase64(bytes: Uint8Array): string {
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

function pcmToWavUrl(pcm: Uint8Array, sampleRate: number): string {
  const dataLen = pcm.length;
  const buf = new ArrayBuffer(44 + dataLen);
  const v = new DataView(buf);
  const w = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i));
  };
  w(0, "RIFF"); v.setUint32(4, 36 + dataLen, true); w(8, "WAVE");
  w(12, "fmt "); v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  w(36, "data"); v.setUint32(40, dataLen, true);
  new Uint8Array(buf, 44).set(pcm);
  return URL.createObjectURL(new Blob([buf], { type: "audio/wav" }));
}

function renderFeedback(t: string) {
  if (!t) return null;
  return t.split("\n").map((line, i) => {
    const parts = line.split("**");
    return (
      <div key={i} className="sp-fb-line">
        {parts.map((p, j) => (j % 2 === 1 ? <strong key={j}>{p}</strong> : <span key={j}>{p}</span>))}
      </div>
    );
  });
}
