import { expect, it, vi } from 'vitest';
import { TaskPlanningApi } from '@/features/tasks/api/task-planning.api';
import { fixtureTask, fixtureRecommendation } from '@/test/task-api-fixture';
import { recommendationFit } from '@/features/tasks/components/recommendation-candidates';

it('Create_응답의_없는_확정시각만_null로_연결하고_저장된_조건을_보존한다', async () => {
  const { scheduledStartAt: _start, scheduledEndAt: _end, ...created } = { ...fixtureTask(4), preferredDate: '2026-10-29', preferredStartTime: '19:10', dateRequired: true, timeRequired: true };
  const request = vi.fn().mockResolvedValue(created);
  expect(await new TaskPlanningApi({ authenticatedRequest: request }).analyze('한 시간 뒤 공부 1시간')).toEqual({ ...created, scheduledStartAt: null, scheduledEndAt: null });
  expect(request.mock.calls).toEqual([['/tasks/natural-language', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"message":"한 시간 뒤 공부 1시간"}' }]]);
});
it.each(['', '   ', '가'.repeat(501)])('잘못된_입력은_서버에_전송하지_않는다_%s', async (message) => {
  const request = vi.fn(); await expect(new TaskPlanningApi({ authenticatedRequest: request }).analyze(message)).rejects.toThrow(); expect(request).not.toHaveBeenCalled();
});
it.each([null, {}, { ...fixtureTask(1), status: 'SCHEDULED' }, { ...fixtureTask(1), preferredStartTime: '25:10' }])('잘못된_분석_응답을_저장_성공으로_표시하지_않는다_%j', async (response) => {
  await expect(new TaskPlanningApi({ authenticatedRequest: vi.fn().mockResolvedValue(response) }).analyze('공부 1시간')).rejects.toThrow();
});
it('빈_추천과_메시지는_정상_계약이고_점수나_서버_순위를_변경하지_않는다', async () => {
  const items = [{ ...fixtureRecommendation(1), rank: 2, score: 139 }, { ...fixtureRecommendation(1, 'PROPOSED', 12), rank: 1, score: 70 }];
  const request = vi.fn().mockResolvedValueOnce({ message: '추천 없음', recommendations: [] }).mockResolvedValueOnce({ message: '안내', recommendations: items });
  const api = new TaskPlanningApi({ authenticatedRequest: request });
  expect(await api.generate(1)).toEqual({ message: '추천 없음', recommendations: [] });
  expect((await api.generate(1)).recommendations).toEqual(items);
  expect(request.mock.calls.every(([path, options]) => path === '/tasks/1/recommendations' && options.method === 'POST')).toBe(true);
});
it.each([
  {}, [], { message: null, recommendations: [] }, { message: '안내', recommendations: [fixtureRecommendation(2)] },
  { message: '안내', recommendations: [fixtureRecommendation(1, 'SYNCED')] },
  { message: '안내', recommendations: [fixtureRecommendation(1), fixtureRecommendation(1)] },
  { message: '안내', recommendations: [fixtureRecommendation(1), fixtureRecommendation(1, 'PROPOSED', 12)] },
])('다른_Task_잘못된_상태_중복_순위_등의_생성_응답을_거절한다_%j', async (body) => {
  await expect(new TaskPlanningApi({ authenticatedRequest: vi.fn().mockResolvedValue(body) }).generate(1)).rejects.toThrow();
});
it('표시만_140점_기준으로_소수점_한자리_환산한다', () => {
  expect([0, 70, 87, 130, 140].map(recommendationFit)).toEqual([0, 50, 62.1, 92.9, 100]);
});

it.each(['SYNCED', 'SYNC_FAILED', 'SELECTED'] as const)('등록_%s_응답을_성공으로_뭉개지_않고_서버_상태대로_반환한다', async (status) => {
  const item = fixtureRecommendation(1, status);
  const request = vi.fn().mockResolvedValue(item);
  expect(await new TaskPlanningApi({ authenticatedRequest: request }).select(1, 10)).toEqual(item);
  expect(request.mock.calls).toEqual([['/recommendations/10/select', { method: 'POST' }]]);
});
it.each([null, {}, fixtureRecommendation(2, 'SYNCED'), fixtureRecommendation(1, 'PROPOSED'), fixtureRecommendation(1, 'SYNCED', 11)])('잘못된_등록_응답의_후보_Task_상태를_거절한다_%j', async (body) => {
  await expect(new TaskPlanningApi({ authenticatedRequest: vi.fn().mockResolvedValue(body) }).select(1,10)).rejects.toThrow();
});
