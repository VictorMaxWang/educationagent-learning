import { useState } from "react";
import { maimemoListenDictation, maimemoListenPassage, maimemoAudioUrl } from "../../request";

type Q = { question: string; options: string[]; answer_index: number };

/**
 * 🎧 听力训练 — 单词听写 + AI 短文听力（M3）。
 *  - 单词听写：后端 TTS 每词生成「发音 + 停顿 + 逐字母拼写」MP3 包，本地播放。
 *  - 短文听力：DeepSeek 用复习词生成小故事 + 理解题，分段 TTS 合成音频，选后显示答案。
 */
export default function MaimemoListening() {
  const [mode, setMode] = useState<"dictation" | "passage">("dictation");
  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState("");
  const [audioUrl, setAudioUrl] = useState("");
  const [words, setWords] = useState<string[]>([]);
  const [passage, setPassage] = useState("");
  const [questions, setQuestions] = useState<Q[]>([]);
  const [picked, setPicked] = useState<Record<number, number>>({});
  const [count, setCount] = useState(20);
  const [audioKey, setAudioKey] = useState(0); // 强制刷新 <audio>，重新生成时清空旧缓存

  const gen = async () => {
    setLoading(true);
    setMsg("");
    try {
      const res = mode === "dictation"
        ? await maimemoListenDictation({ count })
        : await maimemoListenPassage({ count, withQuestions: true });
      if (res?.code === 200) {
        setAudioUrl(maimemoAudioUrl(res.audioUrl));
        setAudioKey(k => k + 1);
        setWords(res.words || []);
        setMsg(res.msg || "");
        if (mode === "passage") {
          setPassage(res.passage || "");
          setQuestions(res.questions || []);
          setPicked({});
        }
      } else {
        setMsg(res?.msg || "❌ 生成失败");
      }
    } catch (e) {
      setMsg(`❌ 请求失败：${e}`);
    } finally {
      setLoading(false);
    }
  };

  const pick = (qi: number, oi: number) => {
    setPicked(prev => ({ ...prev, [qi]: oi }));
  };

  // 切换模式时按各自词数上限钳制
  const switchMode = (m: "dictation" | "passage") => {
    setMode(m);
    const cap = m === "dictation" ? 20 : 15;
    setCount(c => Math.min(c, cap));
  };

  return (
    <div className="mg-panel">
      <div className="mg-header">
        <h2>🎧 听力训练</h2>
        <span className="mg-sub">
          {mode === "dictation" ? "单词听写 · 发音 + 逐字母拼写" : "AI 短文听力 · 用今日复习词生成小故事"}
        </span>
      </div>

      {/* 模式切换 */}
      <div className="rv-mode">
        <button className={`btn tool-btn ${mode === "dictation" ? "rv-mode-active" : ""}`} onClick={() => switchMode("dictation")}>
          📝 单词听写
        </button>
        <button className={`btn tool-btn ${mode === "passage" ? "rv-mode-active" : ""}`} onClick={() => switchMode("passage")}>
          📖 短文听力
        </button>
      </div>

      {/* 生成操作区 */}
      <div className="mg-actions">
        <label className="mg-count">
          词数
          <input
            type="number"
            min={3}
            max={mode === "dictation" ? 20 : 15}
            value={count}
            onChange={(e) => setCount(Number(e.target.value) || 10)}
          />
        </label>
        <button className="btn send-btn" disabled={loading} onClick={gen}>
          {loading ? "生成中…" : mode === "dictation" ? "🎯 生成听写包" : "📄 生成短文"}
        </button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {words.length > 0 && (
        <div className="mg-wordset">
          {words.map((w, i) => (
            <span className="mg-word" key={`${w}-${i}`}>{w}</span>
          ))}
        </div>
      )}

      {audioUrl && (
        <div className="mg-audio">
          <audio key={audioKey} controls preload="metadata" src={audioUrl} style={{ width: "100%" }} />
        </div>
      )}

      {mode === "passage" && passage && (
        <div className="mg-passage">
          <div className="mg-passage-title">📄 短文内容</div>
          <div className="mg-passage-text">{passage}</div>

          {questions.length > 0 && (
            <div className="mg-questions">
              <div className="mg-passage-title">❓ 听力理解</div>
              {questions.map((q, qi) => {
                const revealed = picked[qi] !== undefined;
                return (
                  <div className="mg-question" key={qi}>
                    <div className="mg-q-text">{qi + 1}. {q.question}</div>
                    {q.options.map((op, oi) => {
                      const isAnswer = q.answer_index === oi;
                      const isPicked = picked[qi] === oi;
                      const cls = revealed
                        ? isAnswer
                          ? "mg-opt mg-opt-correct"
                          : isPicked
                            ? "mg-opt mg-opt-wrong"
                            : "mg-opt"
                        : "mg-opt";
                      return (
                        <button key={oi} className={cls} onClick={() => !revealed && pick(qi, oi)}>
                          {op}
                          {revealed && isAnswer && " ✓"}
                          {revealed && isPicked && !isAnswer && " ✗"}
                        </button>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
