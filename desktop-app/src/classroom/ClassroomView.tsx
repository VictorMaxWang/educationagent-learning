import { lazy, Suspense, useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import MistakeView from '../study/MistakeView';
import GrowthCompanion from './GrowthCompanion';
import { cancelCourse, createCourse, generateCourse, getCourse, listCourses, pairWechat, resumeCourse, saveOutline, saveProgress, uploadMaterial, wechatStatus } from './api';
import { courseHash, courseIdFromHash } from './navigation';
import { GRADE_LABELS, STATUS_LABELS, type Course, type Domain, type Outline } from './types';
import OutlineEditor from './OutlineEditor';
import CourseExportMenu from './CourseExportMenu';
import CourseImportButton from './CourseImportButton';
import './classroom.css';

// 首页无需下载课件绘制、公式和图表代码；进入已有课堂时再加载播放器。
const CoursePlayer = lazy(() => import('./CoursePlayer'));

const DOMAINS: { id: Domain; label: string; detail: string }[] = [
  { id: 'general', label: '学科学习', detail: '把课本里的难点，一步一步弄明白' },
  { id: 'psychology', label: '认识自己', detail: '认识情绪，练习表达与沟通' },
  { id: 'lifesafety', label: '安全与健康', detail: '学习生活中用得上的安全知识' },
  { id: 'sexedu', label: '青春期成长', detail: '了解身体变化，学会尊重与保护自己' },
];
const SUBJECTS = ['综合', '语文', '数学', '英语', '科学', '物理', '化学', '生物', '历史', '地理', '道德与法治'];

function CreateCourse({ onCreated }: { onCreated(course: Course): void }) {
  const [topic, setTopic] = useState('');
  const [grade, setGrade] = useState(5);
  const [subject, setSubject] = useState('数学');
  const [domain, setDomain] = useState<Domain>('general');
  const [material, setMaterial] = useState<{ text: string; fileName: string; truncated?: boolean } | null>(null);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!topic.trim() || busy) return;
    setBusy(true); setError('');
    try { onCreated(await createCourse({ topic: topic.trim(), grade, subject, domain, sourceText: material?.text, sourceName: material?.fileName })); }
    catch (error) { setError(error instanceof Error ? error.message : '准备课堂失败'); }
    finally { setBusy(false); }
  };
  return <form className="cr-create-card" onSubmit={event => void submit(event)}>
    <div className="cr-section-heading"><div><p className="cr-eyebrow">从一个问题开始</p><h2>今天，你想学会什么？</h2></div><span className="cr-sun" aria-hidden="true">✳</span></div>
    <label className="cr-main-question"><span className="cr-visually-hidden">学习主题</span><textarea rows={2} maxLength={200} placeholder="例如：分数为什么可以通分？遇到同学嘲笑时，我可以怎么说？" value={topic} onChange={event => setTopic(event.target.value)} required disabled={busy} /></label>
    <div className="cr-create-fields"><label>我在读<select value={grade} onChange={event => setGrade(Number(event.target.value))} disabled={busy}>{GRADE_LABELS.map((label, i) => <option value={i + 1} key={label}>{label}</option>)}</select></label><label>学习方向<select value={domain} onChange={event => setDomain(event.target.value as Domain)} disabled={busy}>{DOMAINS.map(item => <option value={item.id} key={item.id}>{item.label}</option>)}</select></label><label>学科<select value={subject} onChange={event => setSubject(event.target.value)} disabled={busy}>{SUBJECTS.map(item => <option value={item} key={item}>{item}</option>)}</select></label></div>
    <div className="cr-material-row"><label className="cr-upload-material">{uploading ? '正在读取资料…' : '＋ 带上我的学习资料'}<input type="file" accept=".pdf,.docx,.txt,.md" disabled={busy || uploading} onChange={async event => {
      const file = event.target.files?.[0]; event.target.value = ''; if (!file) return;
      setUploading(true); setError('');
      try { setMaterial(await uploadMaterial(file)); } catch (error) { setError(error instanceof Error ? error.message : '资料读取失败'); } finally { setUploading(false); }
    }} /></label><span>PDF、Word 或文字资料</span></div>
    {material && <div className="cr-material-chip"><span>📎 {material.fileName}{material.truncated ? '（将使用前部分文字）' : ''}</span><button type="button" aria-label="移除学习资料" onClick={() => setMaterial(null)}>×</button></div>}
    {error && <p className="cr-error" role="alert">{error}</p>}
    <div className="cr-create-footer"><p>先看大纲，再开始。听不懂时，可以随时提问。</p><button className="cr-button" type="submit" disabled={busy || uploading || !topic.trim()}>{busy ? '正在准备…' : '为我准备一堂课 →'}</button></div>
    <div className="cr-topic-suggestions" aria-label="主题示例"><span>也可以试试</span>{['用菜地的面积理解分数', '用英语介绍我的家乡', '如何向信任的大人表达需要'].map((item, index) => <button type="button" key={item} disabled={busy} onClick={() => { setTopic(item); setSubject(index === 1 ? '英语' : index === 2 ? '综合' : '数学'); setDomain(index === 2 ? 'psychology' : 'general'); }}>{item}</button>)}</div>
  </form>;
}

function WechatConnection() {
  const [bound, setBound] = useState(false);
  const [code, setCode] = useState('');
  const [expiry, setExpiry] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const refresh = useCallback(() => { void wechatStatus().then(result => setBound(result.bound)).catch(() => {}); }, []);
  useEffect(refresh, [refresh]);
  const pair = async () => {
    setBusy(true); setError('');
    try { const result = await pairWechat(); setCode(result.code); setExpiry(Date.now() + result.expiresInSeconds * 1000); }
    catch (error) { setError(error instanceof Error ? error.message : '绑定码暂时不可用'); }
    finally { setBusy(false); }
  };
  return <details className="cr-connection"><summary>手机与微信连接 <span>{bound ? '已绑定微信' : '可连接现有微信机器人'}</span></summary>
    <p>手机打开当前课堂网址即可学习。若使用电脑的局域网地址，请连接同一 Wi-Fi；录音需要 HTTPS。</p>
    <p>微信继续使用你现有的、已经登录的机器人。绑定后可发送「我的课堂」「继续学习」或「课堂提问 你的问题」。</p>
    <div className="cr-inline"><button className="cr-button secondary" disabled={busy} onClick={() => void pair()}>{busy ? '正在生成…' : bound ? '重新连接微信' : '连接微信'}</button><button className="cr-link" onClick={refresh}>检查绑定状态</button></div>
    {code && <div className="cr-pairing-code">在微信里向机器人发送：<code>绑定课堂 {code}</code><span>有效至 {new Date(expiry).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}。请使用手机也能打开的课堂地址生成绑定码。</span></div>}
    {error && <p className="cr-error" role="alert">{error}</p>}
  </details>;
}

export default function ClassroomView({ onOpenNavigation }: { onOpenNavigation?(): void }) {
  const [tab, setTab] = useState<'learn' | 'review' | 'growth'>('learn');
  const [courses, setCourses] = useState<Course[]>([]);
  const [courseId, setCourseId] = useState<string | null>(() => courseIdFromHash(window.location.hash));
  const [course, setCourse] = useState<Course | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [listLoading, setListLoading] = useState(true);
  const [pollRevision, setPollRevision] = useState(0);
  const pendingProgress = useRef<Promise<unknown>>(Promise.resolve());
  const navigationRun = useRef(0);
  const editorDirty = useRef(false);
  const activeCourseId = useRef(courseId);
  activeCourseId.current = courseId;
  const reportEditorDirty = useCallback((dirty: boolean) => { editorDirty.current = dirty; }, []);
  const waitForProgress = useCallback(() => pendingProgress.current, []);
  const acceptCourseChange = useCallback((next: Course) => {
    // 编辑、白板和聊天请求可能晚到；只允许当前课堂且版本不倒退的结果更新画面。
    setCourse(previous => previous?.id === next.id && (next.revision ?? 0) >= (previous.revision ?? 0) ? next : previous);
  }, []);
  const canLeaveEditor = useCallback(() => {
    if (editorDirty.current && !window.confirm('课件还有未保存的修改，确定离开并放弃这些修改吗？')) return false;
    editorDirty.current = false;
    return true;
  }, []);
  const persistProgress = useCallback((id: string, sceneIndex: number, actionIndex: number) => {
    setCourse(previous => previous?.id === id ? { ...previous, progress: { sceneIndex, actionIndex } } : previous);
    // 按操作顺序落库，慢的旧请求不能覆盖快的新位置；返回课堂会等待这一条保存链。
    const write = pendingProgress.current.catch(() => undefined).then(() => saveProgress(id, sceneIndex, actionIndex));
    pendingProgress.current = write;
    void write.catch(error => setError(`学习进度暂未同步：${error instanceof Error ? error.message : '请稍后重试'}`));
  }, []);
  const refreshCourses = useCallback(() => {
    setListLoading(true);
    return listCourses().then(setCourses).catch(error => setError(error instanceof Error ? error.message : '课堂列表加载失败')).finally(() => setListLoading(false));
  }, []);
  useEffect(() => { void refreshCourses(); }, [refreshCourses]);
  useEffect(() => {
    const onHash = () => {
      const nextId = courseIdFromHash(window.location.hash);
      if (nextId !== activeCourseId.current && !canLeaveEditor()) {
        window.history.replaceState(null, '', courseHash(activeCourseId.current));
        return;
      }
      navigationRun.current++; activeCourseId.current = nextId; setCourseId(nextId); setTab('learn');
    };
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!editorDirty.current) return;
      event.preventDefault(); event.returnValue = '';
    };
    const beforeNavigation = (event: Event) => { if (!canLeaveEditor()) event.preventDefault(); };
    window.addEventListener('hashchange', onHash);
    window.addEventListener('beforeunload', beforeUnload);
    window.addEventListener('educationagent:before-navigation', beforeNavigation);
    return () => { window.removeEventListener('hashchange', onHash); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('educationagent:before-navigation', beforeNavigation); };
  }, [canLeaveEditor]);
  // 只为当前课程轮询；AbortController 阻止换课后的旧响应覆盖新画面。
  useEffect(() => {
    if (!courseId) { setCourse(null); setLoading(false); return; }
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    setLoading(true); setCourse(null); setError('');
    const load = async () => {
      try {
        const value = await getCourse(courseId, controller.signal);
        if (controller.signal.aborted) return;
        setCourse(value); setLoading(false);
        if (value.status === 'outlining' || value.status === 'generating') timer = setTimeout(() => void load(), 1800);
      } catch (error) {
        if (!controller.signal.aborted) { setError(error instanceof Error ? error.message : '课堂加载失败'); setLoading(false); }
      }
    };
    void load();
    return () => { controller.abort(); if (timer) clearTimeout(timer); };
  }, [courseId, pollRevision]);
  const openCourse = (id: string | null) => {
    if (id !== courseId && !canLeaveEditor()) return;
    navigationRun.current++;
    activeCourseId.current = id;
    setTab('learn'); setCourseId(id); setError('');
    window.location.hash = courseHash(id);
    if (!id) void refreshCourses();
  };
  const startGeneration = async (outlines: Outline[]) => {
    if (!course) return;
    setBusy(true);
    try {
      await saveOutline(course.id, outlines);
      const result = await generateCourse(course.id);
      setCourse(result);
      // 让同一个有卸载清理的 effect 接管轮询，避免换课后留下后台定时器。
      setPollRevision(previous => previous + 1);
    } finally { setBusy(false); }
  };
  const stopGenerating = async () => {
    if (!course) return;
    setBusy(true); setError('');
    try { setCourse(await cancelCourse(course.id)); setPollRevision(previous => previous + 1); }
    catch (error) { setError(error instanceof Error ? error.message : '停止失败'); }
    finally { setBusy(false); }
  };
  const retryGeneration = async () => {
    if (!course) return;
    setBusy(true); setError('');
    try { setCourse(await generateCourse(course.id)); setPollRevision(previous => previous + 1); }
    catch (error) { setError(error instanceof Error ? error.message : '继续生成失败'); }
    finally { setBusy(false); }
  };
  const selectTab = async (value: typeof tab) => {
    if (value === tab || !canLeaveEditor()) return;
    const run = ++navigationRun.current;
    setTab(value);
    if (value !== 'learn' || tab === 'learn' || !courseId) return;
    setLoading(true); setError('');
    try {
      const fresh = await resumeCourse(courseId, pendingProgress.current);
      if (run === navigationRun.current) setCourse(fresh);
    } catch (error) {
      if (run === navigationRun.current) setError(error instanceof Error ? error.message : '课堂恢复失败，请稍后重试');
    } finally { if (run === navigationRun.current) setLoading(false); }
  };
  return <div className="cr-root">
    <header className="cr-header"><button className="cr-brand" onClick={() => openCourse(null)}><span className="cr-brand-icon" aria-hidden="true">禾</span><span><strong>educationagent</strong><small>让每一个好问题，都有人陪你想</small></span></button><nav className="cr-tabs" aria-label="学习导航">{([['learn', '学习'], ['review', '复习'], ['growth', '成长课堂']] as const).map(([key, label]) => <button key={key} aria-current={tab === key ? 'page' : undefined} onClick={() => selectTab(key)}>{label}</button>)}</nav>{onOpenNavigation && <button className="cr-all-tools" onClick={onOpenNavigation}>更多工具 ☰</button>}</header>
    {tab === 'learn' && !courseId && <div className="cr-home"><section className="cr-welcome"><div><p className="cr-eyebrow">从小学到高中 · 学习，也关心成长</p><h1>把好奇带进课堂，<br />把学会带回生活。</h1><p>面向乡村学生的学习伙伴。讲一讲、试一试，<br className="cr-desktop-break" />无论大人是否在身边，都给每一步探索多一点支持。</p></div><div className="cr-welcome-art" aria-hidden="true"><span className="cr-orbit orbit-one" /><span className="cr-orbit orbit-two" /><span className="cr-art-book">学</span><span className="cr-art-leaf">✦</span><span className="cr-art-note">一个问题，一点进步</span></div></section>
      <CreateCourse onCreated={created => openCourse(created.id)} />
      <section className="cr-library"><div className="cr-section-heading"><div><p className="cr-eyebrow">接着上次的发现</p><h2>我的课堂</h2></div><button className="cr-link" onClick={() => void refreshCourses()}>刷新列表 ↻</button></div>
        <CourseImportButton onImported={imported => { void refreshCourses(); openCourse(imported.id); }} />
        {listLoading ? <p className="cr-muted" role="status">正在找回你的课堂…</p> : !courses.length ? <div className="cr-empty">你的第一堂课，从上面的一个问题开始。</div> : <div className="cr-course-grid">{courses.map(item => <button className="cr-course-card" key={item.id} onClick={() => openCourse(item.id)}><div className="cr-card-top"><span className="cr-course-symbol">{item.domain === 'general' ? '书' : '知'}</span><span className={`cr-status ${item.status}`}>{STATUS_LABELS[item.status]}</span></div><h3>{item.title || item.topic}</h3><p>{GRADE_LABELS[item.grade - 1]} · {item.subject || '综合'} · {DOMAINS.find(domain => domain.id === item.domain)?.label || '学科学习'}</p><div className="cr-course-bottom"><span>{item.updatedAt ? new Date(item.updatedAt).toLocaleDateString('zh-CN') : '新课堂'}</span><strong>走进课堂 ↗</strong></div></button>)}</div>}
      </section><WechatConnection />
    </div>}
    {tab === 'learn' && courseId && <div className="cr-course-page"><button className="cr-link cr-back" onClick={() => openCourse(null)}>← 回到我的课堂</button>{loading ? <div className="cr-empty" role="status">正在打开课堂…</div> : course && <><div className="cr-course-title"><div><p className="cr-eyebrow">{GRADE_LABELS[course.grade - 1]} · {course.subject}</p><h1>{course.title || course.topic}</h1></div><span className={`cr-status ${course.status}`}>{STATUS_LABELS[course.status]}</span></div>
      {(course.status === 'outlining' || course.status === 'generating') && <div className="cr-generation-status" role="status"><span className="cr-pulse" /><div><strong>{course.status === 'outlining' ? '老师正在安排学习步骤' : `已准备 ${course.scenes.length} / ${course.outlines.length} 个步骤`}</strong><p>{course.status === 'outlining' ? '先为你的年级与问题整理一份大纲。' : '已经准备好的页面可以先学，其他内容会继续生成。'}</p></div><button className="cr-link" disabled={busy} onClick={() => void stopGenerating()}>停止生成</button></div>}
      {course.error && <p className="cr-error" role="alert">{course.error}</p>}
      {course.status === 'outline_ready' && <OutlineEditor key={`${course.id}-outline`} initial={course.outlines} busy={busy} onConfirm={startGeneration} />}
      {(course.status === 'cancelled' || course.status === 'error') && <div className="cr-empty"><p>这堂课的生成已停止。已完成的内容仍可以学习。</p><button className="cr-button secondary" disabled={busy} onClick={() => void retryGeneration()}>{busy ? '正在继续…' : '继续准备未完成的内容'}</button></div>}
      {(course.sourceName || course.sources?.length || course.retrievalNote) && <details className="cr-sources"><summary>课程参考资料</summary>{course.sourceName && <p>你提供的资料：{course.sourceName}</p>}{course.sources?.map((source, index) => <p key={index}>{source.file || source.source || '专题资料'}</p>)}{course.retrievalNote && <p>{course.retrievalNote}</p>}</details>}
      {course.scenes.length > 0 && <><CourseExportMenu course={course} disabled={busy || course.status === 'generating'} /><Suspense fallback={<div className="cr-empty">正在打开学习内容…</div>}><CoursePlayer key={course.id} course={course} onProgress={persistProgress} onCourseChange={acceptCourseChange} onEditorDirtyChange={reportEditorDirty} waitForProgress={waitForProgress} /></Suspense></>}
    </>}</div>}
    {tab === 'review' && <div className="cr-embedded-page"><div className="cr-section-heading"><div><p className="cr-eyebrow">学过，再试一次</p><h1>把不会的，慢慢变成会的。</h1><p>这里收集新课堂中的错题。重新练习，会继续使用原有的判题与错题功能。</p></div></div><MistakeView userId="classroom" embedded /></div>}
    {tab === 'growth' && <GrowthCompanion />}
    {error && <p className="cr-error cr-page-error" role="alert">{error}<button className="cr-link" onClick={() => setError('')}>收起</button></p>}
  </div>;
}
