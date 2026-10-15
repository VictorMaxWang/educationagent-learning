import { useState, useRef, useEffect, useCallback } from 'react';
import {
  uploadFile,
  saveKnowledge, listKnowledge, viewKnowledge, deleteKnowledge, searchKnowledge, askKnowledge,
  getKnowledge, toggleKnowledgeFav, getKnowledgeQuestions, toggleQuestionFav, getFavQuestions,
} from '../api/chatApi';
import { fileToBase64 } from '../utils/file';

const TYPE_LABEL = { single: '单选题', multi: '多选题', judge: '判断题', fill: '填空题', short: '简答题' };

function parseOption(opt) {
  const m = String(opt).match(/^([A-Za-z])[.、．)\s]*\s*(.*)$/);
  return m ? { letter: m[1].toUpperCase(), text: m[2] } : { letter: '', text: String(opt) };
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

/**
 * 知识中心：资料库（个人文档 RAG）/ 问答 / 学习掌握（刷题知识点）
 */
function KnowledgeCenterView({ userId }) {
  const [tab, setTab] = useState('library'); // library | ask | mastery

  return (
    <div className="quiz-container quiz-scroll">
      <div className="learn-header">
        <h3>📚 知识中心</h3>
      </div>
      <div className="kc-tabs">
        <button className={`kc-tab ${tab === 'library' ? 'active' : ''}`} onClick={() => setTab('library')}>📄 资料库</button>
        <button className={`kc-tab ${tab === 'ask' ? 'active' : ''}`} onClick={() => setTab('ask')}>💬 问答</button>
        <button className={`kc-tab ${tab === 'mastery' ? 'active' : ''}`} onClick={() => setTab('mastery')}>📊 学习掌握</button>
      </div>
      {tab === 'library' && <LibraryTab userId={userId} />}
      {tab === 'ask' && <AskTab userId={userId} />}
      {tab === 'mastery' && <MasteryTab userId={userId} />}
    </div>
  );
}

// ======================== 资料库 ========================

function LibraryTab({ userId }) {
  const [files, setFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [viewing, setViewing] = useState(null);   // 正在查看的文件名
  const [viewContent, setViewContent] = useState('');
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState('');
  const fileInputRef = useRef(null);

  const load = useCallback(async () => {
    try {
      const res = await listKnowledge({ userId });
      setFiles(Array.isArray(res) ? res : []);
    } catch (err) {
      console.error('加载知识库失败：', err.message);
    }
  }, [userId]);
  useEffect(() => { load(); }, [load]);

  const handleFile = async (f) => {
    if (!f) return;
    setUploading(true);
    setError('');
    try {
      const data = await fileToBase64(f);
      const up = await uploadFile({ fileName: f.name, mime: f.type || 'application/octet-stream', data });
      if (!up?.fileId) { setError('上传失败：' + (up?.error || '未知错误')); return; }
      const res = await saveKnowledge({ userId, fileId: up.fileId });
      if (!res?.success) { setError(res?.error || '保存失败'); return; }
      load();
    } catch (err) {
      setError('保存失败：' + err.message);
    } finally {
      setUploading(false);
    }
  };

  const handleView = async (name) => {
    if (viewing === name) { setViewing(null); setViewContent(''); return; }
    setViewing(name);
    try {
      const res = await viewKnowledge({ userId, fileName: name });
      setViewContent(res?.content || '（无内容）');
    } catch (err) {
      setViewContent('加载失败：' + err.message);
    }
  };

  const handleDelete = async (name) => {
    if (!window.confirm(`确定删除「${name}」及其索引？`)) return;
    try {
      await deleteKnowledge({ userId, fileName: name });
      if (viewing === name) { setViewing(null); setViewContent(''); }
      load();
    } catch (err) {
      setError('删除失败：' + err.message);
    }
  };

  const handleSearch = async () => {
    if (!query.trim()) return;
    try {
      const res = await searchKnowledge({ userId, query: query.trim() });
      setResults(Array.isArray(res?.results) ? res.results : []);
      setSearched(true);
    } catch (err) {
      setError('搜索失败：' + err.message);
    }
  };

  return (
    <div className="kc-body">
      {/* 上传区 */}
      <div
        className={`convert-dropzone kc-upload ${dragOver ? 'dragover' : ''}`}
        onClick={() => fileInputRef.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => { e.preventDefault(); setDragOver(false); handleFile(e.dataTransfer?.files?.[0]); }}
      >
        <input type="file" ref={fileInputRef} style={{ display: 'none' }}
          onChange={(e) => { handleFile(e.target.files?.[0]); e.target.value = ''; }} />
        <div className="convert-drop-icon">📚</div>
        <div className="convert-drop-title">{uploading ? '⏳ 上传并索引中...' : '点击或拖拽文档加入知识库'}</div>
        <div className="convert-drop-meta">支持 PDF / Word / TXT · 索引进向量库后，聊天时我会自动参考</div>
      </div>

      {error && <div className="convert-error">⚠️ {error}</div>}

      {/* 资料搜索 */}
      <div className="kc-search-row">
        <input className="kc-search-input" value={query} onChange={(e) => setQuery(e.target.value)}
          placeholder="🔍 在资料中搜索关键词..." onKeyDown={(e) => e.key === 'Enter' && handleSearch()} />
        <button className="quiz-btn" onClick={handleSearch}>搜索</button>
      </div>
      {searched && (
        <div className="kc-search-results">
          {results.length === 0
            ? <div className="quiz-tip">没有匹配的资料片段</div>
            : results.map((r, i) => <div key={i} className="kc-chunk">{r}</div>)}
        </div>
      )}

      {/* 文档列表 */}
      <div className="kc-section-title">已索引文档（{files.length}）</div>
      {files.length === 0 ? (
        <div className="quiz-tip">还没有上传文档，先上传一份吧</div>
      ) : (
        <div className="kc-doc-list">
          {files.map(f => (
            <div key={f.fileName} className="kc-doc-item">
              <span className="kc-doc-name">📄 {f.fileName}</span>
              <span className="kc-doc-meta">{formatSize(f.size)}</span>
              <button className="quiz-btn" onClick={() => handleView(f.fileName)}>{viewing === f.fileName ? '收起' : '查看'}</button>
              <button className="quiz-btn" onClick={() => handleDelete(f.fileName)}>🗑 删除</button>
            </div>
          ))}
        </div>
      )}

      {viewing && (
        <div className="kc-view">
          <div className="kc-view-title">📖 {viewing}</div>
          <pre className="kc-view-content">{viewContent}</pre>
        </div>
      )}
    </div>
  );
}

// ======================== 问答 ========================

function AskTab({ userId }) {
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null); // { answer, sources }

  const ask = async () => {
    if (!question.trim()) return;
    setBusy(true);
    setResult(null);
    try {
      const res = await askKnowledge({ userId, question: question.trim() });
      setResult({ answer: res?.answer || '（无回答）', sources: Array.isArray(res?.sources) ? res.sources : [] });
    } catch (err) {
      setResult({ answer: '问答失败：' + err.message, sources: [] });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="kc-body">
      <div className="kc-ask-input">
        <input className="kc-search-input" value={question} onChange={(e) => setQuestion(e.target.value)}
          placeholder="问：我的资料里...（基于你的知识库回答）" onKeyDown={(e) => e.key === 'Enter' && ask()} />
        <button className="quiz-btn primary" onClick={ask} disabled={busy}>{busy ? '⏳ 检索中...' : '💬 提问'}</button>
      </div>
      {result && (
        <div className="kc-answer">
          <div className="kc-answer-text">{result.answer}</div>
          {result.sources.length > 0 && (
            <div className="kc-answer-sources">
              <div className="kc-answer-src-title">📎 参考片段</div>
              {result.sources.map((s, i) => <div key={i} className="kc-chunk">{s}</div>)}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ======================== 学习掌握（刷题知识点） ========================

function MasteryTab({ userId }) {
  const [knowledge, setKnowledge] = useState([]);
  const [favs, setFavs] = useState([]);
  const [expanded, setExpanded] = useState(null);
  const [questions, setQuestions] = useState([]);
  const [loading, setLoading] = useState(false);
  // 分档折叠：薄弱区默认展开，待巩固/已掌握默认收起（突出重点）
  const [tierCollapsed, setTierCollapsed] = useState({ weak: false, mid: true, strong: true });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [k, f] = await Promise.all([getKnowledge({ userId }), getFavQuestions({ userId })]);
      setKnowledge(Array.isArray(k) ? k : []);
      setFavs(Array.isArray(f) ? f : []);
    } catch (err) {
      console.error('加载学习掌握失败：', err.message);
    } finally {
      setLoading(false);
    }
  }, [userId]);
  useEffect(() => { load(); }, [load]);

  const expand = async (kp) => {
    if (expanded === kp) { setExpanded(null); setQuestions([]); return; }
    setExpanded(kp);
    setQuestions([]); // 先清空，避免短暂显示上一个知识点的题目
    try {
      setQuestions(await getKnowledgeQuestions({ userId, knowledgePoint: kp }));
    } catch (err) {
      setQuestions([]);
    }
  };

  const toggleKpFav = async (kp) => {
    try {
      const res = await toggleKnowledgeFav({ userId, knowledgePoint: kp });
      setKnowledge(prev => prev.map(x => (x.knowledgePoint === kp ? { ...x, isFav: res.fav } : x)));
    } catch (err) { console.error(err); }
  };

  const toggleQFav = async (q) => {
    try {
      const res = await toggleQuestionFav({ userId, questionId: q.questionId });
      setQuestions(prev => prev.map(x => (x.questionId === q.questionId ? { ...x, isFav: res.fav } : x)));
      await load();
    } catch (err) { console.error(err); }
  };

  const accClass = a => (a < 60 ? 'weak' : a < 80 ? 'mid' : 'strong');

  // 按正确率排序 + 分档（薄弱 <60% / 待巩固 60-80% / 已掌握 >=80%）
  const sorted = [...knowledge].sort((a, b) => a.accuracy - b.accuracy);
  const weak = sorted.filter(k => k.accuracy < 60);
  const mid = sorted.filter(k => k.accuracy >= 60 && k.accuracy < 80);
  const strong = sorted.filter(k => k.accuracy >= 80);
  const avg = sorted.length
    ? Math.round(sorted.reduce((s, k) => s + (k.accuracy || 0), 0) / sorted.length)
    : 0;

  /** 单个知识点行：进度条 + 正确率 + 题数 + 收藏 + 可展开题目 */
  const renderKp = (k) => (
    <div key={k.knowledgePoint} className="kc-kp-wrap">
      <div className="knowledge-row">
        <button className="knowledge-name" onClick={() => expand(k.knowledgePoint)}>
          {k.knowledgePoint}{expanded === k.knowledgePoint ? ' ▾' : ' ▸'}
        </button>
        <div className="knowledge-bar">
          <div className={`knowledge-bar-fill knowledge-bar-${accClass(k.accuracy)}`}
            style={{ width: Math.min(100, k.accuracy || 0) + '%' }} />
        </div>
        <span className={`knowledge-acc ${accClass(k.accuracy)}`}>{k.accuracy}%</span>
        <span className="knowledge-count">{k.correctCount}/{k.questionCount}</span>
        <button className={`fav-btn ${k.isFav ? 'fav' : ''}`} onClick={() => toggleKpFav(k.knowledgePoint)}>
          {k.isFav ? '⭐' : '☆'}
        </button>
      </div>
      {/* 展开的题目紧跟知识点行下方 */}
      {expanded === k.knowledgePoint && (
        <div className="kc-kp-questions">
          {questions.length === 0 ? (
            <div className="quiz-tip">该知识点下暂无题目</div>
          ) : (
            <div className="quiz-result-list">
              {questions.map(q => (
                <div key={q.questionId} className="quiz-r-item self">
                  <div className="quiz-r-head">
                    <span>第 {q.no} 题 · {TYPE_LABEL[q.type] || q.type}</span>
                    <button className={`fav-btn ${q.isFav ? 'fav' : ''}`} onClick={() => toggleQFav(q)}>{q.isFav ? '⭐' : '☆'}</button>
                  </div>
                  <div className="quiz-r-line"><b>题目：</b>{q.stem}</div>
                  {q.options?.length > 0 && (
                    <div className="quiz-r-line">
                      {q.options.map((o, idx) => {
                        const { letter, text } = parseOption(o);
                        return <div key={letter || 'o' + idx} style={{ marginLeft: 8 }}>{letter}. {text}</div>;
                      })}
                    </div>
                  )}
                  {q.answer && <div className="quiz-r-line">参考答案：{q.answer}</div>}
                  {q.explanation && <div className="quiz-r-line">解析：{q.explanation}</div>}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );

  /** 分档区块：可折叠头 + 知识点列表 */
  const renderTier = (key, icon, title, items) => (
    <div key={key} className="mastery-tier">
      <div className="mastery-tier-head" onClick={() => setTierCollapsed(t => ({ ...t, [key]: !t[key] }))}>
        <span className="mastery-tier-arrow">{tierCollapsed[key] ? '▶' : '▼'}</span>
        <span className="mastery-tier-title">{icon} {title}</span>
        <span className="mastery-tier-count">{items.length} 个知识点</span>
      </div>
      {!tierCollapsed[key] && items.length > 0 && (
        <div className="knowledge-list">
          {items.map(renderKp)}
        </div>
      )}
    </div>
  );

  return (
    <div className="kc-body">
      {loading && knowledge.length === 0 ? (
        <div className="quiz-tip">⏳ 加载中...</div>
      ) : knowledge.length === 0 ? (
        <div className="quiz-tip">还没有刷题记录，先去刷题吧～</div>
      ) : (
        <>
          {/* ① 掌握度总览卡 */}
          <div className="mastery-overview">
            <div className="mastery-overview-head">
              <span className="mastery-overview-title">📊 整体掌握度</span>
              <span className="mastery-overview-pct">{avg}%</span>
            </div>
            <div className="mastery-bar">
              <div className="mastery-bar-fill" style={{ width: avg + '%' }} />
            </div>
            <div className="mastery-overview-tiers">
              <span className="tier-chip tier-chip-weak">🔴 薄弱 {weak.length}</span>
              <span className="tier-chip tier-chip-mid">🟡 待巩固 {mid.length}</span>
              <span className="tier-chip tier-chip-strong">🟢 已掌握 {strong.length}</span>
            </div>
            {weak.length > 0 && (
              <div className="mastery-suggest">🎯 建议优先复习：薄弱知识点 {weak.length} 个</div>
            )}
          </div>

          {/* ② 分档列表（薄弱默认展开，待巩固/已掌握默认折叠） */}
          {renderTier('weak', '🔴', '薄弱区', weak)}
          {renderTier('mid', '🟡', '待巩固区', mid)}
          {renderTier('strong', '🟢', '已掌握区', strong)}
        </>
      )}

      <div className="knowledge-block">
        <h4>📌 收藏的题目</h4>
        {favs.length === 0 ? (
          <div className="quiz-tip">还没有收藏的题目</div>
        ) : (
          <div className="quiz-result-list">
            {favs.map(q => (
              <div key={q.questionId} className="quiz-r-item self">
                <div className="quiz-r-head">
                  <span>{q.paperTitle || '未命名试卷'} · 第 {q.no} 题 · {TYPE_LABEL[q.type] || q.type}</span>
                </div>
                <div className="quiz-r-line"><b>题目：</b>{q.stem}</div>
                {q.options?.length > 0 && (
                  <div className="quiz-r-line">
                    {q.options.map((o, idx) => {
                      const { letter, text } = parseOption(o);
                      return <div key={letter || 'o' + idx} style={{ marginLeft: 8 }}>{letter}. {text}</div>;
                    })}
                  </div>
                )}
                {q.knowledgePoint && <div className="quiz-r-line">知识点：{q.knowledgePoint}</div>}
                {q.answer && <div className="quiz-r-line">参考答案：{q.answer}</div>}
                {q.explanation && <div className="quiz-r-line">解析：{q.explanation}</div>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export default KnowledgeCenterView;
