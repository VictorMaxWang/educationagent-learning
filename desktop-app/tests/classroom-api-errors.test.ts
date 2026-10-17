import { afterEach, expect, it, vi } from 'vitest';
import { classroomRequest } from '../src/classroom/api';

afterEach(() => vi.unstubAllGlobals());

it('保留评阅未知业务码，供界面区分新评阅与网络恢复', async () => {
  const fetch = vi.fn(async () => new Response(JSON.stringify({ success: false, code: 'EVALUATION_UNKNOWN', error: '评阅结果未确认' }), { status: 409 }));
  vi.stubGlobal('fetch', fetch);
  await expect(classroomRequest('/courses/c/answer', { method: 'POST' })).rejects.toMatchObject({ status: 409, code: 'EVALUATION_UNKNOWN', message: '评阅结果未确认' });
  expect(fetch).toHaveBeenCalledTimes(1);
});

it('一般内容冲突不伪造评阅未知业务码', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: '内容已更新' }), { status: 409 })));
  const error = await classroomRequest('/courses/c/answer').catch(error => error);
  expect(error.status).toBe(409); expect(error.code).toBeUndefined();
});

it('网络中断保留原错误并且不自动发起第二次请求', async () => {
  const failure = new TypeError('Failed to fetch');
  const fetch = vi.fn().mockRejectedValue(failure); vi.stubGlobal('fetch', fetch);
  await expect(classroomRequest('/courses/c/answer')).rejects.toBe(failure);
  expect(fetch).toHaveBeenCalledTimes(1);
});
