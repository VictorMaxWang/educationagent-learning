import { useState, useRef, useEffect, useMemo } from 'react';
import { uploadFile, generateQuiz, submitQuiz, gradeOneQuiz, getQuizPapers, loadQuizPaper, passageAudio, deleteQuizPaper } from '../api/chatApi';
import { fileToBase64 } from '../utils/file';

const TYPE_LABEL = { single: '单选题', multi: '多选题', judge: '判断题', fill: '填空题', short: '简答题' };

/** 每次懒加载渲染的块数（一个材料组 = 一块） */
const LAZY_BATCH = 3;

/** base64 → Uint8Array（播放听力音频用） */
function base64ToBytes(b64) {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

// 解析选项 "A. xxx" → { letter:'A', text:'xxx' }
function parseOption(opt) {
  const m = String(opt).match(/^([A-Za-z])[.、．)\s]*\s*(.*)$/);
  return m ? { letter: m[1].toUpperCase(), text: m[2] } : { letter: '', text: String(opt) };
}

/**
 * 单题卡片：整卷滚动列表中的一项，内部管理自己的输入状态。
 * 答案变化通过 onAnswer(no, answer) 上报给父组件；单题判分通过 onGrade(no, answer)。
 */
function QuestionCard({ q, answered, fb, loading, onAnswer, onGrade, english }) {
  const [selected, setSelected] = useState([]);
  const [textInput, setTextInput] = useState('');

  const answerValue = () => {
    if (q.type === 'fill' || q.type === 'short') return textInput.trim();
    if (q.type === 'multi') return [...selected].sort().join('');
    return selected[0] || '';
  };

  const handleSelect = (v) => {
    if (q.type === 'multi') {
      setSelected(prev => {
        const next = prev.includes(v) ? prev.filter(x => x !== v) : [...prev, v];
        onAnswer(q.no, next.sort().join(''));
        return next;
      });
    } else {
      setSelected([v]);
      onAnswer(q.no, v);
    }
  };

  const handleText = (e) => {
    setTextInput(e.target.value);
    onAnswer(q.no, e.target.value.trim());
  };

  return (
    <div className={`quiz-card${english ? ' english' : ''}`}>
      <div className="quiz-qtype-row">
        <span className="quiz-qtype">{TYPE_LABEL[q.type] || q.type}</span>
        {answered && <span className="quiz-q-answered">✓ 已答</span>}
      </div>
      <div className="quiz-stem">{q.no}. {q.stem}</div>

      {q.type === 'judge' && (
        <div className="quiz-options">
          {['对', '错'].map(v => (
            <label key={v} className={`quiz-option ${selected.includes(v) ? 'checked' : ''}`}>
              <input type="radio" checked={selected.includes(v)} onChange={() => handleSelect(v)} />
              <span>{v}</span>
            </label>
          ))}
        </div>
      )}

      {(q.type === 'single' || q.type === 'multi') && (
        <div className="quiz-options">
          {q.options.map((opt, idx) => {
            const { letter, text } = parseOption(opt);
            const checked = selected.includes(letter);
            return (
              <label key={letter || 'o' + idx} className={`quiz-option ${checked ? 'checked' : ''}`}>
                <input type={q.type === 'multi' ? 'checkbox' : 'radio'} checked={checked} onChange={() => handleSelect(letter)} />
                <span className="quiz-opt-letter">{letter}.</span>
                <span>{text}</span>
              </label>
            );
          })}
        </div>
      )}

      {(q.type === 'fill' || q.type === 'short') && (
        <textarea
          className="quiz-textarea"
          value={textInput}
          onChange={handleText}
          placeholder={q.type === 'fill' ? '填写答案...' : '作答...'}
          rows={q.type === 'short' ? 4 : 2}
        />
      )}

      <div className="quiz-q-grade">
        {fb ? (
          <div className={`quiz-feedback ${fb.correct === true ? 'correct' : fb.correct === false ? 'wrong' : 'info'}`}>
            <div className="quiz-fb-head">
              {fb.auto ? (fb.correct ? '✅ 回答正确' : '❌ 回答错误') : '📖 本题无标准答案'}
            </div>
            {fb.auto && fb.correctAnswer && <div className="quiz-fb-line">正确答案：{fb.correctAnswer}</div>}
            {fb.explanation && <div className="quiz-fb-line">解析：{fb.explanation}</div>}
          </div>
        ) : q.hasAnswer ? (
          <button className="quiz-btn" onClick={() => onGrade(q.no, answerValue())} disabled={loading || !answerValue()}>
            {loading ? '⏳ 判分中...' : '📝 提交本题'}
          </button>
        ) : (
          <div className="quiz-feedback info">🤖 本题无标准答案，交卷时将由 AI 评判</div>
        )}
      </div>
    </div>
  );
}

/**
 * 材料组卡片（听力/阅读）：材料标题 + 正文，听力带播放按钮。
 */
function PassageCard({ passage, audioUrl, audioLoading, onLoad }) {
  const isListening = passage.passageType === 'listening';
  return (
    <div className={`kc-passage ${isListening ? 'listening' : 'reading'}`}>
      <div className="kc-passage-head">
        <span className="kc-passage-type">{isListening ? '🎧 听力材料' : '📖 阅读材料'}</span>
        {passage.title && <span className="kc-passage-title">{passage.title}</span>}
      </div>
      {isListening ? (
        <>
          {/* 听力语音条：先加载合成音频 → 原生播放条 */}
          <div className="kc-audio-row">
            {audioUrl ? (
              <audio className="kc-audio" controls src={audioUrl} />
            ) : (
              <button className="quiz-btn primary kc-play-btn" onClick={() => onLoad(passage.passageNo)} disabled={audioLoading === passage.passageNo}>
                {audioLoading === passage.passageNo ? '⏳ 语音合成中...' : '▶ 加载听力音频'}
              </button>
            )}
          </div>
          {/* 听力原文默认折叠，避免先看到答案/原文 */}
          <details className="kc-passage-details">
            <summary>查看听力原文</summary>
            <div className="kc-passage-content">{passage.content}</div>
          </details>
        </>
      ) : (
        <div className="kc-passage-content">{passage.content}</div>
      )}
    </div>
  );
}

function QuizView({ userId, embedded }) {
  const [phase, setPhase] = useState('pick'); // pick | answering | finished
  const [pendingFile, setPendingFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [quiz, setQuiz] = useState(null);
  const [answers, setAnswers] = useState({});        // no -> 答案字符串
  const [feedbackMap, setFeedbackMap] = useState({}); // no -> {auto, correct, correctAnswer, explanation}
  const [result, setResult] = useState(null);
  const [papers, setPapers] = useState([]); // 历史试卷
  const [visibleCount, setVisibleCount] = useState(LAZY_BATCH); // 懒加载已渲染块数
  const [audioMap, setAudioMap] = useState({});        // passageNo -> 听力音频 object URL
  const [audioLoading, setAudioLoading] = useState(null); // 正在合成语音的材料组号
  const [pageIndex, setPageIndex] = useState(0);       // 英语卷当前页码（0-based）
  const [dragOver, setDragOver] = useState(false);      // 拖拽上传试卷高亮
  const fileInputRef = useRef(null);
  const sentinelRef = useRef(null);
  const audioUrlsRef = useRef(new Set()); // 已生成的音频 URL（换卷/卸载时统一清理）

  // 按材料组 / 独立题组织渲染块：材料在前、独立题在后
  const blocks = useMemo(() => {
    if (!quiz) return [];
    const byPassage = {};
    const standalone = [];
    (quiz.questions || []).forEach(q => {
      if (q.passageNo != null) (byPassage[q.passageNo] = byPassage[q.passageNo] || []).push(q);
      else standalone.push(q);
    });
    const arr = [];
    (quiz.passages || []).forEach(p => {
      arr.push({
        kind: 'passage',
        passage: p,
        questions: (byPassage[p.passageNo] || []).slice().sort((a, b) => a.no - b.no),
      });
    });
    standalone.sort((a, b) => a.no - b.no).forEach(q => arr.push({ kind: 'question', q }));
    return arr;
  }, [quiz]);

  // 是否英语卷：有听力/阅读材料，或标题含"英语/English" → 题目用英语风格卡片
  const isEnglishPaper = useMemo(() => {
    if (!quiz) return false;
    if ((quiz.passages?.length ?? 0) > 0) return true;
    const title = (quiz.title || '').toLowerCase();
    return title.includes('英语') || title.includes('english');
  }, [quiz]);

  // 英语卷分页数据：从 passages/questions 构建 short_listen / long_listen / reading 分页（长内容整块不拆分）
  const englishPages = useMemo(() => {
    if (!quiz || !quiz.passages?.length) return null;
    const byPassage = {};
    const standalone = [];
    (quiz.questions || []).forEach(q => {
      if (q.passageNo != null) (byPassage[q.passageNo] = byPassage[q.passageNo] || []).push(q);
      else standalone.push(q);
    });
    const items = [];
    (quiz.passages || []).forEach(p => {
      const qs = (byPassage[p.passageNo] || []).slice().sort((a, b) => a.no - b.no);
      if (p.passageType === 'listening') {
        if (qs.length === 1) items.push({ type: 'short_listen', audioScript: p.content, question: qs[0], passageNo: p.passageNo });
        else items.push({ type: 'long_listen', audioScript: p.content, questions: qs, passageNo: p.passageNo });
      } else {
        items.push({ type: 'reading', article: p.content, questions: qs, passageNo: p.passageNo });
      }
    });
    standalone.sort((a, b) => a.no - b.no).forEach(q => items.push({ type: 'plain', question: q }));
    // 分页：整块不拆分，按权重平衡每页（长听力/阅读=2，短听力/独立题=1，目标每页权重≤3）
    const pages = [];
    let cur = [], weight = 0;
    items.forEach(it => {
      const w = (it.type === 'long_listen' || it.type === 'reading') ? 2 : 1;
      if (cur.length > 0 && weight + w > 3) { pages.push(cur); cur = []; weight = 0; }
      cur.push(it); weight += w;
    });
    if (cur.length) pages.push(cur);
    return pages;
  }, [quiz]);

  // 加载历史试卷列表
  useEffect(() => {
    const load = async () => {
      try {
        const res = await getQuizPapers({ userId });
        setPapers(Array.isArray(res) ? res : []);
      } catch (err) {
        console.error('加载试卷列表失败：', err.message);
      }
    };
    load();
  }, [userId]);

  // 懒加载：滚动接近底部（哨兵元素进入可视区）时渲染下一批块
  useEffect(() => {
    if (phase !== 'answering' || !quiz) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          setVisibleCount(prev => Math.min(prev + LAZY_BATCH, blocks.length));
        }
      },
      { rootMargin: '300px' }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [phase, quiz, blocks]);

  // 英语卷：左右方向键翻页
  useEffect(() => {
    if (phase !== 'answering' || !englishPages) return;
    const onKey = (e) => {
      if (e.key === 'ArrowLeft') setPageIndex(i => Math.max(0, i - 1));
      if (e.key === 'ArrowRight') setPageIndex(i => Math.min(englishPages.length - 1, i + 1));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [phase, englishPages]);

  const handlePickFile = () => fileInputRef.current?.click();

  const handleFile = async (file) => {
    if (!file) return;
    try {
      const data = await fileToBase64(file);
      const mime = file.type || 'application/octet-stream';
      const res = await uploadFile({ fileName: file.name, mime, data });
      if (res?.fileId) setPendingFile({ fileId: res.fileId, fileName: file.name });
      else alert('上传失败：' + (res?.error || '未知错误'));
    } catch (err) {
      alert('上传失败：' + err.message);
    }
  };

  const handleFileChange = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许重复选同一个
    handleFile(file);
  };

  // 拖拽上传：选卷面板作为投放区
  const onDragOver = (e) => { e.preventDefault(); setDragOver(true); };
  const onDragLeave = () => setDragOver(false);
  const onDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    handleFile(e.dataTransfer?.files?.[0]);
  };

  // 进入刷题：初始化试卷状态
  const startQuiz = (res) => {
    clearAudioUrls();
    setPageIndex(0);
    setQuiz(res);
    setAnswers({});
    setFeedbackMap({});
    setResult(null);
    setVisibleCount(LAZY_BATCH);
    setPhase('answering');
  };

  const handleGenerate = async () => {
    if (!pendingFile) return;
    setLoading(true);
    try {
      const res = await generateQuiz({ userId, fileId: pendingFile.fileId });
      if (res.error) {
        alert('生成失败：' + res.error);
        return;
      }
      if (!res.questions || res.questions.length === 0) {
        alert('未解析到题目，请检查文件格式');
        return;
      }
      startQuiz(res);
      // 刷新试卷列表（新卷出现在"我的历史试卷"里）
      const papersRes = await getQuizPapers({ userId });
      setPapers(Array.isArray(papersRes) ? papersRes : []);
    } catch (err) {
      alert('生成失败：' + err.message);
    } finally {
      setLoading(false);
    }
  };

  // 历史试卷一键再刷
  const handleLoadPaper = async (quizId) => {
    setLoading(true);
    try {
      const res = await loadQuizPaper({ userId, quizId });
      if (res.error) {
        alert('加载失败：' + res.error);
        return;
      }
      if (!res.questions || res.questions.length === 0) {
        alert('该试卷没有题目');
        return;
      }
      startQuiz(res);
    } catch (err) {
      alert('加载失败：' + err.message);
    } finally {
      setLoading(false);
    }
  };

  // 删除历史试卷（软删除：从列表移除，错题记录保留）
  const handleDeletePaper = async (p) => {
    if (!window.confirm(`确定删除试卷「${p.title}」？将从列表移除（错题记录会保留）。`)) return;
    try {
      await deleteQuizPaper({ userId, quizId: p.quizId });
      setPapers(prev => prev.filter(x => x.quizId !== p.quizId));
    } catch (err) {
      alert('删除失败：' + err.message);
    }
  };

  // 某题答案变化：存入 answers，并清除该题旧反馈（需重新判分）
  const handleAnswer = (no, answer) => {
    setAnswers(prev => ({ ...prev, [no]: answer }));
    setFeedbackMap(prev => {
      if (!prev[no]) return prev;
      const next = { ...prev };
      delete next[no];
      return next;
    });
  };

  // 单题即时判分
  const handleGrade = async (no, answer) => {
    if (!answer) return;
    setLoading(true);
    try {
      const res = await gradeOneQuiz({ quizId: quiz.quizId, userId, no, answer });
      setFeedbackMap(prev => ({ ...prev, [no]: res }));
    } catch (err) {
      alert('判分失败：' + err.message);
    } finally {
      setLoading(false);
    }
  };

  // 加载听力音频（TTS 合成 → object URL），播放交给 <audio controls> 原生播放条
  const loadPassageAudio = async (passageNo) => {
    if (audioMap[passageNo]) return; // 已加载过
    setAudioLoading(passageNo);
    try {
      const res = await passageAudio({ quizId: quiz.quizId, passageNo });
      if (!res?.success) {
        alert(res?.error || '语音合成失败');
        return;
      }
      const url = URL.createObjectURL(new Blob([base64ToBytes(res.audio)], { type: res.mime || 'audio/mpeg' }));
      audioUrlsRef.current.add(url);
      setAudioMap(prev => ({ ...prev, [passageNo]: url }));
    } catch (err) {
      alert('加载听力失败：' + err.message);
    } finally {
      setAudioLoading(null);
    }
  };

  // 清理所有听力音频 URL（换卷 / 退出 / 卸载时）
  const clearAudioUrls = () => {
    audioUrlsRef.current.forEach(u => URL.revokeObjectURL(u));
    audioUrlsRef.current.clear();
    setAudioMap({});
  };
  useEffect(() => () => {
    audioUrlsRef.current.forEach(u => URL.revokeObjectURL(u));
    audioUrlsRef.current.clear();
  }, []);

  // 交卷：把所有已答题目提交
  const handleSubmit = async () => {
    const list = Object.entries(answers).map(([no, answer]) => ({ no: Number(no), answer }));
    setLoading(true);
    try {
      const res = await submitQuiz({ quizId: quiz.quizId, userId, answers: list });
      setResult(res);
      setPhase('finished');
    } catch (err) {
      alert('交卷失败：' + err.message);
    } finally {
      setLoading(false);
    }
  };

  const resetAll = () => {
    clearAudioUrls();
    setPhase('pick');
    setQuiz(null);
    setResult(null);
    setAnswers({});
    setFeedbackMap({});
    setPendingFile(null);
    setVisibleCount(LAZY_BATCH);
  };

  // 退出当前刷题（回试卷列表），已答内容不保存
  const backToPick = () => {
    if (!window.confirm('退出当前刷题？已答内容不会保存。')) return;
    clearAudioUrls();
    setPhase('pick');
    setQuiz(null);
    setAnswers({});
    setFeedbackMap({});
    setVisibleCount(LAZY_BATCH);
  };

  // ======================== 渲染 ========================

  if (phase === 'pick') {
    return (
      <div className="quiz-container">
        <div
          className={`quiz-panel${dragOver ? ' quiz-panel-dragover' : ''}`}
          onDragOver={onDragOver}
          onDragLeave={onDragLeave}
          onDrop={onDrop}
        >
          {!embedded && <h3>📝 刷题</h3>}
          <p className="quiz-tip">上传 Word / PDF / 文本题目文件，自动解析成试卷（可点击或拖拽到此处）</p>
          <input type="file" ref={fileInputRef} style={{ display: 'none' }} onChange={handleFileChange} />
          <button className="quiz-btn" onClick={handlePickFile} disabled={loading}>📎 选择题目文件</button>
          {pendingFile && <div className="pending-file-chip">📄 {pendingFile.fileName}</div>}
          <button className="quiz-btn primary" onClick={handleGenerate} disabled={loading || !pendingFile}>
            {loading ? '⏳ 解析中...' : '🚀 生成试卷'}
          </button>
        </div>

        {/* 我的历史试卷：一键再刷，无需重新上传 */}
        <div className="quiz-papers">
          <h4>📚 我的历史试卷</h4>
          {papers.length === 0 ? (
            <div className="quiz-tip">还没有试卷，上传一份题目文件后会自动保存</div>
          ) : (
            <div className="paper-list">
              {papers.map(p => (
                <div key={p.quizId} className="paper-item">
                  <button className="paper-load" onClick={() => handleLoadPaper(p.quizId)} disabled={loading}>
                    <span className="paper-title">📄 {p.title}</span>
                    <span className="paper-count">{p.questionCount} 题</span>
                  </button>
                  <button className="paper-del" onClick={() => handleDeletePaper(p)} title="删除试卷">🗑</button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  if (phase === 'finished') {
    const correctCount = result?.results?.filter(r => r.correct === true).length ?? 0;
    const selfCount = result?.results?.filter(r => r.correct == null).length ?? 0;
    // 新接口将未知判分单列，分数只除以已确认题数；缺少字段时保留旧接口显示。
    const hasConfirmedCount = result?.confirmedCount != null;
    const confirmedCount = hasConfirmedCount ? result.confirmedCount : (result?.total ?? 0);
    const pendingCount = result?.pendingCount ?? selfCount;
    return (
      <div className="quiz-container quiz-scroll">
        {/* 吸顶返回栏：回到"添加试卷"页面 */}
        <div className="quiz-topbar">
          <button className="quiz-back-btn" onClick={() => setPhase('pick')}>⬅ 返回</button>
          <div className="quiz-progress">刷题结果</div>
        </div>
        <div className="quiz-result">
          <h3>{hasConfirmedCount && confirmedCount === 0 ? '📊 结果待确认' : `📊 得分：${result?.score ?? 0} / ${confirmedCount}`}</h3>
          <div className="quiz-score-sub">{hasConfirmedCount ? <>
            共 {result?.total ?? 0} 题 · 已确认 {confirmedCount} 题
            {confirmedCount > 0 && ` · ✅ 做对 ${correctCount} 题`}
            {pendingCount > 0 && ` · ⏳ 待确认 ${pendingCount} 题（不计为答错）`}
          </> : <>✅ 做对 {correctCount} 题{selfCount > 0 ? ` · 📖 AI 未判 ${selfCount} 题` : ''}</>}</div>
          <div className="quiz-result-list">
            {result?.results?.map(r => (
              <div key={r.no} className={`quiz-r-item ${r.correct === true ? 'correct' : r.correct === false ? 'wrong' : 'self'}`}>
                <div className="quiz-r-head">
                  <span>第 {r.no} 题</span>
                  <span>{r.correct === true ? '✅ 正确' : r.correct === false ? '❌ 错误' : hasConfirmedCount ? '⏳ 待确认' : '📖 自查'}</span>
                </div>
                <div className="quiz-r-line">你的答案：{r.userAnswer || '（未作答）'}</div>
                {r.correctAnswer && <div className="quiz-r-line">参考答案：{r.correctAnswer}</div>}
                {r.explanation && <div className="quiz-r-line">解析：{r.explanation}</div>}
              </div>
            ))}
          </div>
          <div className="quiz-actions" style={{ justifyContent: 'center' }}>
            <button className="quiz-btn primary" onClick={resetAll}>🔄 再刷一套</button>
          </div>
        </div>
      </div>
    );
  }

  // answering：整卷上下滚动 + 懒加载（英语卷有分页则走分页视图）
  const answeredCount = Object.keys(answers).length;

  // ===== 英语卷分页视图：左右翻页，三种题型分页渲染 =====
  if (englishPages) {
    const page = englishPages[pageIndex] || [];
    return (
      <div className="quiz-container quiz-scroll">
        <div className="quiz-topbar">
          <button className="quiz-back-btn" onClick={backToPick}>⬅ 返回</button>
          <div className="quiz-progress">第 {pageIndex + 1} / {englishPages.length} 页 · 已答 {answeredCount} 题</div>
        </div>

        {page.map((item, idx) => (
          <div key={idx} className="kc-quiz-passage">
            {item.type === 'short_listen' && (
              <>
                <PassageCard
                  passage={{ passageType: 'listening', title: '', content: item.audioScript }}
                  audioUrl={audioMap[item.passageNo]}
                  audioLoading={audioLoading}
                  onLoad={loadPassageAudio}
                />
                <QuestionCard q={item.question} english answered={!!answers[item.question.no]} fb={feedbackMap[item.question.no] || null} loading={loading} onAnswer={handleAnswer} onGrade={handleGrade} />
              </>
            )}
            {item.type === 'long_listen' && (
              <>
                <PassageCard
                  passage={{ passageType: 'listening', title: '', content: item.audioScript }}
                  audioUrl={audioMap[item.passageNo]}
                  audioLoading={audioLoading}
                  onLoad={loadPassageAudio}
                />
                {item.questions.map(q => (
                  <QuestionCard key={q.no} q={q} english answered={!!answers[q.no]} fb={feedbackMap[q.no] || null} loading={loading} onAnswer={handleAnswer} onGrade={handleGrade} />
                ))}
              </>
            )}
            {item.type === 'reading' && (
              <>
                <PassageCard passage={{ passageType: 'reading', title: '', content: item.article }} />
                {item.questions.map(q => (
                  <QuestionCard key={q.no} q={q} english answered={!!answers[q.no]} fb={feedbackMap[q.no] || null} loading={loading} onAnswer={handleAnswer} onGrade={handleGrade} />
                ))}
              </>
            )}
            {item.type === 'plain' && (
              <QuestionCard q={item.question} answered={!!answers[item.question.no]} fb={feedbackMap[item.question.no] || null} loading={loading} onAnswer={handleAnswer} onGrade={handleGrade} />
            )}
          </div>
        ))}

        <div className="quiz-actions">
          <button className="quiz-btn" onClick={() => setPageIndex(i => Math.max(0, i - 1))} disabled={pageIndex === 0}>⬅ 上一页</button>
          <span className="quiz-progress" style={{ flex: 1 }}>{pageIndex + 1} / {englishPages.length}</span>
          <button className="quiz-btn" onClick={() => setPageIndex(i => Math.min(englishPages.length - 1, i + 1))} disabled={pageIndex >= englishPages.length - 1}>下一页 ➡</button>
          <button className="quiz-btn primary" onClick={handleSubmit} disabled={loading}>
            {loading ? '⏳ 交卷中...' : `📮 交卷（${answeredCount}/${quiz.questions.length}）`}
          </button>
        </div>
      </div>
    );
  }

  // ===== 普通卷：整卷滚动 + 懒加载 =====
  return (
    <div className="quiz-container quiz-scroll">
      {/* 吸顶栏：返回 + 进度 */}
      <div className="quiz-topbar">
        <button className="quiz-back-btn" onClick={backToPick}>⬅ 返回</button>
        <div className="quiz-progress">共 {quiz.questions.length} 题 · 已答 {answeredCount} 题</div>
      </div>

      {blocks.slice(0, visibleCount).map(block => (
        block.kind === 'passage' ? (
          <div key={'p' + block.passage.passageNo} className="kc-quiz-passage">
            <PassageCard
              passage={block.passage}
              audioUrl={audioMap[block.passage.passageNo]}
              audioLoading={audioLoading}
              onLoad={loadPassageAudio}
            />
            {block.questions.map(q => (
              <QuestionCard
                key={q.no}
                q={q}
                english={isEnglishPaper}
                answered={!!answers[q.no]}
                fb={feedbackMap[q.no] || null}
                loading={loading}
                onAnswer={handleAnswer}
                onGrade={handleGrade}
              />
            ))}
          </div>
        ) : (
          <QuestionCard
            key={block.q.no}
            q={block.q}
            english={isEnglishPaper}
            answered={!!answers[block.q.no]}
            fb={feedbackMap[block.q.no] || null}
            loading={loading}
            onAnswer={handleAnswer}
            onGrade={handleGrade}
          />
        )
      ))}

      {/* 懒加载哨兵：滚到这里时加载下一批 */}
      {visibleCount < blocks.length && <div className="quiz-sentinel" ref={sentinelRef} />}

      <div className="quiz-actions">
        <button className="quiz-btn primary" onClick={handleSubmit} disabled={loading}>
          {loading ? '⏳ 交卷中...' : `📮 交卷（${answeredCount}/${quiz.questions.length}）`}
        </button>
      </div>
    </div>
  );
}

export default QuizView;
