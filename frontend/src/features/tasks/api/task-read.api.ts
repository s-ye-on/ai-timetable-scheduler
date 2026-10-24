import { TASK_STATUSES, RECOMMENDATION_STATUSES } from '@/features/tasks/task.types';
import type { TaskRead, TaskReadClient, RecommendationRead } from '@/features/tasks/task.types';
import { localDateTimeKey, validLocalDate, validLocalDateTime } from '@/features/tasks/task-time';

export function parseTask(body: unknown): TaskRead {
  const task = body as TaskRead | null;
  if (!task || typeof task !== 'object' || !Number.isSafeInteger(task.id) || task.id <= 0 || typeof task.title !== 'string' || !task.title.trim()
    || !TASK_STATUSES.includes(task.status) || !Number.isInteger(task.durationMinutes) || task.durationMinutes <= 0
    || !['STUDY', 'ASSIGNMENT', 'APPOINTMENT', 'PERSONAL'].includes(task.category) || !['HIGH', 'NORMAL', 'LOW'].includes(task.priority)
    || ![true, false, null].includes(task.dateRequired) || ![true, false, null].includes(task.timeRequired)
    || [task.preferredDate, task.preferredStartDate, task.preferredEndDate, task.deadline].some((value) => value !== null && !validLocalDate(value))
    || [task.scheduledStartAt, task.scheduledEndAt].some((value) => value !== null && !validLocalDateTime(value))
    || task.description !== null && typeof task.description !== 'string'
    || task.preferredDayOfWeek !== null && !['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].includes(task.preferredDayOfWeek)
    || task.preferredTimeRange !== null && !['ANYTIME', 'MORNING', 'LUNCH', 'AFTERNOON', 'EVENING'].includes(task.preferredTimeRange)
    || task.preferredStartTime !== null && !/^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(task.preferredStartTime)) throw new Error('Invalid task response');
  return task;
}
export function parseHistory(body: unknown, taskId: number): RecommendationRead[] {
  if (!Array.isArray(body)) throw new Error('Invalid recommendation history');
  return body.map((item: RecommendationRead) => {
    if (!item || !Number.isSafeInteger(item.id) || item.id <= 0 || item.taskId !== taskId || !RECOMMENDATION_STATUSES.includes(item.status)
      || !validLocalDateTime(item.recommendedStartAt) || !validLocalDateTime(item.recommendedEndAt)
      || localDateTimeKey(item.recommendedStartAt) >= localDateTimeKey(item.recommendedEndAt)
      || !Number.isInteger(item.rank) || item.rank <= 0 || !Number.isInteger(item.score) || typeof item.syncRetryable !== 'boolean'
      || item.reason !== null && typeof item.reason !== 'string'
      || ![null, 'EXACT', 'TIME_CHANGED', 'DATE_CHANGED', 'DATE_AND_TIME_CHANGED', 'GENERAL'].includes(item.matchType)) throw new Error('Invalid recommendation history');
    return item;
  });
}
/** M4는 조회 API만 제공한다. 생성·선택·재시도는 후속 단계에서 별도로 연결한다. */
export class TaskReadApi {
  constructor(private readonly client: TaskReadClient) {}
  async list(): Promise<TaskRead[]> {
    const body = await this.client.authenticatedRequest('/tasks');
    if (!Array.isArray(body)) throw new Error('Invalid tasks response');
    return body.map(parseTask);
  }
  async read(id: number): Promise<TaskRead> {
    const task = parseTask(await this.client.authenticatedRequest(`/tasks/${id}`));
    if (task.id !== id) throw new Error('Task identity mismatch');
    return task;
  }
  async history(id: number): Promise<RecommendationRead[]> {
    return parseHistory(await this.client.authenticatedRequest(`/tasks/${id}/recommendations?includeHistory=true`), id);
  }
}
export function scheduledTasks(tasks: TaskRead[]): TaskRead[] {
  return tasks.filter((task) => task.status === 'SCHEDULED').sort((a, b) => {
    const start = (task: TaskRead) => task.scheduledStartAt ? localDateTimeKey(task.scheduledStartAt) : '~';
    return start(a).localeCompare(start(b)) || a.id - b.id;
  });
}
export function currentSync(task: TaskRead, history: RecommendationRead[]) {
  if (!task.scheduledStartAt || !task.scheduledEndAt) return null;
  return history.filter((item) => ['SELECTED', 'SYNCED', 'SYNC_FAILED'].includes(item.status)
    && localDateTimeKey(item.recommendedStartAt) === localDateTimeKey(task.scheduledStartAt!)
    && localDateTimeKey(item.recommendedEndAt) === localDateTimeKey(task.scheduledEndAt!)).sort((a, b) => b.id - a.id)[0] || null;
}
