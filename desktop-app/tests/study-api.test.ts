import { beforeEach, describe, expect, it, vi } from 'vitest';
const request = vi.hoisted(() => vi.fn());
vi.mock('../src/classroom/api', () => ({ classroomRequest: request }));
import { refreshRecommendations } from '../src/classroom/study-api';

beforeEach(() => request.mockReset().mockResolvedValue({ status: 'queued' }));
describe('补充资料显式重新准备', () => {
  it('仅手动动作携带manual标记，自动准备允许复用缓存', async () => {
    const controller = new AbortController();
    await refreshRecommendations('course', 'scene', 10, controller.signal);
    await refreshRecommendations('course', 'scene', 10, controller.signal, true);
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ sceneId: 'scene', budgetMinutes: 10 });
    expect(JSON.parse(request.mock.calls[1][1].body)).toEqual({ sceneId: 'scene', budgetMinutes: 10, manual: true });
    expect(request.mock.calls[1][1].signal).toBe(controller.signal);
  });
});
