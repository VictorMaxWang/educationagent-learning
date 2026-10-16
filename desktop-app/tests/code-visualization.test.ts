import { describe, expect, it } from 'vitest';
import {
  codeContext, compareCodeOutput, displayTraceValue, isCodeJobActive, numericTraceArray,
  numericTraceSeries, resolveTraceValue, shouldPollCodeJob, traceReference, traceVariables, type CodeJob, type TraceStep,
} from '../src/practice/code-api';
import { CODE_EXAMPLES, CODE_LANGUAGES } from '../src/practice/code-examples';

const step: TraceStep = {
  index: 0, line: 4, event: 'line', stdout: '',
  frames: [{ name: 'sum(2)', locals: { n: 2, numbers: { $ref: 'list-1' }, unknown: { $unavailable: '尚未初始化' } } }, { name: 'sum(3)', locals: { n: 3 } }],
  heap: { 'list-1': { type: 'array', items: [8, 3, 6, 1], length: 4, truncated: false } },
};

describe('四语言代码可视化的真实轨迹适配', () => {
  it('每种语言各有四个完整可编辑任务与对应输入、输出和递进提示', () => {
    expect(CODE_LANGUAGES.map(item => item.id)).toEqual(['javascript', 'python', 'cpp', 'java']);
    for (const { id } of CODE_LANGUAGES) {
      expect(CODE_EXAMPLES[id].map(item => item.id)).toEqual(['area', 'sum', 'sort', 'recursion']);
      for (const item of CODE_EXAMPLES[id]) {
        expect(item.code.trim().length).toBeGreaterThan(40); expect(item.stdin).not.toBe('');
        expect(item.expectedStdout).not.toBe(''); expect(item.hints).toHaveLength(3);
      }
    }
    expect(CODE_EXAMPLES.java[2].code).toContain('split("\\\\s+")');
    expect(CODE_EXAMPLES.javascript[0].code).toContain('split(/\\s+/)');
  });
  it('保留递归的不同栈帧，同名参数不覆盖', () => {
    const variables = traceVariables(step);
    expect(variables.find(item => item.key === '0:n')?.value).toBe(2);
    expect(variables.find(item => item.key === '1:n')?.value).toBe(3);
  });
  it('解析运行器真实 heap 数组、缺失引用与不可读值', () => {
    expect(traceReference({ $ref: 'list-1' })).toBe('list-1');
    expect(numericTraceArray({ $ref: 'list-1' }, step)).toEqual([8, 3, 6, 1]);
    expect(numericTraceArray([1, -2.5, 0])).toEqual([1, -2.5, 0]);
    expect(numericTraceArray([1, '2'])).toBeUndefined();
    expect(resolveTraceValue({ $ref: 'missing' }, step)).toBeUndefined();
    expect(displayTraceValue({ $unavailable: '尚未初始化' })).toBe('暂不可读取：尚未初始化');
  });
  it('数值曲线仅来自截止选中步骤的真实值，忽略缺失和非数值', () => {
    const next: TraceStep = { ...step, index: 1, frames: [{ name: 'sum(2)', locals: { n: 7 } }] };
    const missing: TraceStep = { ...step, index: 2, frames: [] };
    expect(numericTraceSeries([step, next, missing], '0:n', 0)).toEqual([{ step: 0, value: 2 }]);
    expect(numericTraceSeries([step, next, missing], '0:n', 2)).toEqual([{ step: 0, value: 2 }, { step: 1, value: 7 }]);
  });
  it('输出检查兼容换行和行末空格，但不掩盖有意义的不同结果', () => {
    expect(compareCodeOutput('1 3 6 8\r\n', '1 3 6 8')).toBe(true);
    expect(compareCodeOutput('1  3 6 8\n', '1 3 6 8')).toBe(false);
    expect(compareCodeOutput('12\n13', '12')).toBe(false);
  });
  it('只有执行状态或尚有轨迹待收尾的取消任务继续轮询', () => {
    for (const status of ['queued', 'running', 'completed', 'timeout', 'error', 'cancelled'] as const) {
      expect(isCodeJobActive({ status } as CodeJob)).toBe(status === 'queued' || status === 'running');
      expect(shouldPollCodeJob({ status } as CodeJob)).toBe(status === 'queued' || status === 'running');
    }
    expect(shouldPollCodeJob({ status: 'cancelled', tracePending: true } as CodeJob)).toBe(true);
    expect(isCodeJobActive({ status: 'cancelled', tracePending: true } as CodeJob)).toBe(false);
    expect(shouldPollCodeJob({ status: 'timeout', tracePending: true } as CodeJob)).toBe(false);
  });
  it('问答上下文带真实步骤并限制代码、输入和输出长度', () => {
    const context = codeContext({ language: 'python', code: 'a'.repeat(15000), stdin: 'b'.repeat(4000) }, { ...step, stdout: 'c'.repeat(3000) });
    expect(context.length).toBeLessThanOrEqual(4000); expect(context).toContain('第 4 行');
    expect(context).toContain('sum(2).n=2'); expect(context).not.toContain('a'.repeat(6001));
  });
  it('长文件把步骤、变量、输出和输入放在源码前，并保留远处选中行', () => {
    const source = Array.from({ length: 300 }, (_, index) => index === 249 ? 'total += current; // SELECTED_SOURCE_LINE' : `const value${index} = ${index}; // ${'很长的注释'.repeat(15)}`).join('\n');
    const context = codeContext({ language: 'javascript', code: source, stdin: 'SPECIAL_INPUT' }, { ...step, line: 250, stdout: 'SPECIAL_OUTPUT' });
    expect(context.length).toBeLessThanOrEqual(4000);
    expect(context.indexOf('第 250 行')).toBeLessThan(context.indexOf('代码（'));
    expect(context.indexOf('sum(2).n=2')).toBeLessThan(context.indexOf('代码（'));
    expect(context.indexOf('SPECIAL_INPUT')).toBeLessThan(context.indexOf('代码（'));
    expect(context.indexOf('SPECIAL_OUTPUT')).toBeLessThan(context.indexOf('代码（'));
    expect(context).toContain('> 250: total += current; // SELECTED_SOURCE_LINE');
    expect(context).toContain('附近源码（行号与源文件一致）');
  });
});
