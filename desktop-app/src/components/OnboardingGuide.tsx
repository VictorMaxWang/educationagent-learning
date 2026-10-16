import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { clippedTourTarget, positionTourBubble, type TourBox } from './onboardingGeometry';
import './OnboardingGuide.css';

const FINISHED_KEY = 'educationagent.onboarding.finished';
interface TourStep { target?: string; title: string; body: string }
const STEPS: TourStep[] = [
  { title: '👋 你好！我是小陶', body: '咱们简单逛一圈，很快就能学会怎么用晓陶学伴。' },
  { target: '.cr-main-question', title: '📚 想学什么，写在这里', body: '把问题或主题写下来，比如「分数为什么可以通分」。也可以上传 PDF、Word 资料，我照着你的材料备课。' },
  { target: '.cr-create-fields', title: '🎯 选年级和学习方向', body: '选你正在读的年级。学科学习可以选学科，身心成长和实践探索也有各自的学习内容。' },
  { target: '.cr-library', title: '📖 学过的课都在这儿', body: '点开「我的课堂」，就能接着上次的地方继续学，不用从头开始。' },
  { target: '.cr-tabs', title: '📝 学习 · 复习 · 成长课堂', body: '需要再练的题可以在「复习」里重做。「成长课堂」还有沟通、健康、语言与动手实践。' },
  { target: '.voice-orb', title: '🎤 不懂就问我', body: '右下角就是小陶。点一下，可以说话或打字，也能听我朗读。课堂里还可以直接问老师。' },
  { target: '.cr-all-tools', title: '✨ 还有其他工具', body: '这里能找到更多学习工具。想换昵称、主题颜色或听音乐，也可以从这里打开设置。' },
  { title: '🌱 可以开始啦', body: '从写下第一个学习主题开始吧。想再看一遍，点右上角的小问号就行。' },
];
function hasFinished() { try { return localStorage.getItem(FINISHED_KEY) === '1'; } catch { return false; } }
function rememberFinished() { try { localStorage.setItem(FINISHED_KEY, '1'); } catch { /* 存储受限仍可跳过本次引导。 */ } }
function viewportBox(): TourBox {
  const visual = window.visualViewport;
  return { left: visual?.offsetLeft || 0, top: visual?.offsetTop || 0, width: visual?.width || window.innerWidth, height: visual?.height || window.innerHeight };
}
function visibleTarget(selector?: string): HTMLElement | null {
  if (!selector) return null;
  const target = document.querySelector<HTMLElement>(selector);
  if (!target) return null;
  const rect = target.getBoundingClientRect(), style = getComputedStyle(target);
  return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden' ? target : null;
}

/** 引导仅保存“已看过”；页面元素和学生内容不进入存储。 */
export default function OnboardingGuide() {
  const [index, setIndex] = useState<number | null>(null);
  const [available, setAvailable] = useState<TourStep[]>([]);
  const [rect, setRect] = useState<TourBox | null>(null);
  const [viewport, setViewport] = useState<TourBox>(viewportBox);
  const [bubbleHeight, setBubbleHeight] = useState(240);
  const [helpTop, setHelpTop] = useState(100);
  const primaryRef = useRef<HTMLButtonElement>(null);
  const helpRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const bubbleRef = useRef<HTMLElement>(null);
  const restoreFocus = useRef<HTMLElement | null>(null);
  const startFrame = useRef<number | null>(null);
  const step = index === null ? null : available[index] ?? null;
  const active = Boolean(step);

  const finish = useCallback(() => { if (startFrame.current !== null) window.cancelAnimationFrame(startFrame.current); startFrame.current = null; rememberFinished(); setIndex(null); setRect(null); }, []);
  const startTour = useCallback(function begin(attempt = 0) {
    if (attempt === 0) {
      if (startFrame.current !== null) window.cancelAnimationFrame(startFrame.current);
      restoreFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    }
    const usable = STEPS.filter(candidate => !candidate.target || visibleTarget(candidate.target));
    if (!usable.some(candidate => candidate.target) && attempt < 12) {
      startFrame.current = window.requestAnimationFrame(() => begin(attempt + 1)); return;
    }
    startFrame.current = null; setAvailable(usable); setIndex(0);
  }, []);
  useEffect(() => {
    if (!hasFinished()) startTour();
    return () => { if (startFrame.current !== null) window.cancelAnimationFrame(startFrame.current); };
  }, [startTour]);

  // visualViewport覆盖手机浏览器栏、软键盘及缩放；帮助入口跟随真实头部下沿。
  useEffect(() => {
    const measure = () => {
      const next = viewportBox(); setViewport(next);
      const header = document.querySelector('.cr-header')?.getBoundingClientRect();
      setHelpTop(Math.max(next.top + 12, Math.min((header?.bottom || next.top + 88) + 12, next.top + next.height - 54)));
    };
    measure();
    window.addEventListener('resize', measure); window.addEventListener('scroll', measure, true);
    window.visualViewport?.addEventListener('resize', measure); window.visualViewport?.addEventListener('scroll', measure);
    return () => {
      window.removeEventListener('resize', measure); window.removeEventListener('scroll', measure, true);
      window.visualViewport?.removeEventListener('resize', measure); window.visualViewport?.removeEventListener('scroll', measure);
    };
  }, []);

  useLayoutEffect(() => {
    if (!step) return;
    const element = visibleTarget(step.target);
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    element?.scrollIntoView({ block: 'center', behavior: reduced ? 'auto' : 'smooth' });
    const measure = () => {
      const box = visibleTarget(step.target)?.getBoundingClientRect();
      setRect(box ? { left: box.left, top: box.top, width: box.width, height: box.height } : null);
    };
    measure();
    window.addEventListener('scroll', measure, true); window.addEventListener('resize', measure);
    window.visualViewport?.addEventListener('resize', measure); window.visualViewport?.addEventListener('scroll', measure);
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null;
    if (element) observer?.observe(element);
    return () => {
      observer?.disconnect(); window.removeEventListener('scroll', measure, true); window.removeEventListener('resize', measure);
      window.visualViewport?.removeEventListener('resize', measure); window.visualViewport?.removeEventListener('scroll', measure);
    };
  }, [step]);
  useLayoutEffect(() => {
    if (!step || !bubbleRef.current) return;
    const measure = () => { const height = bubbleRef.current?.getBoundingClientRect().height; if (height) setBubbleHeight(height); };
    measure();
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null;
    observer?.observe(bubbleRef.current);
    return () => observer?.disconnect();
  }, [step, viewport.width, viewport.height]);
  useEffect(() => { if (step) primaryRef.current?.focus({ preventScroll: true }); }, [step]);

  useEffect(() => {
    if (!active || !dialogRef.current) return;
    const dialog = dialogRef.current;
    // aria-modal本身不会阻止背景键盘操作；恢复原inert状态，不能破坏其他弹层。
    const siblings = Array.from(dialog.parentElement?.children || []).filter((node): node is HTMLElement => node instanceof HTMLElement && node !== dialog);
    const previous = siblings.map(node => ({ node, inert: node.inert }));
    previous.forEach(({ node }) => { node.inert = true; });
    const focusables = () => Array.from(dialog.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]'));
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); finish(); return; }
      if (event.key !== 'Tab') return;
      const items = focusables(), first = items[0], last = items.at(-1);
      if (!first || !last) return;
      if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
    };
    const onFocus = (event: FocusEvent) => { if (!dialog.contains(event.target as Node)) primaryRef.current?.focus({ preventScroll: true }); };
    document.addEventListener('keydown', onKeyDown, true); document.addEventListener('focusin', onFocus);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true); document.removeEventListener('focusin', onFocus);
      previous.forEach(({ node, inert }) => { node.inert = inert; });
      const target = restoreFocus.current;
      if (target?.isConnected && target !== document.body) target.focus({ preventScroll: true });
      else helpRef.current?.focus({ preventScroll: true });
    };
  }, [active, finish]);

  if (!step) return <button ref={helpRef} className="onb-help" style={{ top: helpTop }} onClick={() => startTour()} aria-label="重新播放使用引导" title="使用帮助">?</button>;
  const current = index ?? 0, last = current === available.length - 1;
  const placement = positionTourBubble(rect, viewport, bubbleHeight);
  const spot = clippedTourTarget(rect, viewport);
  return <div ref={dialogRef} className="onb-root" role="dialog" aria-modal="true" aria-labelledby="xiaotao-guide-title">
    {spot ? <div className="onb-spot" style={spot} /> : <div className="onb-dim" />}
    <section ref={bubbleRef} className="onb-bubble" style={{ top: placement.top, left: placement.left, width: placement.width, maxHeight: placement.maxHeight }}>
      <div className="onb-bubble-scroll">
        <p className="onb-progress">{current + 1}/{available.length}</p>
        <h3 id="xiaotao-guide-title">{step.title}</h3><p className="onb-body">{step.body}</p>
        <div className="onb-actions"><button className="onb-skip" onClick={finish}>跳过全部</button><button ref={primaryRef} className="onb-next" onClick={() => last ? finish() : setIndex(current + 1)}>{last ? '开始使用' : '知道了'}</button></div>
      </div>
      {placement.arrow && <span className={'onb-arrow onb-arrow-' + placement.arrow.side} style={{ left: placement.arrow.offset }} aria-hidden="true" />}
    </section>
  </div>;
}
