import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { getMistakes, redoMistake, removeMistake } from '../api/chatApi';
import { courseHash, newLocalId } from '../classroom/navigation';

const TYPE_LABEL = { single: '单选题', multi: '多选题', judge: '判断题', fill: '填空题', short: '简答题' };
const revisionHref = revision => typeof revision?.newCourseId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(revision.newCourseId)
  ? courseHash(revision.newCourseId, typeof revision.newSceneId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(revision.newSceneId) ? revision.newSceneId : null) : null;

function parseOption(opt) {
  const m = String(opt).match(/^([A-Za-z])[.、．)\s]*\s*(.*)$/);
  return m ? { letter: m[1].toUpperCase(), text: m[2] } : { letter: '', text: String(opt) };
}

// 错题日期 → 友好标签（今天 / 昨天 / N天前 / 具体日期）
function mistakeDateLabel(iso) {
  if (!iso) return '未知日期';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const that = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diff = Math.round((today - that) / 86400000);
  if (diff === 0) return '今天';
  if (diff === 1) return '昨天';
  if (diff > 1 && diff < 7) return `${diff} 天前`;
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * 错题本：列出做错的题，可重做（答对自动移除）或手动移除
 */
function MistakeView({ userId, embedded }) {
  const [mistakes, setMistakes] = useState([]);
  const [listLoading, setListLoading] = useState(false);
  const [redoBusy, setRedoBusy] = useState(false);
  const loading = listLoading || redoBusy;
  const [redoId, setRedoId] = useState(null);      // 正在重做的 questionId
  const [redoSelected, setRedoSelected] = useState([]);
  const [redoText, setRedoText] = useState('');
  const [feedback, setFeedback] = useState(null);  // 重做判分结果
  const [typeFilter, setTypeFilter] = useState('all'); // 题型筛选：all|single|multi|judge|fill|short
  const [collapsed, setCollapsed] = useState({});   // 折叠的日期组 { label: true }
  const alive = useRef(true), activeUser = useRef(userId), loadRun = useRef(0), redoRun = useRef(0), currentRedo = useRef(null), redoBusyRef = useRef(false);
  const redoRequests = useRef(new Map()), removing = useRef(new Set());
  activeUser.current = userId;
  useEffect(() => { alive.current = true; return () => { alive.current = false; loadRun.current++; redoRun.current++; }; }, []);
  useEffect(() => { redoRun.current++; currentRedo.current = null; redoBusyRef.current = false; setRedoId(null); setRedoBusy(false); setFeedback(null); setMistakes([]); redoRequests.current.clear(); }, [userId]);

  const load = useCallback(async () => {
    const run = ++loadRun.current, owner = userId;
    const current = () => alive.current && owner === activeUser.current && run === loadRun.current;
    setListLoading(true);
    try {
      const res = await getMistakes({ userId });
      if (current()) setMistakes(Array.isArray(res) ? res : []);
    } catch (err) {
      if (current()) alert('加载错题失败：' + err.message);
    } finally {
      if (current()) setListLoading(false);
    }
  }, [userId]);

  useEffect(() => { load(); }, [load]);

  // 题型筛选
  const filteredMistakes = useMemo(() => {
    if (typeFilter === 'all') return mistakes;
    return mistakes.filter(m => m.type === typeFilter);
  }, [mistakes, typeFilter]);

  // 按错题日期分组（后端已按 answeredAt 倒序，新日期在前）
  const groups = useMemo(() => {
    const g = {};
    filteredMistakes.forEach(m => {
      const label = mistakeDateLabel(m.answeredAt);
      (g[label] = g[label] || []).push(m);
    });
    return g;
  }, [filteredMistakes]);

  // 折叠 / 展开某个日期组
  const toggleGroup = (label) => {
    setCollapsed(prev => ({ ...prev, [label]: !prev[label] }));
  };

  const toggleRedo = (m) => {
    if (m.revision) return;
    // 切题立即撤销旧响应对界面的所有权，不能把A题的解析显示在B题下面。
    redoRun.current++; redoBusyRef.current = false; setRedoBusy(false);
    if (redoId === m.questionId) {
      currentRedo.current = null;
      setRedoId(null);
      setFeedback(null);
      return;
    }
    currentRedo.current = m.questionId;
    setRedoId(m.questionId);
    setRedoSelected([]);
    setRedoText('');
    setFeedback(null);
  };

  const answerOf = (m) => {
    if (m.type === 'fill' || m.type === 'short') return redoText.trim();
    if (m.type === 'multi') return [...redoSelected].sort().join('');
    return redoSelected[0] || '';
  };

  const handleSelect = (m, v) => {
    if (m.type === 'multi') {
      setRedoSelected(prev => (prev.includes(v) ? prev.filter(x => x !== v) : [...prev, v]));
    } else {
      setRedoSelected([v]);
    }
  };

  const submitRedo = async (m) => {
    if (m.revision || redoBusyRef.current || currentRedo.current !== m.questionId) return;
    const answer = answerOf(m);
    if (!answer) return;
    const run = ++redoRun.current, owner = userId;
    const owned = () => alive.current && activeUser.current === owner && redoRun.current === run;
    const current = () => owned() && currentRedo.current === m.questionId;
    const previous = redoRequests.current.get(m.questionId);
    const request = previous?.answer === answer ? previous : { answer, requestId: newLocalId() };
    redoRequests.current.set(m.questionId, request);
    redoBusyRef.current = true; setRedoBusy(true);
    try {
      const res = await redoMistake({ userId, quizId: m.quizId, questionId: m.questionId, answer, requestId: request.requestId });
      // 普通错题重做的明确待确认回执已处理完该请求；主动重新核对须创建新编号。
      // 网络异常则保留编号恢复未知结果。即使切到别题，迟到回执也只结清自己的请求，
      // 不能删除同题后来提交的新请求；界面反馈仍由下面的 current() 判定归属。
      if (res?.correct === null && alive.current && activeUser.current === owner
          && redoRequests.current.get(m.questionId) === request) redoRequests.current.delete(m.questionId);
      if (!current()) return;
      setFeedback(res);
      if (res.removed) {
        // 答对 → 移出错题本
        loadRun.current++; setListLoading(false);
        setMistakes(prev => prev.filter(x => x.questionId !== m.questionId));
        currentRedo.current = null; setRedoId(null);
      }
    } catch (err) {
      if (current()) alert('重做失败：' + err.message);
    } finally {
      if (owned()) { redoBusyRef.current = false; setRedoBusy(false); }
    }
  };

  const handleRemove = async (m) => {
    if (removing.current.has(m.questionId)) return;
    if (!window.confirm('确定将该题移出错题本？')) return;
    const owner = userId; removing.current.add(m.questionId);
    try {
      await removeMistake({ userId, questionId: m.questionId });
      if (!alive.current || owner !== activeUser.current) return;
      // 使删除之前发出的列表读取失效，迟到列表不能把已移除项插回来。
      loadRun.current++; setListLoading(false);
      setMistakes(prev => prev.filter(x => x.questionId !== m.questionId));
      if (currentRedo.current === m.questionId) { redoRun.current++; currentRedo.current = null; redoBusyRef.current = false; setRedoId(null); setRedoBusy(false); setFeedback(null); }
    } catch (err) {
      if (alive.current && owner === activeUser.current) alert('移除失败：' + err.message);
    } finally { removing.current.delete(m.questionId); }
  };

  const answerText = (m) => {
    if (m.type === 'judge') return m.userAnswer || '';
    if (m.type === 'fill' || m.type === 'short') return m.userAnswer || '';
    return m.userAnswer || '';
  };

  return (
    <div className="quiz-container quiz-scroll">
      {!embedded && (
        <div className="learn-header">
          <h3>❌ 错题本</h3>
          <button className="quiz-btn" onClick={load} disabled={loading}>🔄 刷新</button>
        </div>
      )}

      {/* 题型筛选（嵌入刷题练习时，刷新并入行尾最右，小号图标） */}
      <div className="mistake-filters">
        {[
          ['all', '全部'],
          ['single', '单选'],
          ['multi', '多选'],
          ['judge', '判断'],
          ['fill', '填空'],
          ['short', '简答'],
        ].map(([key, label]) => (
          <button
            key={key}
            className={`mistake-filter ${typeFilter === key ? 'active' : ''}`}
            onClick={() => setTypeFilter(key)}
          >
            {label}
          </button>
        ))}
        {embedded && (
          <button className="mistake-refresh-sm" onClick={load} disabled={loading} title="刷新">
            🔄 刷新
          </button>
        )}
      </div>

      {mistakes.length === 0 ? (
        <div className="empty-tip" style={{ marginTop: 40 }}>
          {loading ? '⏳ 加载中...' : '🎉 暂无错题，继续保持！'}
        </div>
      ) : filteredMistakes.length === 0 ? (
        <div className="empty-tip" style={{ marginTop: 40 }}>当前筛选下没有错题</div>
      ) : (
        Object.entries(groups).map(([label, list]) => (
          <div key={label} className="mistake-group">
            <div className="mistake-group-date" role="button" tabIndex={0} aria-expanded={!collapsed[label]} onClick={() => toggleGroup(label)} onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggleGroup(label); } }}>
              <span className="mistake-group-arrow">{collapsed[label] ? '▶' : '▼'}</span>
              🗓 {label}
              <span className="mistake-group-count">{list.length} 题</span>
            </div>
            {!collapsed[label] && (
            <div className="quiz-result-list">
              {list.map(m => (
                <div key={m.questionId} className={`quiz-r-item ${m.revision ? 'info' : 'wrong'}`}>
              <div className="quiz-r-head">
                <span>{m.paperTitle || '未命名试卷'} · 第 {m.no} 题 · {TYPE_LABEL[m.type] || m.type}</span>
                <span>{m.knowledgePoint ? `🏷 ${m.knowledgePoint}` : ''}</span>
              </div>
              <div className="quiz-r-line"><b>题目：</b>{m.stem}</div>
              {m.revision && <div className="quiz-feedback info" role="status"><strong>内容已更新</strong><p>这条记录保留当时的作答，不用它判断现在是否掌握。可以打开当前练习继续学习。</p>{revisionHref(m.revision) && <a className="quiz-btn" href={revisionHref(m.revision)}>打开当前练习</a>}</div>}
              {m.options?.length > 0 && (
                <div className="quiz-r-line">
                  {m.options.map((o, idx) => {
                    const { letter, text } = parseOption(o);
                    return <div key={letter || 'o' + idx} style={{ marginLeft: 8 }}>{letter}. {text}</div>;
                  })}
                </div>
              )}
              <div className="quiz-r-line" style={m.revision ? undefined : { color: '#DC2626' }}><b>{m.revision ? '当时的作答：' : '我的答案：'}</b>{answerText(m) || '（未作答）'}</div>

              {/* 重做区域 */}
              {!m.revision && redoId === m.questionId && (
                <div className="mistake-redo">
                  {m.type === 'judge' ? (
                    ['对', '错'].map(v => (
                      <label key={v} className={`quiz-option ${redoSelected.includes(v) ? 'checked' : ''}`}>
                        <input type="radio" name={`redo-${m.questionId}`} checked={redoSelected.includes(v)} onChange={() => handleSelect(m, v)} />
                        <span>{v}</span>
                      </label>
                    ))
                  ) : (m.type === 'single' || m.type === 'multi') ? (
                    m.options.map((o, idx) => {
                      const { letter, text } = parseOption(o);
                      const checked = redoSelected.includes(letter);
                      return (
                        <label key={letter || 'o' + idx} className={`quiz-option ${checked ? 'checked' : ''}`}>
                          <input type={m.type === 'multi' ? 'checkbox' : 'radio'} name={`redo-${m.questionId}`} checked={checked} onChange={() => handleSelect(m, letter)} />
                          <span className="quiz-opt-letter">{letter}.</span>
                          <span>{text}</span>
                        </label>
                      );
                    })
                  ) : (
                    <textarea className="quiz-textarea" value={redoText} maxLength={m.type === 'fill' ? 2000 : 4000} onChange={e => setRedoText(e.target.value)}
                      placeholder={m.type === 'fill' ? '填写答案...' : '作答...'} rows={m.type === 'short' ? 3 : 1} />
                  )}

                  <div className="quiz-actions" style={{ marginTop: 10 }}>
                    <button className="quiz-btn" onClick={() => toggleRedo(m)}>取消</button>
                    <button className="quiz-btn primary" onClick={() => submitRedo(m)} disabled={loading || !answerOf(m)}>
                      {loading ? '⏳ 判分中...' : feedback?.correct === null ? '🔄 重新核对' : '📝 提交重做'}
                    </button>
                  </div>

                  {feedback && (
                    <div className={`quiz-feedback ${feedback.correct === true ? 'correct' : feedback.correct === false ? 'wrong' : 'info'}`}>
                      <div className="quiz-fb-head">
                        {feedback.correct === true ? '✅ 重做正确，已移出错题本！' : feedback.correct === false ? '❌ 仍未答对' : feedback.correct === null ? '⏳ 待确认（不计为答错）' : '📖 无法判分'}
                      </div>
                      {feedback.correctAnswer && <div className="quiz-fb-line">正确答案：{feedback.correctAnswer}</div>}
                      {feedback.explanation && <div className="quiz-fb-line">解析：{feedback.explanation}</div>}
                    </div>
                  )}
                </div>
              )}

              <div className="quiz-actions" style={{ marginTop: 10 }}>
                {!m.revision && redoId !== m.questionId && (
                  <button className="quiz-btn" onClick={() => toggleRedo(m)}>🔁 重做</button>
                )}
                <button className="quiz-btn" onClick={() => handleRemove(m)}>🗑 移除</button>
              </div>
              </div>
              ))}
            </div>
            )}
          </div>
        ))
      )}
    </div>
  );
}

export default MistakeView;
