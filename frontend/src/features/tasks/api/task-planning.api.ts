import type { RecommendationRead, TaskRead, TaskReadClient } from '@/features/tasks/task.types';
import { parseHistory, parseTask } from '@/features/tasks/api/task-read.api';

export interface RecommendationGeneration { message: string; recommendations: RecommendationRead[] }

/** 쓰기 요청은 명시적인 사용자 동작에서만 호출한다. 최종 등록 API는 제공하지 않는다. */
export class TaskPlanningApi {
  constructor(private readonly client: TaskReadClient) {}
  async analyze(message: string): Promise<TaskRead> {
    if (!message.trim() || message.length > 500) throw new Error('Invalid natural-language input');
    const body = await this.client.authenticatedRequest('/tasks/natural-language', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message }),
    });
    if (!body || typeof body !== 'object') throw new Error('Invalid created task');
    // Create 응답에는 확정 시각이 없다. Read와 공통인 조건 계약을 검증한다.
    const task = parseTask({ ...body, scheduledStartAt: null, scheduledEndAt: null });
    if (task.status !== 'UNSCHEDULED') throw new Error('Unexpected created task status');
    return task;
  }
  async generate(id: number): Promise<RecommendationGeneration> {
    const body = await this.client.authenticatedRequest(`/tasks/${id}/recommendations`, { method: 'POST' });
    if (!body || typeof body !== 'object' || !('message' in body) || typeof body.message !== 'string'
      || !('recommendations' in body)) throw new Error('Invalid generation response');
    const recommendations = parseHistory(body.recommendations, id);
    if (recommendations.some((item) => item.status !== 'PROPOSED')
      || new Set(recommendations.map((item) => item.id)).size !== recommendations.length
      || new Set(recommendations.map((item) => item.rank)).size !== recommendations.length) throw new Error('Invalid generated candidates');
    return { message: body.message, recommendations };
  }
}
