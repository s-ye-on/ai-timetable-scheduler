export const TIMETABLE_DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
export type TimetableDay = typeof TIMETABLE_DAYS[number];
export const TIMETABLE_COLORS = ['BLUE', 'MINT', 'PEACH', 'LAVENDER', 'ROSE', 'LEMON', 'LIME', 'AQUA', 'APRICOT', 'SLATE'] as const;
export type TimetableColor = typeof TIMETABLE_COLORS[number];
export const DAY_LABELS: Record<TimetableDay, string> = { MONDAY: '월', TUESDAY: '화', WEDNESDAY: '수', THURSDAY: '목', FRIDAY: '금', SATURDAY: '토', SUNDAY: '일' };
export const COLOR_LABELS: Record<TimetableColor, string> = { BLUE: '소프트 블루', MINT: '민트', PEACH: '피치', LAVENDER: '라벤더', ROSE: '로즈', LEMON: '레몬', LIME: '라임', AQUA: '아쿠아', APRICOT: '살구', SLATE: '슬레이트' };
export interface TimetableDraft {
  subjectName: string;
  dayOfWeek: TimetableDay;
  location: string | null;
  startTime: string;
  endTime: string;
  color?: TimetableColor;
}
export interface TimetableSlot extends TimetableDraft { id: number; color: TimetableColor }
export interface TimetableRequestClient { authenticatedRequest(path: string, options?: RequestInit): Promise<unknown> }
export type TimetableFieldErrors = Partial<Record<keyof TimetableDraft, string>>;
