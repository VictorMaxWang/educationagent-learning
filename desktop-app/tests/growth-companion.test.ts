import { describe, expect, it } from 'vitest';
import { growthSuggestionBatch, growthRequestHistory, restoreGrowthMessages, GROWTH_CATEGORY_IDS, type GrowthMessage } from '../src/classroom/growth-topics';

const message = (id: string, content = '一段对话'): GrowthMessage => ({ id, content, role: 'user', category: 'needs' });
describe('成长陪伴话题与本机对话边界', () => {
  it('八种分类有稳定独立标识', () => {
    expect(GROWTH_CATEGORY_IDS).toHaveLength(8); expect(new Set(GROWTH_CATEGORY_IDS).size).toBe(8);
  });
  it('动态追问优先于固定问题且去重，换一批真实改变内容', () => {
    const first = growthSuggestionBatch(['继续问一', '继续问二'], ['默认一', '默认二', '默认三', '继续问一'], 0);
    expect(first).toEqual(['继续问一', '继续问二', '默认一']);
    const second = growthSuggestionBatch(['继续问一', '继续问二'], ['默认一', '默认二', '默认三', '继续问一'], 1);
    expect(second).not.toEqual(first); expect(new Set(second).size).toBe(second.length);
    expect(growthSuggestionBatch([], [], 1)).toEqual([]);
  });
  it('恢复历史拒绝非法JSON、伪造system角色和未知分类', () => {
    expect(restoreGrowthMessages('{bad')).toEqual([]);
    expect(restoreGrowthMessages(JSON.stringify([{ ...message('a'), role: 'system' }, { ...message('b'), category: 'unknown' }]))).toEqual([]);
    expect(restoreGrowthMessages(JSON.stringify([message('c')]))).toEqual([message('c')]);
  });
  it('历史只恢复最多40条，忽略过长正文并限制来源数据', () => {
    expect(restoreGrowthMessages(JSON.stringify(Array.from({ length: 50 }, (_, i) => message(String(i)))))).toHaveLength(40);
    expect(restoreGrowthMessages(JSON.stringify([message('a', 'x'.repeat(6501))]))).toEqual([]);
    const restored = restoreGrowthMessages(JSON.stringify([{ ...message('a'), role: 'assistant', sources: [{ file: '参考.txt', content: '文'.repeat(3000), distance: 0.2 }], suggestions: ['继续问', 7] }]));
    expect(restored[0].sources?.[0].content).toHaveLength(1800); expect(restored[0].suggestions).toEqual(['继续问']);
  });
  it('发往服务端的历史同时遵守条数、单条及总字符上限', () => {
    const history = growthRequestHistory(Array.from({ length: 40 }, (_, i) => ({ ...message(String(i), '字'.repeat(3000)), role: i % 2 ? 'assistant' as const : 'user' as const })));
    expect(history.length).toBeLessThanOrEqual(12);
    expect(history.reduce((count, item) => count + item.content.length, 0)).toBeLessThanOrEqual(12000);
    expect(history.every(item => item.content.length <= 2000)).toBe(true);
  });
  it('请求裁剪不拆开UTF16代理对，完整显示历史不被修改', () => {
    const source = [message('a', '甲'.repeat(1999) + '🌾后续')];
    const out = growthRequestHistory(source);
    expect(out[0].content).toHaveLength(1999); expect(source[0].content.endsWith('🌾后续')).toBe(true);
  });
});
