import { useState, useEffect, useMemo, useCallback } from 'react';
import { getMistakes, redoMistake, removeMistake } from '../api/chatApi';

const TYPE_LABEL = { single: '单选题', multi: '多选题', judge: '判断题', fill: '填空题', short: '简答题' };
const revisionHref = revision => typeof revision?.newCourseId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(revision.newCourseId)
  ? `#/classroom/${encodeURIComponent(revision.newCourseId)}` : null;

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
  const [loading, setLoading] = useState(false);
  const [redoId, setRedoId] = useState(null);      // 正在重做的 questionId
  const [redoSelected, setRedoSelected] = useState([]);
  const [redoText, setRedoText] = useState('');
  const [feedback, setFeedback] = useState(null);  // 重做判分结果
  const [typeFilter, setTypeFilter] = useState('all'); // 题型筛选：all|single|multi|judge|fill|short
  const [collapsed, setCollapsed] = useState({});   // 折叠的日期组 { label: true }

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await getMistakes({ userId });
      setMistakes(Array.isArray(res) ? res : []);
    } catch (err) {
      alert('加载错题失败：' + err.message);
    } finally {
      setLoading(false);
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
    if (redoId === m.questionId) {
      setRedoId(null);
      setFeedback(null);
      return;
    }
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
    if (m.revision) return;
    const answer = answerOf(m);
    if (!answer) return;
    setLoading(true);
    try {
      const res = await redoMistake({ userId, quizId: m.quizId, questionId: m.questionId, answer });
      setFeedback(res);
      if (res.removed) {
        // 答对 → 移出错题本
        setMistakes(prev => prev.filter(x => x.questionId !== m.questionId));
        setRedoId(null);
      }
    } catch (err) {
      alert('重做失败：' + err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleRemove = async (m) => {
    if (!window.confirm('确定将该题移出错题本？')) return;
    try {
      await removeMistake({ userId, questionId: m.questionId });
      setMistakes(prev => prev.filter(x => x.questionId !== m.questionId));
    } catch (err) {
      alert('移除失败：' + err.message);
    }
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
            <div className="mistake-group-date" onClick={() => toggleGroup(label)}>
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
              {m.revision && <div className="quiz-feedback info" role="status"><strong>历史题目已修订</strong><p>{m.revision.reason || '原练习存在题面或答案问题。'}这条记录保留当时的作答，不用它判断现在是否掌握。</p>{revisionHref(m.revision) && <a className="quiz-btn" href={revisionHref(m.revision)}>打开修订版课堂</a>}</div>}
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
                        <input type="radio" checked={redoSelected.includes(v)} onChange={() => handleSelect(m, v)} />
                        <span>{v}</span>
                      </label>
                    ))
                  ) : (m.type === 'single' || m.type === 'multi') ? (
                    m.options.map((o, idx) => {
                      const { letter, text } = parseOption(o);
                      const checked = redoSelected.includes(letter);
                      return (
                        <label key={letter || 'o' + idx} className={`quiz-option ${checked ? 'checked' : ''}`}>
                          <input type={m.type === 'multi' ? 'checkbox' : 'radio'} checked={checked} onChange={() => handleSelect(m, letter)} />
                          <span className="quiz-opt-letter">{letter}.</span>
                          <span>{text}</span>
                        </label>
                      );
                    })
                  ) : (
                    <textarea className="quiz-textarea" value={redoText} onChange={e => setRedoText(e.target.value)}
                      placeholder={m.type === 'fill' ? '填写答案...' : '作答...'} rows={m.type === 'short' ? 3 : 1} />
                  )}

                  <div className="quiz-actions" style={{ marginTop: 10 }}>
                    <button className="quiz-btn" onClick={() => toggleRedo(m)}>取消</button>
                    <button className="quiz-btn primary" onClick={() => submitRedo(m)} disabled={loading || !answerOf(m)}>
                      {loading ? '⏳ 判分中...' : '📝 提交重做'}
                    </button>
                  </div>

                  {feedback && (
                    <div className={`quiz-feedback ${feedback.correct === true ? 'correct' : feedback.correct === false ? 'wrong' : 'info'}`}>
                      <div className="quiz-fb-head">
                        {feedback.correct === true ? '✅ 重做正确，已移出错题本！' : feedback.correct === false ? '❌ 仍未答对' : '📖 无法判分'}
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
