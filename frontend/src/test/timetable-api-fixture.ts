import { vi } from 'vitest';
import type { TimetableSlot } from '@/features/timetable/timetable.types';
import { AuthApiError } from '@/features/auth/api/auth-client';

/** UI 계약 검증용 저장소. 실제 Spring Boot·DB 검증 결과와 구분한다. */
export function timetableFixture(initial: TimetableSlot[] = []) {
  const rows = new Map(initial.map((slot) => [slot.id, { ...slot }]));
  let sequence = Math.max(0, ...rows.keys());
  const request = vi.fn(async (path: string, options?: RequestInit): Promise<unknown> => {
    const id = Number(path.split('/').at(-1));
    if (!options?.method) {
      if (path === '/timetable-slots') return [...rows.values()].map((slot) => ({ ...slot }));
      if (!rows.has(id)) throw new AuthApiError('not found', 404, {}, 'NOT_FOUND_TIMESLOT');
      return { ...rows.get(id)! };
    }
    if (options.method === 'DELETE') { rows.delete(id); return null; }
    const body = JSON.parse(options.body as string);
    const target = options.method === 'POST' ? ++sequence : id;
    const slot: TimetableSlot = { ...body, id: target, color: body.color || rows.get(target)?.color || 'BLUE' };
    rows.set(target, slot);
    return { ...slot };
  });
  return { rows, request, client: { authenticatedRequest: request } };
}
export const classSlot: TimetableSlot = { id: 1, subjectName: '운영체제', dayOfWeek: 'MONDAY', location: '공학관 302', startTime: '09:00:00', endTime: '10:30:00', color: 'MINT' };
