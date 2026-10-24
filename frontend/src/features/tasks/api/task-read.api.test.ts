import { describe, expect, it, vi } from 'vitest';
import { TaskReadApi, currentSync, scheduledTasks } from '@/features/tasks/api/task-read.api';
import { fixtureTask, fixtureRecommendation } from '@/test/task-api-fixture';
import { seoulToday, taskTimeLabel, validLocalDateTime } from '@/features/tasks/task-time';
import { visibleRecommendationReason } from '@/features/tasks/recommendation-reason';

describe('Task_조회_계약', () => {
  it('확정_일정만_선택하고_생성순이_아닌_확정_시각순으로_정렬한다', () => {
    const early = { ...fixtureTask(9, 'SCHEDULED'), scheduledStartAt: '2026-10-25T09:00', scheduledEndAt: '2026-10-25T10:00' };
    const late = fixtureTask(1, 'SCHEDULED');
    expect(scheduledTasks([late, fixtureTask(3), fixtureTask(4, 'COMPLETED'), early, fixtureTask(5, 'CANCELED')]).map((item) => item.id)).toEqual([9, 1]);
  });
  it('SCHEDULED만으로_동기화_완료를_추정하지_않고_같은_시각의_최신_선택_이력을_사용한다', () => {
    const task = fixtureTask(1, 'SCHEDULED');
    const old = { ...fixtureRecommendation(1, 'SYNCED', 1), recommendedStartAt: '2026-10-25T19:00', recommendedEndAt: '2026-10-25T20:00' };
    expect(currentSync(task, [old])).toBeNull();
    const synced = fixtureRecommendation(1, 'SYNCED', 2), failed = fixtureRecommendation(1, 'SYNC_FAILED', 3);
    expect(currentSync(task, [old, synced, failed])?.status).toBe('SYNC_FAILED');
    expect(currentSync({ ...task, scheduledStartAt: '2026-10-26T19:00', scheduledEndAt: '2026-10-26T20:00' }, [synced])?.status).toBe('SYNCED');
  });
  it('상세와_전체_이력의_GET_계약을_사용하고_기존_null_조건을_보존한다', async () => {
    const task = fixtureTask(2); const history = fixtureRecommendation(2, 'EXPIRED');
    const request = vi.fn().mockResolvedValueOnce(task).mockResolvedValueOnce([history]); const api = new TaskReadApi({ authenticatedRequest: request });
    expect(await api.read(2)).toMatchObject({ dateRequired: null, timeRequired: null, preferredDate: null });
    expect(await api.history(2)).toEqual([history]);
    expect(request.mock.calls).toEqual([['/tasks/2'], ['/tasks/2/recommendations?includeHistory=true']]);
  });
  it.each([null, {}, [{ ...fixtureTask(1), status: 'confirmed' }], [{ ...fixtureTask(1), preferredDate: '2026-02-30' }]])('잘못된_목록은_빈_목록으로_위장하지_않는다_%j', async (value) => {
    await expect(new TaskReadApi({ authenticatedRequest: vi.fn().mockResolvedValue(value) }).list()).rejects.toThrow();
  });
  it('다른_Task_상세와_추천_응답은_화면으로_반환하지_않는다', async () => {
    await expect(new TaskReadApi({ authenticatedRequest: vi.fn().mockResolvedValue(fixtureTask(2)) }).read(1)).rejects.toThrow();
    await expect(new TaskReadApi({ authenticatedRequest: vi.fn().mockResolvedValue([fixtureRecommendation(2)]) }).history(1)).rejects.toThrow();
  });
  it('서울_자정과_분_시각을_브라우저_시간대와_무관하게_표시한다', () => {
    expect(seoulToday(new Date('2026-10-24T15:00:00Z'))).toBe('2026-10-25');
    expect(taskTimeLabel('2026-10-25T19:00')).toBe('19:00');
    expect(taskTimeLabel('2026-10-25T19:00:00')).toBe('19:00');
    expect(validLocalDateTime('2026-02-30T19:00')).toBe(false);
  });
  it('추천_이유의_숫자는_보존하며_점수_전용_구절만_제거한다', () => {
    expect(visibleRecommendationReason('최소 여유 10분 반영, 점수 87점')).toBe('최소 여유 10분 반영');
    expect(visibleRecommendationReason('내부 점수: 130, 선호 시간대 일치')).toBe('선호 시간대 일치');
    expect(visibleRecommendationReason('원점수 90점')).toBe('');
    expect(visibleRecommendationReason(null)).toBe('');
  });
});
