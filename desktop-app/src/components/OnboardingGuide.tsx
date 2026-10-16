import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import './OnboardingGuide.css';

/** 只记录"整套引导是否走完"。清浏览器缓存才会重置——需求要的就是这个行为。 */
const FINISHED_KEY = 'educationagent.onboarding.finished';

interface TourStep {
  /** 页面元素的 CSS 选择器；不填则该步居中显示（用于开场白）。 */
  target?: string;
  title: string;
  body: string;
}

/**
 * 引导步骤。**用 class 选择器锚定，不改动任何现有组件**（线上页面没有 id 属性）。
 * 文案面向 7–18 岁：短句、口语，一次只讲一件事。
 *
 * 这些锚点都是**首次进入时页面上确实存在**的元素；课堂播放器、练习区要等生成出课程才有，
 * 所以不放进引导，改在「学习 / 复习 / 成长课堂」那一步里说明。
 */
const STEPS: TourStep[] = [
  { title: '👋 你好！我是小陶', body: '咱们简单逛一圈，很快就能学会怎么用晓陶学伴。' },
  { target: '.cr-main-question', title: '📚 想学什么，写在这里', body: '把你的问题或主题写下来，比如「分数为什么可以通分」。也可以上传 PDF、Word 资料，我照你的材料备课。' },
  { target: '.cr-create-fields', title: '🎯 选年级和学习方向', body: '选你正在读的年级，我就按你的水平讲课。想聊成长话题，把学习方向换成身心成长或实践探索就行。' },
  { target: '.cr-library', title: '📖 学过的课都在这儿', body: '上过的课会在「我的课堂」里排队，点一下就能接着上次的地方继续学，不用从头开始。' },
  { target: '.cr-tabs', title: '📝 学习 · 复习 · 成长课堂', body: '做错的题会自动进「复习」，可以反复重做。「成长课堂」里有心灵与沟通、安全与健康、语言与通识、实践与创造。' },
  { target: '.voice-orb', title: '🎤 不懂就问我', body: '右下角就是我，随时都能问：点一下打开，说话或打字都行，我还能读给你听。课堂里也可以直接问老师。' },
  { target: '.cr-all-tools', title: '✨ 还有其他工具', body: '这里能找到刷题练习、英语口语与语法、团队成果实验室、图片水印。想换昵称或主题颜色，就在「设置」里。' },
  { title: '🌱 可以开始啦', body: '从写下第一个学习主题开始吧。想再看一遍，点右上角的小问号就行。' },
];

function hasFinished(): boolean {
  // 隐私模式或禁用存储时 localStorage 会抛异常；读不到就当作没看过，不能因此白屏。
  try { return localStorage.getItem(FINISHED_KEY) === '1'; } catch { return false; }
}

function rememberFinished(): void {
  try { localStorage.setItem(FINISHED_KEY, '1'); } catch { /* 存不下就只是每次都弹，功能仍然可用 */ }
}

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

/**
 * 分步新手引导：首次进入自动播放，逐个小高亮页面元素并弹出蓝色气泡；走完后右上角留一个
 * 小问号，随时可以重放整套引导。
 *
 * 高亮用 `box-shadow: 0 0 0 9999px` 挖洞，只有一个元素、不会在四块遮罩拼接处留缝。
 */
export default function OnboardingGuide() {
  /** null = 未在引导中。 */
  const [index, setIndex] = useState<number | null>(null);
  /** 只保留"此刻页面上真的存在"的步骤，避免走到一半指向空气。 */
  const [available, setAvailable] = useState<TourStep[]>([]);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const primaryRef = useRef<HTMLButtonElement>(null);

  const finish = useCallback(() => {
    rememberFinished();
    setIndex(null);
    setRect(null);
  }, []);

  /**
   * 开始整套引导。只保留**此刻页面上真的看得见**的锚点对应的步骤。
   *
   * 首页可能还没完成布局（刚挂载时量到的是零尺寸），那样会把带箭头的步骤全部丢掉、
   * 只剩两个居中气泡，看起来就像"没锚定、背景全暗"。所以这里逐帧重试，
   * 最多等 12 帧（约 200ms）；一次都没量到就照现状开始，不无限等。
   * 首次播放和问号重放都走这一个入口，避免两条路径行为不一致。
   */
  const startTour = useCallback(function begin(attempt = 0) {
    const usable = STEPS.filter(step => {
      if (!step.target) return true;
      const box = document.querySelector(step.target)?.getBoundingClientRect();
      return Boolean(box && box.width > 0 && box.height > 0);
    });
    const anchored = usable.some(step => step.target);
    if (!anchored && attempt < 12) { window.requestAnimationFrame(() => begin(attempt + 1)); return; }
    setAvailable(usable);
    setIndex(0);
  }, []);

  // 首次进入：没有标记就自动播放。
  useEffect(() => { if (!hasFinished()) startTour(); }, [startTour]);

  const step = index === null ? null : available[index] ?? null;

  // 定位当前步骤：先把它滚到视野中间，再量它的位置。
  useEffect(() => {
    if (!step) return;
    let cancelled = false;
    const measure = () => {
      if (cancelled) return;
      const box = step.target ? document.querySelector(step.target)?.getBoundingClientRect() : null;
      // 量到零尺寸就退回居中气泡，不要画一个贴在角落的空框。
      setRect(box && box.width > 0 && box.height > 0 ? box : null);
    };
    const element = step.target ? document.querySelector(step.target) : null;
    // 减少动画偏好下不做平滑滚动，直接到位。
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    element?.scrollIntoView({ block: 'center', behavior: reduced ? 'auto' : 'smooth' });
    // 平滑滚动是异步的，滚完再量；同时按帧跟一段，避免量到中途位置。
    const timer = window.setTimeout(measure, reduced ? 0 : 220);
    const onMove = () => measure();
    window.addEventListener('resize', onMove);
    window.addEventListener('scroll', onMove, true); // capture：滚动也可能发生在内层容器里
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      window.removeEventListener('resize', onMove);
      window.removeEventListener('scroll', onMove, true);
    };
  }, [step]);

  // 每一步把焦点移到主按钮上，键盘与读屏用户不必先找。
  useEffect(() => { if (step) primaryRef.current?.focus({ preventScroll: true }); }, [step]);

  const next = useCallback(() => {
    setIndex(current => {
      if (current === null) return null;
      if (current + 1 < available.length) return current + 1;
      rememberFinished();
      return null;
    });
  }, [available.length]);

  // Esc 跳过整套引导（与"走完"同样记为已看过）。
  useEffect(() => {
    if (!step) return;
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') finish(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [step, finish]);

  /**
   * 气泡与箭头的几何：优先把气泡放在高亮框**下方**，下方不够就翻到上方；横向夹在视口内。
   * 箭头按目标中心对齐 —— 气泡被夹到屏幕边上时箭头也跟着偏，仍然指着目标，
   * 不画那种"永远指向正下方、却对不上目标"的装饰箭头。
   */
  const layout = useMemo(() => {
    if (!step) return null;
    if (!rect) return { centered: true as const, style: {}, arrow: null };
    const width = Math.min(360, window.innerWidth - 24);
    const GAP = 16; // 气泡与目标之间留给箭头的距离
    const placeBelow = window.innerHeight - (rect.bottom + GAP) > 180;
    const top = placeBelow ? rect.bottom + GAP : Math.max(12, rect.top - GAP - 176);
    const left = clamp(rect.left + rect.width / 2 - width / 2, 12, Math.max(12, window.innerWidth - width - 12));
    const offset = clamp(rect.left + rect.width / 2 - left, 20, width - 20);
    return { centered: false as const, style: { top, left, width },
      arrow: { side: placeBelow ? ('top' as const) : ('bottom' as const), offset } };
  }, [step, rect]);

  if (!step || !layout) return <>
    <button className="onb-help" onClick={() => startTour()} aria-label="重新播放使用引导" title="使用帮助">?</button>
  </>;

  // step 非空蕴含 index 非空，但两个 state 之间 TS 推不出来，这里显式收窄一次。
  const current = index ?? 0;
  const last = current === available.length - 1;
  return <>
    {/* 点击遮罩不关闭：引导要的是"跟着走完"，不是随手点掉。可用 Esc 或「跳过」。 */}
    <div className="onb-root" role="dialog" aria-modal="true" aria-label="使用引导">
      {rect
        ? <div className="onb-spot" style={{ top: rect.top - 6, left: rect.left - 6, width: rect.width + 12, height: rect.height + 12 }} />
        : <div className="onb-dim" />}
      <section className={`onb-bubble${layout.centered ? ' onb-bubble-center' : ''}`} style={layout.style}>
        <p className="onb-progress">{current + 1}/{available.length}</p>
        <h3>{step.title}</h3>
        <p className="onb-body">{step.body}</p>
        <div className="onb-actions">
          <button className="onb-skip" onClick={finish}>跳过全部</button>
          <button ref={primaryRef} className="onb-next" onClick={next}>{last ? '开始使用' : '知道了'}</button>
        </div>
        {layout.arrow && <span className={`onb-arrow onb-arrow-${layout.arrow.side}`} style={{ left: layout.arrow.offset }} aria-hidden="true" />}
      </section>
    </div>
  </>;
}
