import type { SettingsClient } from '@/features/preferences/preference.types';
export type TaskReadClient = SettingsClient;
export const TASK_STATUSES = ['UNSCHEDULED', 'SCHEDULED', 'COMPLETED', 'CANCELED'] as const;
export const RECOMMENDATION_STATUSES = ['PROPOSED', 'SELECTED', 'REJECTED', 'EXPIRED', 'SYNCED', 'SYNC_FAILED'] as const;
export interface TaskRead {
  id: number; title: string; category: 'STUDY' | 'ASSIGNMENT' | 'APPOINTMENT' | 'PERSONAL'; durationMinutes: number;
  preferredDate: string | null; preferredDayOfWeek: string | null; preferredStartDate: string | null; preferredEndDate: string | null;
  preferredTimeRange: string | null; deadline: string | null; priority: 'HIGH' | 'NORMAL' | 'LOW'; description: string | null;
  dateRequired: boolean | null; timeRequired: boolean | null; preferredStartTime: string | null;
  scheduledStartAt: string | null; scheduledEndAt: string | null; status: typeof TASK_STATUSES[number];
}
export interface RecommendationRead {
  id: number; taskId: number; recommendedStartAt: string; recommendedEndAt: string; rank: number; score: number;
  reason: string | null; status: typeof RECOMMENDATION_STATUSES[number]; syncRetryable: boolean;
  matchType: 'EXACT' | 'TIME_CHANGED' | 'DATE_CHANGED' | 'DATE_AND_TIME_CHANGED' | 'GENERAL' | null;
}
