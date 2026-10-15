/**
 * 浏览器侧运行时采集：FPS、JS 堆内存、事件循环延迟。
 *
 * ── 关于 CPU ─────────────────────────────────────────────
 * 方案书要求采集「浏览器 FPS、RAM、CPU」。前两个能拿，**CPU 拿不到**：
 * 浏览器沙箱不暴露进程 CPU 占用（没有 Chrome 的 `--enable-precise-memory-info`
 * 之外也没有任何 CPU API），WebView2 同样如此。
 *
 * 与其编一个数字，这里采集 **事件循环延迟（event loop lag）** 作为 CPU 压力的
 * 代理指标：主线程被占满时，定时器回调会被推迟，延迟直接升高。
 * 它衡量的是"这个界面卡不卡"，比一个假的 CPU 百分比有用得多，
 * 而且和 FPS 互补——FPS 反映渲染，lag 反映主线程计算。
 *
 * 采集本身也必须便宜：全部基于 rAF + 1 个 1 秒定时器，无轮询循环。
 */

const MAX_SAMPLES = 60; // 保留最近 60 个采样点（约 1 分钟）

/** 一段采样。 */
function emptySample(ts) {
  return { ts, fps: 0, heapMb: 0, lagMs: 0 };
}

export function createMetricsCollector() {
  let frames = 0;
  let rafId = null;
  let timerId = null;
  let lastFrameAt = 0;
  let lastTickAt = 0;
  let running = false;

  const samples = [];
  const listeners = new Set();

  /** 当前一秒内累计的帧数与事件循环延迟。 */
  let tickFrames = 0;
  let maxLagThisTick = 0;

  function onFrame(now) {
    if (!running) return;
    frames += 1;
    tickFrames += 1;
    if (lastFrameAt) {
      const delta = now - lastFrameAt;
      // 单帧超过 200ms 说明这一帧被长任务阻塞了，记为一次延迟尖峰
      if (delta > 200) maxLagThisTick = Math.max(maxLagThisTick, delta - 16.7);
    }
    lastFrameAt = now;
    rafId = requestAnimationFrame(onFrame);
  }

  function readHeapMb() {
    // Chromium（含 Tauri 的 WebView2）提供 performance.memory；其他内核没有
    const mem = typeof performance !== 'undefined' ? performance.memory : null;
    if (!mem || !mem.usedJSHeapSize) return 0;
    return Math.round((mem.usedJSHeapSize / (1024 * 1024)) * 10) / 10;
  }

  function tick() {
    if (!running) return;
    const now = Date.now();
    const elapsed = Math.max(1, now - lastTickAt) / 1000;
    const sample = {
      ts: now,
      fps: Math.round(tickFrames / elapsed),
      heapMb: readHeapMb(),
      lagMs: Math.round(maxLagThisTick),
    };
    tickFrames = 0;
    maxLagThisTick = 0;
    lastTickAt = now;

    samples.push(sample);
    while (samples.length > MAX_SAMPLES) samples.shift();
    listeners.forEach((fn) => {
      try {
        fn(sample);
      } catch {
        /* 订阅者异常不能影响采集 */
      }
    });
  }

  return {
    start() {
      if (running) return;
      running = true;
      lastFrameAt = 0;
      lastTickAt = Date.now();
      tickFrames = 0;
      maxLagThisTick = 0;
      rafId = requestAnimationFrame(onFrame);
      timerId = setInterval(tick, 1000);
    },

    stop() {
      running = false;
      if (rafId != null) cancelAnimationFrame(rafId);
      if (timerId != null) clearInterval(timerId);
      rafId = null;
      timerId = null;
    },

    /** 订阅每次采样，返回取消订阅函数。 */
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },

    getSamples() {
      return samples.slice();
    },

    /** 汇总：平均值 + 最近值。没有采样时全部为 null，避免前端显示 0 造成误读。 */
    summarize() {
      if (samples.length === 0) {
        return { fps: null, fpsAvg: null, heapMb: null, lagMs: null, lagAvg: null, samples: 0 };
      }
      const last = samples[samples.length - 1];
      const avg = (key) =>
        Math.round((samples.reduce((s, x) => s + x[key], 0) / samples.length) * 10) / 10;
      return {
        fps: last.fps,
        fpsAvg: avg('fps'),
        heapMb: last.heapMb || null,
        lagMs: last.lagMs,
        lagAvg: avg('lagMs'),
        samples: samples.length,
      };
    },

    /** 当前环境是否支持读取 JS 堆（不支持时前端要说明原因而不是显示 0）。 */
    heapSupported() {
      return !!(typeof performance !== 'undefined' && performance.memory);
    },
  };
}

export { emptySample };
