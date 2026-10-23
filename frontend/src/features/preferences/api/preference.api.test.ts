import { describe, expect, it, vi } from 'vitest';
import { PreferenceApi, preferenceErrors } from '@/features/preferences/api/preference.api';
import { AuthClient } from '@/features/auth/api/auth-client';
import { DEFAULT_PREFERENCE, createSettingsFixture, fixtureJson } from '@/test/settings-api-fixture';

describe('선호_API_계약', () => {
  it('전체_여섯_필드를_저장하고_DELETE_200_응답으로_기본값을_복원한다', async () => {
    const fixture = createSettingsFixture();
    const api = new PreferenceApi(new AuthClient(localStorage, fixture.transport));
    expect(await api.read()).toEqual(DEFAULT_PREFERENCE);
    const values = { preferredTimeRange: 'EVENING' as const, scheduleStartTime: '10:10', scheduleEndTime: '23:45', minimumGapMinutes: 125, scheduleDensity: 'RELAXED' as const, deadlineTiming: 'NEAR_DEADLINE' as const };
    expect(await api.save(values)).toEqual({ id: 1, ...values, customized: true });
    expect(fixture.getPreference().minimumGapMinutes).toBe(125);
    expect(JSON.parse(String(fixture.transport.mock.calls[1][1]?.body))).toEqual(values);
    expect(await api.reset()).toEqual(DEFAULT_PREFERENCE);
    expect(fixture.getPreference().customized).toBe(false);
    expect(fixture.transport.mock.calls[2][1]?.method).toBe('DELETE');
  });
  it.each([null, {}, { ...DEFAULT_PREFERENCE, preferredTimeRange: 'ANY' }, { ...DEFAULT_PREFERENCE, deadlineTiming: 'EARLY' }, { ...DEFAULT_PREFERENCE, minimumGapMinutes: 3 }])('잘못된_선호_응답을_기본값으로_위장하지_않는다_%j', async (value) => {
    const api = new PreferenceApi({ authenticatedRequest: vi.fn().mockResolvedValue(value) });
    await expect(api.read()).rejects.toThrow();
  });
  it('5분_단위와_시간_경계를_확인하고_초_정밀도와_120분_초과를_보존한다', () => {
    expect(preferenceErrors({ ...DEFAULT_PREFERENCE, minimumGapMinutes: 125 })).toEqual({});
    expect(preferenceErrors({ ...DEFAULT_PREFERENCE, minimumGapMinutes: 7 })).toHaveProperty('minimumGapMinutes');
    expect(preferenceErrors({ ...DEFAULT_PREFERENCE, minimumGapMinutes: -5 })).toHaveProperty('minimumGapMinutes');
    expect(preferenceErrors({ ...DEFAULT_PREFERENCE, scheduleStartTime: '22:00', scheduleEndTime: '22:00:00' })).toHaveProperty('scheduleEndTime');
    expect(preferenceErrors({ ...DEFAULT_PREFERENCE, scheduleStartTime: '09:00:00.1', scheduleEndTime: '09:00:00.2' })).toEqual({});
  });
  it('기존_AuthClient가_쿠키와_JWT를_같은_출처로_전달한다', async () => {
    const storage = { getItem: () => 'fixture-access', setItem: vi.fn(), removeItem: vi.fn() };
    const transport = vi.fn<typeof fetch>().mockResolvedValue(fixtureJson(DEFAULT_PREFERENCE));
    await new PreferenceApi(new AuthClient(storage, transport)).read();
    expect(transport.mock.calls[0][1]?.credentials).toBe('same-origin');
    expect(new Headers(transport.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer fixture-access');
  });
});
