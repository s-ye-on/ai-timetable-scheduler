import { DEADLINE_TIMINGS, DENSITIES, TIME_RANGES } from '@/features/preferences/preference.types';
import type { Preference, PreferenceValues, SettingsClient } from '@/features/preferences/preference.types';

export const preferenceTime = (value: unknown): value is string => typeof value === 'string'
  && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(value);
export const preferenceDisplayTime = (value: string) => value.length > 5 ? value.replace(/:00(?:\.0+)?$/, '') : value;
function orderedTime(value: string): string {
  const [seconds = '00', fraction = ''] = (value.split(':')[2] || '').split('.');
  return value.slice(0, 5) + (seconds || '00') + fraction.padEnd(9, '0');
}
export function preferenceErrors(values: PreferenceValues): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!preferenceTime(values.scheduleStartTime) || !preferenceTime(values.scheduleEndTime)
    || orderedTime(values.scheduleStartTime) >= orderedTime(values.scheduleEndTime)) {
    errors.scheduleEndTime = '종료 시간을 시작 시간보다 늦게 설정해 주세요.';
  }
  if (!Number.isInteger(values.minimumGapMinutes) || values.minimumGapMinutes < 0
    || values.minimumGapMinutes > 2147483647 || values.minimumGapMinutes % 5 !== 0) {
    errors.minimumGapMinutes = '최소 여유 시간은 0 이상, 5분 단위로 입력해 주세요.';
  }
  return errors;
}
function parsePreference(body: unknown): Preference {
  const value = body as Preference | null;
  if (!value || typeof value !== 'object' || !Number.isSafeInteger(value.id) || value.id <= 0
    || !TIME_RANGES.includes(value.preferredTimeRange) || !DENSITIES.includes(value.scheduleDensity)
    || !DEADLINE_TIMINGS.includes(value.deadlineTiming) || typeof value.customized !== 'boolean'
    || Object.keys(preferenceErrors(value)).length) throw new Error('Invalid preference response');
  return value;
}
export class PreferenceApi {
  constructor(private readonly client: SettingsClient) {}
  async read(): Promise<Preference> { return parsePreference(await this.client.authenticatedRequest('/preferences')); }
  async save(values: PreferenceValues): Promise<Preference> {
    return parsePreference(await this.client.authenticatedRequest('/preferences', { method: 'PUT', body: JSON.stringify(values) }));
  }
  async reset(): Promise<Preference> {
    return parsePreference(await this.client.authenticatedRequest('/preferences', { method: 'DELETE' }));
  }
}
export function preferenceFailure(error: unknown) {
  const details = error as { status?: number; code?: string; fieldErrors?: Record<string, string> } | null;
  const message = details?.code === 'INVALID_PREFERENCE_GAP' ? '최소 여유 시간은 0 이상, 5분 단위로 입력해 주세요.'
    : details?.code === 'INVALID_PREFERENCE_TIME_RANGE' ? '종료 시간을 시작 시간보다 늦게 설정해 주세요.'
    : details?.status === 400 ? '선호 입력값을 확인해 주세요.'
    : details?.status === 403 ? '선호 설정에 접근할 권한이 없어요.'
    : details?.status === 401 ? '로그인이 만료되었어요. 다시 로그인해 주세요.'
    : '선호 설정을 확인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.';
  return { message, fields: details?.fieldErrors || {}, uncertain: !details?.status || details.status >= 500 };
}
