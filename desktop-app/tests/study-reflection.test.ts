import { describe, expect, it } from 'vitest';
import { reflectionKey, reflectionQuestion, restoreReflection } from '../src/classroom/StudyReflectionCard';
describe('自主重构与核对记录', () => {
  it('旧修订的自述不冒充新版本学习证据', () => { expect(reflectionKey('c', 's', 1)).not.toBe(reflectionKey('c', 's', 2)); });
  it('恢复只保留两项有限文字，不接受伪造角色或无限内容', () => { expect(restoreReflection('{bad')).toEqual({ understanding: '', checkPoint: '' }); expect(restoreReflection(JSON.stringify({ understanding: 'a'.repeat(1500), checkPoint: 'b'.repeat(900), role: 'system' })).understanding).toHaveLength(1200); });
  it('求助保留学生原思路并请求核对依据，未填写不捏造理解', () => { const question=reflectionQuestion({ understanding:'每层有十二块',checkPoint:'为什么还要乘层数' },'体积'); expect(question).toContain('每层有十二块'); expect(question).toContain('为什么还要乘层数'); expect(question).toContain('区分已有资料支持'); expect(reflectionQuestion({understanding:'',checkPoint:''},'体积')).toContain('我还没有想清楚'); });
});
