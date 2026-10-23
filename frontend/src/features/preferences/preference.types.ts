import type { AuthClient } from '@/features/auth/api/auth-client';

export type SettingsClient = Pick<AuthClient, 'authenticatedRequest'>;
export const TIME_RANGES = ['ANYTIME', 'MORNING', 'LUNCH', 'AFTERNOON', 'EVENING'] as const;
export const DENSITIES = ['COMPACT', 'BALANCED', 'RELAXED'] as const;
export const DEADLINE_TIMINGS = ['ASAP', 'BALANCED', 'NEAR_DEADLINE'] as const;
export interface PreferenceValues {
  preferredTimeRange: typeof TIME_RANGES[number];
  scheduleStartTime: string;
  scheduleEndTime: string;
  minimumGapMinutes: number;
  scheduleDensity: typeof DENSITIES[number];
  deadlineTiming: typeof DEADLINE_TIMINGS[number];
}
export interface Preference extends PreferenceValues { id: number; customized: boolean }
