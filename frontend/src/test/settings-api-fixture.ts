import { vi } from 'vitest';
import type { Preference } from '@/features/preferences/preference.types';

export const DEFAULT_PREFERENCE: Preference = { id: 1, preferredTimeRange: 'ANYTIME', scheduleStartTime: '09:00:00', scheduleEndTime: '22:00:00', minimumGapMinutes: 10, scheduleDensity: 'BALANCED', deadlineTiming: 'BALANCED', customized: false };
export const fixtureJson = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
export function createSettingsFixture() {
  let preference = { ...DEFAULT_PREFERENCE };
  let connected = false;
  const transport = vi.fn<typeof fetch>().mockImplementation(async (path, options) => {
    if (path === '/api/auth/me') return fixtureJson({ id: 1, name: '학생', email: 'fixture@example.com' });
    if (path === '/timetable-slots') return fixtureJson([]);
    if (path === '/preferences') {
      if (options?.method === 'PUT') preference = { ...preference, ...JSON.parse(String(options.body)), customized: true };
      if (options?.method === 'DELETE') preference = { ...DEFAULT_PREFERENCE };
      return fixtureJson(preference);
    }
    if (path === '/api/calendar/status') return fixtureJson({ connected });
    if (path === '/api/calendar/oauth/url') return fixtureJson({ authUrl: `https://accounts.google.com/o/oauth2/auth?redirect_uri=${encodeURIComponent(window.location.origin + '/api/calendar/oauth/callback')}&state=fixture-state` });
    return fixtureJson({}, 404);
  });
  return { transport, getPreference: () => preference, setPreference: (value: Preference) => { preference = value; }, setConnected: (value: boolean) => { connected = value; } };
}
