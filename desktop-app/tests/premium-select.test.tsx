// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PremiumSelect from '../src/classroom/PremiumSelect';
import { companionIdentityText } from '../src/classroom/branding';

let root: Root;
let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  Element.prototype.scrollIntoView = vi.fn();
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
const key = (target: Element, value: string) => target.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true }));

function Example() {
  const [value, setValue] = useState('academic');
  return <PremiumSelect label="学习方向" value={value} onChange={setValue} options={[{ value: 'academic', label: '学科学习', detail: '从课本里的问题出发' }, { value: 'wellbeing', label: '身心成长' }, { value: 'exploration', label: '实践探索' }]} />;
}

describe('卡片选择器键盘与焦点', () => {
  it('方向键打开，End定位末项，Enter选择并恢复触发器焦点', async () => {
    await act(async () => root.render(<Example />));
    const trigger = host.querySelector('button')!;
    await act(async () => key(trigger, 'ArrowDown'));
    const list = host.querySelector('[role=listbox]')!;
    expect(document.activeElement).toBe(list); expect(trigger.getAttribute('aria-expanded')).toBe('true');
    await act(async () => key(list, 'End'));
    expect(list.getAttribute('aria-activedescendant')).toMatch(/option-2$/);
    await act(async () => key(list, 'Enter'));
    expect(host.querySelector('[role=listbox]')).toBeNull(); expect(trigger.textContent).toContain('实践探索'); expect(document.activeElement).toBe(trigger);
  });
  it('Escape放弃移动不改变已选值，点击外部关闭', async () => {
    await act(async () => root.render(<Example />));
    const trigger = host.querySelector('button')!;
    await act(async () => trigger.click());
    await act(async () => key(host.querySelector('[role=listbox]')!, 'ArrowDown'));
    await act(async () => key(host.querySelector('[role=listbox]')!, 'Escape'));
    expect(trigger.textContent).toContain('学科学习'); expect(document.activeElement).toBe(trigger);
    await act(async () => trigger.click());
    await act(async () => document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })));
    expect(host.querySelector('[role=listbox]')).toBeNull();
  });
  it('打开和选择均不触发表单提交；学段只组织展示不改变值', async () => {
    const submitted = vi.fn(); const changed = vi.fn();
    await act(async () => root.render(<form onSubmit={event => { event.preventDefault(); submitted(); }}><PremiumSelect label="我在读" value={5} onChange={changed} options={[{ value: 5, label: '五年级', group: '小学' }, { value: 7, label: '初一', group: '初中' }, { value: 10, label: '高一', group: '高中' }]} /></form>));
    await act(async () => host.querySelector('button')!.click());
    expect([...host.querySelectorAll('.ps-group-heading')].map(item => item.textContent)).toEqual(['小学', '初中', '高中']);
    await act(async () => (host.querySelectorAll<HTMLElement>('[role=option]')[1]).click());
    expect(changed).toHaveBeenCalledWith(7); expect(submitted).not.toHaveBeenCalled();
  });
});

describe('旧学伴身份展示兼容', () => {
  it('只转换旧学伴自我身份，保留教材中姓名和普通禾字', () => {
    expect(companionIdentityText('你好，我是小禾姐姐。和小盒一起想一想。')).toBe('你好，我是小陶。和小陶一起想一想。');
    expect(companionIdentityText('小禾 · AI 学伴')).toBe('小陶 · AI 学伴');
    expect(companionIdentityText('阅读材料：小禾在菜地观察禾苗。')).toBe('阅读材料：小禾在菜地观察禾苗。');
  });
});
