// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PracticeActivities from '../src/practice/PracticeActivities';

let root: Root;
let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
const button = (label: string) => [...host.querySelectorAll('button')].find(item => item.textContent === label)!;
const changeRange = async (label: string, value: number) => {
  const input = host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;
  // 使用原生 setter 绕过 React 的值跟踪，再派发真实 DOM 输入事件；不依赖另一份 React 的测试封装。
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, String(value)); input.dispatchEvent(new Event('input', { bubbles: true })); });
};

describe('实践活动的真实状态与计算', () => {
  it('分数约束选中份数，面积格网与米数尺度一致，实际参数改变面积', async () => {
    const context = vi.fn();
    await act(async () => root.render(<PracticeActivities mode="simulation" onContextChange={context} />));
    await changeRange('等分份数', 2);
    expect(context.mock.lastCall?.[0]).toContain('选中2份'); expect(context.mock.lastCall?.[0]).toContain('100.0%');
    await changeRange('选中份数', 1); expect(context.mock.lastCall?.[0]).toContain('50.0%');
    await act(async () => button('菜地面积').click());
    await changeRange('菜地长（米）', 6); await changeRange('菜地宽（米）', 4);
    expect(context.mock.lastCall?.[0]).toContain('面积24平方米');
    const grid = host.querySelector('pattern')!;
    expect([grid.getAttribute('width'), grid.getAttribute('height')]).toEqual(['30', '16']);
    expect([grid.getAttribute('x'), grid.getAttribute('y')]).toEqual(['60', '35']);
    const land = host.querySelector('svg>rect')!;
    expect([land.getAttribute('width'), land.getAttribute('height')]).toEqual(['180', '64']);
    expect(host.textContent).toContain('每格 1 平方米');
  });
  it('空间顶点随尺寸和视角变化，体积由三边实际计算', async () => {
    const context = vi.fn();
    await act(async () => root.render(<PracticeActivities mode="visualization3d" onContextChange={context} />));
    const before = host.querySelector('polygon')?.getAttribute('points');
    await changeRange('长', 8); expect(context.mock.lastCall?.[0]).toContain('体积48立方单位');
    const shape = host.querySelector('polygon')?.getAttribute('points'); expect(shape).not.toBe(before);
    await act(async () => host.querySelector('[role=img]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    expect(host.querySelector('polygon')?.getAttribute('points')).not.toBe(shape);
  });
  it('闯关按实际选择计分，完成摘要正确，重试归零不叠加旧分数', async () => {
    const context = vi.fn();
    await act(async () => root.render(<PracticeActivities mode="game" onContextChange={context} />));
    await act(async () => button('1/4').click()); await act(async () => button('进入下一轮 →').click());
    await act(async () => button('4/6').click()); await act(async () => button('进入下一轮 →').click());
    await act(async () => button('3/4').click()); await act(async () => button('查看本轮结果').click());
    expect(host.textContent).toContain('本轮答对 2 题。你能把一道题的理由讲给小陶听吗？');
    expect(context.mock.lastCall?.[0]).toContain('已完成3轮'); expect(context.mock.lastCall?.[0]).toContain('最终得分2/3'); expect(context.mock.lastCall?.[0]).not.toContain('尚未作答');
    await act(async () => button('重新闯关 ↻').click());
    expect(host.textContent).toContain('得分 0 / 3'); expect(context.mock.lastCall?.[0]).toContain('第1轮');
  });
});
