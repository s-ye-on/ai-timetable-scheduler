import { useCallback } from 'react';
import type { RecommendationRead, TaskRead } from '@/features/tasks/task.types';
import { TaskReadApi, currentSync } from '@/features/tasks/api/task-read.api';
import { useRemoteRead } from '@/shared/hooks/use-remote-read';

export function TaskSyncQuery({ api, task }: { api: TaskReadApi; task: TaskRead }) {
  const request = useCallback(() => api.history(task.id), [api, task.id]);
  const history = useRemoteRead(request);
  return <><TaskSyncBadge task={task} history={history.data} loading={history.loading} error={history.error} />
    {history.error && <button type="button" className="link-btn" onClick={history.refresh}>Calendar 상태 다시 확인</button>}</>;
}
export function TaskSyncBadge({ task, history, loading = false, error }: {
  task: TaskRead; history: RecommendationRead[] | null; loading?: boolean; error?: unknown;
}) {
  const current = history ? currentSync(task, history) : null;
  const label = loading ? 'Calendar 상태 확인 중' : error ? 'Calendar 상태 조회 실패' : current?.status === 'SYNCED' ? 'Google Calendar 동기화 완료'
    : current?.status === 'SYNC_FAILED' ? current.syncRetryable ? 'Calendar 동기화 실패 · 재시도 가능' : 'Calendar 동기화 확인 필요'
    : current?.status === 'SELECTED' ? 'Calendar 동기화 결과 확인 필요' : 'Calendar 동기화 확인 필요';
  return <span className={`badge ${current?.status === 'SYNCED' && !error && !loading ? 'success' : 'warning'}`}>{label}</span>;
}
