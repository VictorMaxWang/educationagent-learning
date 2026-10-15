import { useCallback, useEffect, useState } from "react";
import { maimemoGetWords, maimemoReviewToday, maimemoReviewAnswer } from "../../request";
import ForgettingCurve from "./ForgettingCurve";

type Word = {
  id?: number;
  word: string;
  phonetic?: string;
  meaning?: string;
  partOfSpeech?: string;
  example?: string;
  status?: string;
  reviewLevel?: number;
  nextReviewAt?: number;
  options?: { options?: string[]; answerIndex?: number };
};

/** 词源模式 */
type Mode = "today" | "weak" | "all";
/** 答题模式 */
type ReviewMode = "flip" | "spell" | "mcq";

/** 下次复习时间显示 */
function fmtNext(ts?: number): string {
  if (!ts) return "";
  const days = Math.round((ts - Date.now()) / 86400000);
  if (days <= 0) return "今天";
  if (days === 1) return "明天";
  return `${days}天后`;
}

/**
 * 📖 背单词 — 艾宾浩斯间隔复习。
 * 词源：今日复习（到期词）/ 难词强化（模糊+遗忘）/ 全部词库
 * 答题：翻转 / 拼写 / 选择题；答对按艾宾浩斯间隔推进，答错归零明天再复习。
 */
export default function MaimemoReview() {
  const [mode, setMode] = useState<Mode>("today");
  const [reviewMode, setReviewMode] = useState<ReviewMode>("flip");
  const [queue, setQueue] = useState<Word[]>([]);
  const [idx, setIdx] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const [loading, setLoading] = useState(true);
  const [masteredCount, setMasteredCount] = useState(0);
  const [redoWords, setRedoWords] = useState<Word[]>([]); // 模糊+遗忘（重背用）
  const [done, setDone] = useState(false);
  const [msg, setMsg] = useState("");

  // 拼写/选择题反馈
  const [spellInput, setSpellInput] = useState("");
  const [feedback, setFeedback] = useState<{ correct: boolean; answer: string } | null>(null);

  const loadWords = useCallback(async (m: Mode) => {
    setLoading(true);
    setDone(false);
    setFlipped(false);
    setMasteredCount(0);
    setRedoWords([]);
    setFeedback(null);
    let words: Word[] = [];
    if (m === "today") {
      const res = await maimemoReviewToday();
      if (res?.code === 200) {
        words = res.words || [];
        setMsg(res.msg || "");
      } else setMsg(res?.msg || "❌ 拉取今日复习失败");
    } else if (m === "weak") {
      const res = await maimemoReviewToday("weak");
      if (res?.code === 200) {
        words = res.words || [];
        setMsg(res.msg || "");
      } else setMsg(res?.msg || "❌ 拉取难词失败");
    } else {
      const res = await maimemoGetWords();
      if (res?.code === 200) {
        words = res.words || [];
        setMsg(words.length ? `共 ${words.length} 个单词` : "📭 词库为空，先去「词库管理」生成/导入一批词吧");
      } else setMsg("❌ 加载词库失败");
    }
    setQueue(words);
    setIdx(0);
    setLoading(false);
  }, []);

  useEffect(() => {
    loadWords(mode);
  }, [mode, loadWords]);

  // 全部词库没有选择题数据，切回翻转
  useEffect(() => {
    if (mode === "all" && reviewMode === "mcq") setReviewMode("flip");
  }, [mode, reviewMode]);

  const current: Word | undefined = queue[idx];

  const advance = () => {
    setFlipped(false);
    setFeedback(null);
    setSpellInput("");
    if (idx + 1 >= queue.length) setDone(true);
    else setIdx(idx + 1);
  };

  /** 判分并推进（翻转模式） */
  const doAnswer = async (correct: boolean, revisit = false) => {
    if (!current) return;
    if (current.id != null) maimemoReviewAnswer(current.id, correct, revisit).catch(() => {});
    if (correct && !revisit) setMasteredCount((c) => c + 1);
    else setRedoWords((r) => [...r, current]);
    advance();
  };

  /** 拼写提交 */
  const submitSpell = () => {
    if (!current || feedback) return;
    const correct = spellInput.trim().toLowerCase() === current.word.toLowerCase();
    if (current.id != null) maimemoReviewAnswer(current.id, correct, false).catch(() => {});
    if (correct) setMasteredCount((c) => c + 1);
    else setRedoWords((r) => [...r, current]);
    setFeedback({ correct, answer: current.word });
  };

  /** 选择题提交 */
  const submitMcq = (pickIndex: number) => {
    if (!current || feedback) return;
    const answerIndex = current.options?.answerIndex ?? -1;
    const correct = pickIndex === answerIndex;
    if (current.id != null) maimemoReviewAnswer(current.id, correct, false).catch(() => {});
    if (correct) setMasteredCount((c) => c + 1);
    else setRedoWords((r) => [...r, current]);
    const answer = current.options?.options?.[answerIndex] ?? "";
    setFeedback({ correct, answer });
  };

  const reviewAgain = () => {
    if (!redoWords.length) return;
    setQueue(redoWords);
    setIdx(0);
    setDone(false);
    setFlipped(false);
    setMasteredCount(0);
    setRedoWords([]);
  };

  const total = queue.length;
  const progress = done ? total : idx;
  const redoCount = redoWords.length;
  const levelInfo = current?.reviewLevel != null ? `Lv.${current.reviewLevel}` : "";
  const nextInfo = fmtNext(current?.nextReviewAt);

  return (
    <div className="mg-panel rv-panel">
      <div className="mg-header">
        <h2>📖 单词学习</h2>
        <span className="mg-sub">
          {mode === "today"
            ? "今日待复习（艾宾浩斯到期词）"
            : mode === "weak"
              ? "难词强化（模糊/遗忘）"
              : "本地全部词库"}
        </span>
      </div>

      {/* 词源模式 */}
      <div className="rv-mode">
        <button className={`btn tool-btn ${mode === "today" ? "rv-mode-active" : ""}`} onClick={() => setMode("today")}>
          📋 今日复习
        </button>
        <button className={`btn tool-btn ${mode === "weak" ? "rv-mode-active" : ""}`} onClick={() => setMode("weak")}>
          💪 难词强化
        </button>
        <button className={`btn tool-btn ${mode === "all" ? "rv-mode-active" : ""}`} onClick={() => setMode("all")}>
          📚 全部词库
        </button>
      </div>

      {/* 答题模式 */}
      <div className="rv-mode">
        <button className={`btn tool-btn ${reviewMode === "flip" ? "rv-mode-active" : ""}`} onClick={() => setReviewMode("flip")}>
          🃏 翻转
        </button>
        <button className={`btn tool-btn ${reviewMode === "spell" ? "rv-mode-active" : ""}`} onClick={() => setReviewMode("spell")}>
          ⌨️ 拼写
        </button>
        <button
          className={`btn tool-btn ${reviewMode === "mcq" ? "rv-mode-active" : ""}`}
          onClick={() => setReviewMode("mcq")}
          disabled={mode === "all"}
          title={mode === "all" ? "全部词库模式不支持选择题" : "选择题"}
        >
          🔢 选择题
        </button>
      </div>

      {msg && <div className="mg-msg">{msg}</div>}

      {/* 艾宾浩斯遗忘曲线（可折叠） */}
      <ForgettingCurve />

      {loading ? (
        <div className="mg-loading">加载中…</div>
      ) : !done ? (
        current && (
          <>
            <div className="rv-progress">
              进度 {progress + 1} / {total} · 熟练 {masteredCount} · 需重背 {redoCount}
              {levelInfo ? ` · ${levelInfo}` : ""}
              {nextInfo ? ` · 下次${nextInfo}` : ""}
            </div>

            {reviewMode === "flip" && (
              <>
                <div className={`rv-card ${flipped ? "rv-flipped" : ""}`} onClick={() => setFlipped(!flipped)}>
                  {!flipped ? (
                    <div className="rv-front">
                      <div className="rv-word">{current.word}</div>
                      <div className="rv-phonetic">{current.phonetic || ""}</div>
                      <div className="rv-hint">点击卡片翻面查看释义</div>
                    </div>
                  ) : (
                    <div className="rv-back">
                      <div className="rv-word-sm">{current.word}</div>
                      <div className="rv-meaning">
                        <span className="mg-word-pos">{current.partOfSpeech || ""}</span> {current.meaning || "（暂无释义）"}
                      </div>
                      {current.example && <div className="rv-example">例句：{current.example}</div>}
                    </div>
                  )}
                </div>
                <div className="rv-actions">
                  <button className="btn rv-btn rv-known" onClick={() => doAnswer(true, false)}>✓ 熟练</button>
                  <button className="btn rv-btn rv-weak" onClick={() => doAnswer(true, true)}>? 模糊</button>
                  <button className="btn rv-btn rv-forgotten" onClick={() => doAnswer(false, false)}>✗ 遗忘</button>
                </div>
              </>
            )}

            {reviewMode === "spell" && (
              <>
                <div className="rv-card">
                  <div className="rv-back">
                    <div className="rv-meaning">
                      <span className="mg-word-pos">{current.partOfSpeech || ""}</span> {current.meaning || "（暂无释义）"}
                    </div>
                    {current.example && <div className="rv-example">例句：{current.example}</div>}
                    <div className="rv-hint">根据释义拼写单词</div>
                  </div>
                </div>
                {!feedback ? (
                  <div className="rv-actions">
                    <input
                      className="rv-spell-input"
                      value={spellInput}
                      onChange={(e) => setSpellInput(e.target.value)}
                      onKeyDown={(e) => e.key === "Enter" && submitSpell()}
                      placeholder="输入单词…"
                      autoFocus
                    />
                    <button className="btn send-btn" onClick={submitSpell}>提交</button>
                  </div>
                ) : (
                  <div className={`rv-feedback ${feedback.correct ? "rv-fb-ok" : "rv-fb-no"}`}>
                    <span>{feedback.correct ? "✅ 正确！" : `❌ 正确答案：${feedback.answer}`}</span>
                    <button className="btn tool-btn" onClick={advance}>下一张 →</button>
                  </div>
                )}
              </>
            )}

            {reviewMode === "mcq" && (
              <>
                <div className="rv-card">
                  <div className="rv-front">
                    <div className="rv-word">{current.word}</div>
                    <div className="rv-phonetic">{current.phonetic || ""}</div>
                    <div className="rv-hint">选出正确的释义</div>
                  </div>
                </div>
                {!feedback ? (
                  <div className="rv-actions rv-mcq-actions">
                    {(current.options?.options || []).map((opt, i) => (
                      <button key={i} className="btn tool-btn rv-mcq-opt" onClick={() => submitMcq(i)}>
                        {opt}
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className={`rv-feedback ${feedback.correct ? "rv-fb-ok" : "rv-fb-no"}`}>
                    <span>{feedback.correct ? "✅ 正确！" : `❌ 正确答案：${feedback.answer}`}</span>
                    <button className="btn tool-btn" onClick={advance}>下一张 →</button>
                  </div>
                )}
              </>
            )}
          </>
        )
      ) : (
        <div className="rv-summary">
          <div className="rv-summary-title">本轮复习完成！</div>
          <div className="rv-summary-row">
            熟练 <b>{masteredCount}</b> 个 · 需重背 <b>{redoCount}</b> 个
          </div>
          {redoWords.length > 0 && (
            <div className="rv-unknown-list">
              {redoWords.map((w, i) => (
                <span className="mg-word-pos" key={`${w.word}-${i}`}>{w.word}</span>
              ))}
            </div>
          )}
          <div className="rv-summary-actions">
            {redoWords.length > 0 && (
              <button className="btn send-btn" onClick={reviewAgain}>🔁 重背（{redoCount}）</button>
            )}
            <button className="btn tool-btn" onClick={() => loadWords(mode)}>🔄 再来一轮</button>
          </div>
        </div>
      )}
    </div>
  );
}
