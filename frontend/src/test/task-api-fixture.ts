import type { RecommendationRead, TaskRead } from '@/features/tasks/task.types';
import { createSettingsFixture, fixtureJson } from '@/test/settings-api-fixture';

export function fixtureTask(id: number, status: TaskRead['status'] = 'UNSCHEDULED', title = `할 일 ${id}`): TaskRead {
  return { id, title, status, category: 'STUDY', durationMinutes: 60, priority: 'NORMAL', description: null,
    preferredDate: null, preferredDayOfWeek: null, preferredStartDate: null, preferredEndDate: null, preferredTimeRange: null,
    deadline: null, dateRequired: null, timeRequired: null, preferredStartTime: null,
    scheduledStartAt: status === 'SCHEDULED' ? '2026-10-26T19:00:00' : null, scheduledEndAt: status === 'SCHEDULED' ? '2026-10-26T20:00:00' : null };
}
export function fixtureRecommendation(taskId: number, status: RecommendationRead['status'] = 'PROPOSED', id = taskId * 10): RecommendationRead {
  return { id, taskId, status, recommendedStartAt: '2026-10-26T19:00:00', recommendedEndAt: '2026-10-26T20:00:00',
    rank: 1, score: 130, reason: '저장된 추천 근거', syncRetryable: status === 'SYNC_FAILED', matchType: 'GENERAL' };
}
export function createTaskFixture(initialTasks: TaskRead[] = [], initialHistory: Record<number, RecommendationRead[]> = {}) {
  const fixture = createSettingsFixture(); const settings = fixture.transport.getMockImplementation()!;
  let tasks = initialTasks;
  const histories = initialHistory;
  fixture.transport.mockImplementation(async (path, options) => {
    if (path === '/tasks') return fixtureJson(tasks);
    const match = String(path).match(/^\/tasks\/(\d+)(\/recommendations\?includeHistory=true)?$/);
    if (match) {
      const id = Number(match[1]); const task = tasks.find((item) => item.id === id);
      return task ? fixtureJson(match[2] ? histories[id] || [] : task) : fixtureJson({ code: 'NOT_FOUND_TASK' }, 404);
    }
    return settings(path, options);
  });
  return { ...fixture, setTasks: (values: TaskRead[]) => { tasks = values; }, histories };
}
