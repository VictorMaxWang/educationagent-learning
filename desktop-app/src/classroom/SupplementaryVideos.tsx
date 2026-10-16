import { useEffect, useRef, useState } from 'react';
import { getRecommendations, refreshRecommendations, sendVideoFeedback } from './study-api';
import { newLocalId } from './navigation';
import type { RecommendationResponse, StudyBudget, SupplementaryVideo, VideoFeedback } from './study-types';
import './supplementary-videos.css';

/** 只允许核验过的视频详情地址，不能把任意外链或搜索页当作课堂推荐。 */
export function safeBilibiliVideoUrl(raw: string): string | null {
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || !['bilibili.com', 'www.bilibili.com', 'm.bilibili.com'].includes(url.hostname)
      || url.username || url.password || url.port || !/^\/video\/BV[A-Za-z0-9]{10}\/?$/.test(url.pathname)) return null;
    const part = url.searchParams.get('p');
    if (part !== null && !/^[1-9]\d{0,5}$/.test(part)) return null;
    // 没有p参数代表整个视频入口，不能把整条时长偷偷变成P1时长。
    return `https://www.bilibili.com${url.pathname.replace(/\/$/, '')}${part === null ? '' : `?p=${part}`}`;
  } catch { return null; }
}
export function recommendationExpired(value: RecommendationResponse, now = Date.now()): boolean {
  const checked = Date.parse(value.updatedAt || '');
  return Number.isFinite(checked) && now - checked >= 24 * 60 * 60 * 1000;
}
export function displayableVideos(value: RecommendationResponse | null, budgetMinutes: StudyBudget): SupplementaryVideo[] {
  if (value?.status !== 'ready' || value.budgetMinutes !== budgetMinutes || !value.decisionId || recommendationExpired(value)) return [];
  const items = value.items || [];
  // 组合有一项不满足公开契约时整体隐藏，防止前端部分过滤后改变后端已验证的组合。
  if (!items.length || items.length > 3 || items.some(item => !safeBilibiliVideoUrl(item.url) || !item.id || !item.title.trim()
    || !item.author.trim() || !item.reason.trim() || !item.goals?.length || !validResourceUnit(item)
    || !Number.isSafeInteger(item.durationSeconds) || item.durationSeconds <= 0 || !Number.isFinite(Date.parse(item.checkedAt)) || Date.now() - Date.parse(item.checkedAt) >= 24 * 60 * 60 * 1000)
    || items.reduce((sum, item) => sum + item.durationSeconds, 0) > budgetMinutes * 60
    || new Set(items.map(item => new URL(safeBilibiliVideoUrl(item.url)!).pathname.split('/')[2])).size !== items.length || new Set(items.map(item => item.id)).size !== items.length) return [];
  return items;
}
function validResourceUnit(item: SupplementaryVideo): boolean {
  const safe = safeBilibiliVideoUrl(item.url); if (!safe) return false;
  const url = new URL(safe), bvid = url.pathname.split('/')[2], part = url.searchParams.get('p');
  const whole = item.durationScope === 'whole-video' || item.part === 0 || item.id.endsWith(':whole')
    || item.verificationScope === 'search-metadata' || item.sourceKind === 'bilibili-search-metadata';
  if (whole) return part === null && item.id === `${bvid}:whole` && item.part === 0 && item.durationScope === 'whole-video'
    && item.sourceKind === 'bilibili-search-metadata' && item.verificationScope === 'search-metadata';
  return part !== null && item.id === `${bvid}:${part}` && item.verificationScope === 'metadata'
    && (item.part === undefined || item.part === Number(part)) && (item.durationScope === undefined || item.durationScope === 'part')
    && (item.sourceKind === undefined || item.sourceKind === 'bilibili-public-api' || item.sourceKind === 'verified-metadata-cache');
}
export function videoDuration(seconds: number): string {
  const rounded = Math.ceil(seconds);
  return `${Math.floor(rounded / 60)}:${String(rounded % 60).padStart(2, '0')}`;
}
function verificationLabel(item: SupplementaryVideo): string {
  // 学生只需要知道资料来自哪次核验；供应商错误与网络诊断不属于来源名称。
  return item.sourceKind === 'bilibili-search-metadata' ? 'B站搜索资料' : item.sourceKind === 'verified-metadata-cache' ? '已核验资料缓存' : '已核验的视频资料';
}
function verificationTime(checkedAt: string): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(checkedAt));
}
export function readStudyBudget(courseId: string): StudyBudget {
  try { const value = Number(localStorage.getItem(`educationagent.study-budget.v1:${courseId}`)); return value === 5 || value === 20 ? value : 10; }
  catch { return 10; }
}
export function VideoBudgetControl({ value, onChange, onRefresh, preparing = false }: { value: StudyBudget; onChange(value: StudyBudget): void; onRefresh?(): void; preparing?: boolean }) {
  const clicked = useRef(false);
  useEffect(() => { if (!preparing) clicked.current = false; }, [preparing]);
  return <fieldset className="cr-video-budget"><legend>补充观看时间</legend><div>{([5, 10, 20] as const).map(minutes => <button type="button" key={minutes} aria-pressed={minutes === value} onClick={() => onChange(minutes)}>{minutes} 分钟</button>)}</div>{onRefresh && <button type="button" className="cr-video-retry" disabled={preparing} onClick={() => { if (preparing || clicked.current) return; clicked.current = true; onRefresh(); }}>{preparing ? '补充资料准备中…' : '重新准备补充资料'}</button>}</fieldset>;
}

export default function SupplementaryVideos({ courseId, sceneId, contentRevision, budgetMinutes, budgetOverride = true, onBudgetResolved, learningRevision = '', manualRefreshId = '', onPreparingChange }: {
  courseId: string; sceneId: string; contentRevision: number; budgetMinutes: StudyBudget; budgetOverride?: boolean; onBudgetResolved?(value: StudyBudget): void;
  learningRevision?: string; manualRefreshId?: string; onPreparingChange?(preparing: boolean): void;
}) {
  const identity = `${courseId}:${sceneId}:${contentRevision}:${budgetMinutes}:${learningRevision}`;
  const [result, setResult] = useState<{ identity: string; value: RecommendationResponse } | null>(null);
  const [feedback, setFeedback] = useState<Record<string, string>>({});
  const feedbackControllers = useRef(new Set<AbortController>());
  const budgetResolved = useRef(onBudgetResolved); budgetResolved.current = onBudgetResolved;
  const preparingChanged = useRef(onPreparingChange); preparingChanged.current = onPreparingChange;
  const consumedManualRefresh = useRef('');
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let prepared = false;
    const notifyPreparing = preparingChanged.current;
    notifyPreparing?.(true);
    setFeedback({});
    const read = async () => {
      try {
        let next: RecommendationResponse;
        if (manualRefreshId && consumedManualRefresh.current !== manualRefreshId) {
          // 点击意图只消费一次；预算回读或普通重渲染不重放付费准备请求。
          consumedManualRefresh.current = manualRefreshId; prepared = true;
          next = await refreshRecommendations(courseId, sceneId, budgetMinutes, controller.signal, true);
        } else next = await getRecommendations(courseId, sceneId, controller.signal);
        if (controller.signal.aborted) return;
        // 共享账号以网站预算为准，只有本次主动选择才能写回，旧浏览器缓存不覆盖另一设备的设置。
        if (!budgetOverride && budgetResolved.current && next.budgetMinutes !== budgetMinutes && [5, 10, 20].includes(next.budgetMinutes)) {
          budgetResolved.current(next.budgetMinutes); notifyPreparing?.(false); return;
        }
        if (!prepared && (next.status === 'idle' || recommendationExpired(next) || next.budgetMinutes !== budgetMinutes)) {
          prepared = true;
          next = await refreshRecommendations(courseId, sceneId, budgetMinutes, controller.signal);
        }
        if (controller.signal.aborted) return;
        if (next.sceneId !== sceneId) { setResult(null); notifyPreparing?.(false); return; }
        if (budgetOverride && next.budgetMinutes === budgetMinutes) budgetResolved.current?.(next.budgetMinutes);
        setResult({ identity, value: next });
        if (next.status === 'queued' || next.status === 'running') timer = setTimeout(() => void read(), Math.min(10000, Math.max(500, next.nextPollMs || 1500)));
        else notifyPreparing?.(false);
      } catch {
        // 推荐准备失败不阻塞原课堂，也不展示搜索页或失败占位卡。
        if (!controller.signal.aborted) { setResult(null); notifyPreparing?.(false); }
      }
    };
    void read();
    return () => { controller.abort(); if (timer) clearTimeout(timer); notifyPreparing?.(false); };
  }, [courseId, sceneId, contentRevision, budgetMinutes, budgetOverride, identity, manualRefreshId]);
  useEffect(() => () => { feedbackControllers.current.forEach(controller => controller.abort()); feedbackControllers.current.clear(); }, [identity]);
  const value = result?.identity === identity ? result.value : null;
  const items = displayableVideos(value, budgetMinutes);
  if (!items.length || !value?.decisionId) return null;
  const recordFeedback = async (item: SupplementaryVideo, kind: VideoFeedback) => {
    const controller = new AbortController(); feedbackControllers.current.add(controller);
    try {
      await sendVideoFeedback(courseId, value.decisionId!, item.id, kind, newLocalId(), controller.signal);
      if (!controller.signal.aborted && kind !== 'opened') setFeedback(previous => ({ ...previous, [item.id]: '已记下你的反馈，谢谢。' }));
    } catch { if (!controller.signal.aborted && kind !== 'opened') setFeedback(previous => ({ ...previous, [item.id]: '暂未保存反馈，可以稍后再试。' })); }
    finally { feedbackControllers.current.delete(controller); }
  };
  return <section className="cr-supplementary-videos" aria-label="这节课的补充视频">
    <header><div><p className="cr-eyebrow">换一种方式，看看这个知识点</p><h3>这节课的补充视频</h3></div><span className="cr-video-total">共 {videoDuration(items.reduce((sum, item) => sum + item.durationSeconds, 0))}</span></header>
    <div className="cr-video-list">{items.map(item => <article key={item.id} className="cr-video-card">
      <div className="cr-video-card-top"><span>哔哩哔哩 · {item.author}</span><span>{item.durationScope === 'whole-video' ? '整条视频 ' : ''}{videoDuration(item.durationSeconds)}</span></div>
      <a href={safeBilibiliVideoUrl(item.url)!} target="_blank" rel="noopener noreferrer" onClick={() => void recordFeedback(item, 'opened')}><strong>{item.title}</strong><span aria-hidden="true">↗</span><span className="cr-video-sr">（在新标签页打开）</span></a>
      <p>{item.reason}</p><div className="cr-video-goals">{item.goals.map((goal, index) => <span key={`${goal}-${index}`}>{goal}</span>)}</div>
      <small className="cr-video-verification">{verificationLabel(item)} · <time dateTime={item.checkedAt}>{verificationTime(item.checkedAt)}</time></small>
      <details className="cr-video-feedback"><summary>看过后，告诉小陶感受</summary><div>{([['helpful', '有帮助'], ['not_helpful', '没帮上忙'], ['too_long', '有点长'], ['tried', '我试过了']] as const).map(([kind, label]) => <button type="button" key={kind} onClick={() => void recordFeedback(item, kind)}>{label}</button>)}</div>{feedback[item.id] && <small role="status">{feedback[item.id]}</small>}</details>
    </article>)}</div>
    <p className="cr-video-note">{items.some(item => item.durationScope === 'whole-video') ? '整条视频按B站搜索显示的总时长计算。' : '依据标题、简介与分集信息匹配。'}进入哔哩哔哩观看，回到这里可以继续原来的学习。</p>
  </section>;
}
