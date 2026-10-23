import { TIMETABLE_COLORS, TIMETABLE_DAYS } from '@/features/timetable/timetable.types';
import type { TimetableDraft, TimetableRequestClient, TimetableSlot } from '@/features/timetable/timetable.types';

export function timeMinutes(value: string): number {
  const [hours, minutes, seconds = '0'] = value.split(':');
  return Number(hours) * 60 + Number(minutes) + Number(seconds) / 60;
}
export function validTime(value: unknown): value is string {
  return typeof value === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(value);
}
export function displayTime(value: string): string { return value.replace(/:00(?:\.0+)?$/, (match) => value.length > 5 ? '' : match); }
function slot(value: unknown): TimetableSlot {
  if (!value || typeof value !== 'object') throw new Error('수업 응답을 확인하지 못했어요. 목록을 다시 확인해 주세요.');
  const item = value as Record<string, unknown>;
  if (!Number.isSafeInteger(item.id) || Number(item.id) <= 0 || typeof item.subjectName !== 'string' || !item.subjectName.trim()
    || !TIMETABLE_DAYS.includes(item.dayOfWeek as TimetableSlot['dayOfWeek'])
    || (item.location !== null && typeof item.location !== 'string') || !validTime(item.startTime) || !validTime(item.endTime)
    || timeMinutes(item.startTime) >= timeMinutes(item.endTime) || !TIMETABLE_COLORS.includes(item.color as TimetableSlot['color'])) {
    throw new Error('수업 응답을 확인하지 못했어요. 목록을 다시 확인해 주세요.');
  }
  return item as unknown as TimetableSlot;
}
export function sortSlots(slots: TimetableSlot[]): TimetableSlot[] {
  return [...slots].sort((a, b) => TIMETABLE_DAYS.indexOf(a.dayOfWeek) - TIMETABLE_DAYS.indexOf(b.dayOfWeek)
    || timeMinutes(a.startTime) - timeMinutes(b.startTime) || a.id - b.id);
}

/** JWT 갱신은 주입받은 기존 인증 클라이언트가 담당한다. */
export class TimetableApi {
  constructor(private readonly client: TimetableRequestClient) {}
  async list(): Promise<TimetableSlot[]> {
    const body = await this.client.authenticatedRequest('/timetable-slots');
    if (!Array.isArray(body)) throw new Error('수업 목록을 확인하지 못했어요. 다시 시도해 주세요.');
    return sortSlots(body.map(slot));
  }
  async read(id: number): Promise<TimetableSlot> { return slot(await this.client.authenticatedRequest(`/timetable-slots/${id}`)); }
  async save(draft: TimetableDraft, id?: number): Promise<TimetableSlot> {
    return slot(await this.client.authenticatedRequest(id ? `/timetable-slots/${id}` : '/timetable-slots', {
      method: id ? 'PUT' : 'POST', body: JSON.stringify(draft),
    }));
  }
  async remove(id: number): Promise<void> { await this.client.authenticatedRequest(`/timetable-slots/${id}`, { method: 'DELETE' }); }
}

export function timetableFailure(error: unknown): { message: string; fields: Record<string, string>; uncertain: boolean } {
  const details = error && typeof error === 'object' ? error as { status?: number; code?: string; fieldErrors?: Record<string, string> } : {};
  const message = details.code === 'CONFLICT_TIMETABLE_SLOT' ? '이미 등록한 수업과 시간이 겹쳐요. 요일이나 시간을 바꿔 주세요.'
    : details.code === 'NOT_FOUND_TIMESLOT' ? '이 수업을 찾을 수 없어요. 목록을 다시 확인해 주세요.'
    : details.code === 'INVALID_TIMETABLE_SLOT_TIME' ? '종료 시간을 시작 시간보다 늦게 설정해 주세요.'
    : details.status === 403 ? '이 수업에 접근할 권한이 없어요.'
    : details.status === 400 ? '입력 내용을 확인해 주세요.'
    : details.status === 401 ? '로그인이 만료되었어요. 다시 로그인해 주세요.'
    : '서버에서 수업 정보를 확인하지 못했어요. 네트워크 상태를 확인하고 다시 시도해 주세요.';
  return { message, fields: details.fieldErrors || {}, uncertain: !details.status || details.status >= 500 };
}
