import { describe, expect, it, vi } from 'vitest';
import { TimetableApi, displayTime, sortSlots } from '@/features/timetable/api/timetable.api';
import { TIMETABLE_COLORS } from '@/features/timetable/timetable.types';
import { timetableFixture, classSlot } from '@/test/timetable-api-fixture';
import { weekDates } from '@/features/timetable/components/timetable-week';

describe('시간표_API_계약', () => {
  it.each(TIMETABLE_COLORS)('%s_색상을_생성_수정_재조회하고_새_API에서도_복원한다', async (color) => {
    const fixture = timetableFixture(); const api = new TimetableApi(fixture.client);
    const created = await api.save({ ...classSlot, subjectName: '공부 수업', color });
    expect(created.color).toBe(color);
    const updated = await api.save({ ...created, location: '새 강의실' }, created.id);
    expect(updated.color).toBe(color);
    expect(await new TimetableApi(fixture.client).read(created.id)).toMatchObject({ color, location: '새 강의실' });
    expect(fixture.request.mock.calls[0][1]?.method).toBe('POST');
    expect(fixture.request.mock.calls[1][1]?.method).toBe('PUT');
  });

  it('색상_생략_생성은_BLUE이고_수정_생략은_기존_색상을_그대로_전달한다', async () => {
    const fixture = timetableFixture([classSlot]); const api = new TimetableApi(fixture.client);
    const { color: _, ...draft } = classSlot;
    expect((await api.save(draft)).color).toBe('BLUE');
    expect((await api.save(draft, 1)).color).toBe('MINT');
    expect(JSON.parse(fixture.request.mock.calls[1][1]!.body as string)).not.toHaveProperty('color');
  });

  it('동일_과목의_두_슬롯은_서로_다른_색상을_유지한다', async () => {
    const fixture = timetableFixture(); const api = new TimetableApi(fixture.client);
    await api.save({ ...classSlot, color: 'BLUE' });
    await api.save({ ...classSlot, dayOfWeek: 'TUESDAY', color: 'ROSE' });
    expect((await api.list()).map((slot) => slot.color)).toEqual(['BLUE', 'ROSE']);
  });

  it.each([{}, null, [{ id: 1 }]])('잘못된_목록_%j를_빈_시간표로_바꾸지_않는다', async (body) => {
    const api = new TimetableApi({ authenticatedRequest: vi.fn().mockResolvedValue(body) });
    await expect(api.list()).rejects.toThrow();
  });

  it('목록은_7요일_순서이고_기존_초_단위_시각도_변경하지_않는다', () => {
    const slots = sortSlots([{ ...classSlot, id: 2, dayOfWeek: 'SUNDAY' }, { ...classSlot, startTime: '09:00:30.123456789' }]);
    expect(slots.map((slot) => slot.dayOfWeek)).toEqual(['MONDAY', 'SUNDAY']);
    expect(slots[0].startTime).toBe('09:00:30.123456789');
    expect(displayTime('09:00:00')).toBe('09:00'); expect(displayTime('09:00')).toBe('09:00');
    expect(displayTime(slots[0].startTime)).toBe(slots[0].startTime);
  });

  it('서울_자정과_연말_주간_날짜를_계산한다', () => {
    expect(weekDates(new Date('2026-12-31T15:00:00Z'), 0)).toEqual(['2026-12-28', '2026-12-29', '2026-12-30', '2026-12-31', '2027-01-01', '2027-01-02', '2027-01-03']);
    expect(weekDates(new Date('2026-10-25T15:00:00Z'), 0)[0]).toBe('2026-10-26');
  });
});
