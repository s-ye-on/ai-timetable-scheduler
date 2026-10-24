import { useCallback, useEffect, useMemo, useState } from 'react';
import type { TaskRead, TaskReadClient, RecommendationRead } from '@/features/tasks/task.types';
import { TaskReadApi } from '@/features/tasks/api/task-read.api';
import { TaskSyncBadge } from '@/features/tasks/components/task-sync-badge';
import { PlannerComposerPreview } from '@/features/tasks/components/planner-composer-preview';
import { useRemoteRead } from '@/shared/hooks/use-remote-read';
import { RemoteReadNotice } from '@/shared/components/remote-read-notice';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';
import { taskDateLabel, taskTimeLabel } from '@/features/tasks/task-time';
import { PERIOD_LABELS } from '@/features/preferences/preference.types';
import { visibleRecommendationReason } from '@/features/tasks/recommendation-reason';

const TASK_LABELS = { UNSCHEDULED: '미확정', SCHEDULED: '일정 확정됨', COMPLETED: '완료된 할 일', CANCELED: '취소된 할 일' };
const CATEGORIES = { STUDY: '공부', ASSIGNMENT: '과제', APPOINTMENT: '약속', PERSONAL: '개인 일정' };
const STATUS_LABELS = { PROPOSED: '저장된 후보 · 최신 검증 전', SELECTED: '선택됨 · 동기화 확인 필요', REJECTED: '제외된 후보', EXPIRED: '만료된 후보', SYNCED: 'Calendar 동기화 완료', SYNC_FAILED: 'Calendar 동기화 실패' };
const MATCH_LABELS = { EXACT: '원조건 일치', TIME_CHANGED: '시간 변경', DATE_CHANGED: '날짜 변경', DATE_AND_TIME_CHANGED: '날짜·시간 변경', GENERAL: '원조건 없음' };

export function PlannerPage({ client, taskId }: { client: TaskReadClient; taskId?: number }) {
  const api = useMemo(() => new TaskReadApi(client), [client]);
  const [resolved, setResolved] = useState<TaskRead | null>(null);
  const [reopen, setReopen] = useState(0);
  const onResolved = useCallback((task: TaskRead) => { if (task.id === taskId) setResolved(task); }, [taskId]);
  return <div className="planner-layout planning-ui"><SavedTasks key={`list:${taskId || 'none'}:${reopen}`} api={api} activeId={taskId} resolved={resolved?.id === taskId ? resolved : null} onReopen={() => { setResolved(null); setReopen((value) => value + 1); }} />
    {taskId ? <RestoredTask key={`detail:${taskId}:${reopen}`} api={api} id={taskId} onResolved={onResolved} /> : <><div className="page-heading"><div><h1>나에게 맞는 시간 찾기</h1><p>저장한 할 일을 열어 조건과 추천 이력을 확인해 보세요.</p></div></div><PlannerComposerPreview /></>}
  </div>;
}
function SavedTasks({ api, activeId, resolved, onReopen }: { api: TaskReadApi; activeId?: number; resolved: TaskRead | null; onReopen: () => void }) {
  const request = useCallback(() => api.list(), [api]); const query = useRemoteRead(request);
  const tasks = query.data?.filter((task) => task.status === 'UNSCHEDULED' && !(resolved?.id === task.id && resolved.status !== 'UNSCHEDULED'));
  return <details className="card saved-tasks-panel" open={!activeId}><summary><TimetableIcon name="book" /><strong>저장한 할 일</strong><span className="saved-tasks-count">{tasks ? `${tasks.length}개` : query.error ? '조회 실패' : '조회 중'}</span><TimetableIcon name="next" /></summary><div className="saved-tasks-body">
    <RemoteReadNotice {...query} label="저장한 할 일을" onRetry={query.refresh} />
    {tasks && (tasks.length ? <><p className="saved-tasks-help">분석해 둔 할 일을 선택해 이어서 계획해요.</p><ul className="task-list saved-task-list">{tasks.map((task) => <li className="task-row" key={task.id}>
      <span className="task-symbol color-lavender"><TimetableIcon name="book" /></span><div className="task-text"><a href={`/#planner?taskId=${task.id}`} aria-current={activeId === task.id ? 'true' : undefined} onClick={(event) => { if (activeId === task.id) { event.preventDefault(); onReopen(); } }}>
        <strong>{task.title}</strong><p>{task.durationMinutes}분 · 분석 결과 다시 보기</p><span className="badge neutral">미확정</span></a></div></li>)}</ul></>
      : <div className="empty"><span className="empty-symbol"><TimetableIcon name="book" /></span><h3>저장한 미확정 할 일이 없어요</h3><p>기존 플래너에서 일정을 분석하면 여기에 저장돼요.</p></div>)}
    <button type="button" className="link-btn" onClick={query.refresh} disabled={query.loading}>할 일 목록 새로고침</button>
  </div></details>;
}
function RestoredTask({ api, id, onResolved }: { api: TaskReadApi; id: number; onResolved: (task: TaskRead) => void }) {
  const readTask = useCallback(() => api.read(id), [api, id]); const readHistory = useCallback(() => api.history(id), [api, id]);
  const taskQuery = useRemoteRead(readTask); const history = useRemoteRead(readHistory); const task = taskQuery.data;
  useEffect(() => { if (task) onResolved(task); }, [task, onResolved]);
  function refresh() { taskQuery.refresh(); history.refresh(); }
  const candidates = history.data?.filter((item) => item.status === 'PROPOSED').sort((a, b) => a.rank - b.rank || a.id - b.id);
  const previous = history.data?.filter((item) => item.status !== 'PROPOSED');
  return <>
    <a className="return-link" href="/#home"><TimetableIcon name="back" />홈으로 돌아가기</a>
    <div className="page-heading"><div><h1>내 일정에 딱 맞는 시간</h1><p>서버에 저장된 조건과 추천 이력을 다시 확인해요.</p></div></div>
    <RemoteReadNotice {...taskQuery} label="할 일 상세를" onRetry={refresh} />
    {task && <><div className="restored-inline" role="status"><TimetableIcon name="check" />저장된 할 일을 다시 불러왔어요.</div>
      <section className="card card-pad task-detail" aria-labelledby="task-detail-title"><div className="analysis-header"><span className="task-symbol color-lavender"><TimetableIcon name="book" /></span><div className="od-fill"><h2 id="task-detail-title">{task.title}</h2><p>저장된 일정 조건이에요.</p></div><span className="badge neutral">{TASK_LABELS[task.status]}</span></div>
        {task.status !== 'UNSCHEDULED' && <div className="notice"><div><strong>{TASK_LABELS[task.status]}</strong><p>미확정 할 일과 구분해 읽기 전용으로 표시해요.</p></div></div>}
        {task.status === 'SCHEDULED' && <div className="scheduled-detail">{task.scheduledStartAt && task.scheduledEndAt ? <p><strong>{taskDateLabel(task.scheduledStartAt)}</strong><br />{taskTimeLabel(task.scheduledStartAt)}–{taskTimeLabel(task.scheduledEndAt)}</p> : <p>확정 날짜·시간을 확인해 주세요.</p>}
          <TaskSyncBadge task={task} history={history.data} loading={history.loading} error={history.error} /></div>}
        <dl className="condition-grid"><div><dt>분류</dt><dd>{CATEGORIES[task.category]}</dd></div><div><dt>소요 시간</dt><dd>{task.durationMinutes}분</dd></div>
          <div><dt>요청 날짜</dt><dd>{task.preferredDate ? taskDateLabel(task.preferredDate) : task.preferredDayOfWeek ? ({ MONDAY: '월요일', TUESDAY: '화요일', WEDNESDAY: '수요일', THURSDAY: '목요일', FRIDAY: '금요일', SATURDAY: '토요일', SUNDAY: '일요일' }[task.preferredDayOfWeek] || task.preferredDayOfWeek) : '날짜 미지정'}</dd></div>
          <div><dt>요청 시작 시각</dt><dd>{task.preferredStartTime || '시각 미지정'} · {task.preferredTimeRange ? PERIOD_LABELS[task.preferredTimeRange] || task.preferredTimeRange : '시간대 미지정'}</dd></div>
          <div><dt>날짜 조건</dt><dd>{task.dateRequired === true ? '필수 날짜' : task.dateRequired === false ? '선호 날짜 · 대안 가능' : '기존 날짜 제한 정책 유지'}</dd></div>
          <div><dt>시간 조건</dt><dd>{task.timeRequired === true ? '필수 시간' : task.timeRequired === false ? '선호 시간 · 대안 가능' : '기존 시간대 선호 정책 유지'}</dd></div>
          <div><dt>허용 기간</dt><dd>{task.preferredStartDate || '시작 제한 없음'} ~ {task.preferredEndDate || '종료 제한 없음'}</dd></div><div><dt>마감일</dt><dd>{task.deadline || '마감 미지정'}</dd></div>
          <div><dt>중요도</dt><dd>{{ HIGH: '높음', NORMAL: '보통', LOW: '낮음' }[task.priority]}</dd></div>
          {task.description && <div className="full"><dt>저장된 설명</dt><dd>{task.description}</dd></div>}
        </dl><div className="analysis-actions"><p>이 화면은 저장된 작업을 확인하는 단계예요. 추천 생성과 일정 등록은 기존 플래너에서 이용해 주세요.</p><a className="btn btn-secondary" href="/demo.html">기존 플래너 이용하기</a></div>
      </section>
      <section className="section-gap" aria-labelledby="history-title"><div className="result-heading"><div><h2 id="history-title">저장된 추천 이력</h2><p>후보는 최신 충돌 검증을 마친 상태가 아니에요. 이 화면에서 선택하거나 등록하지 않아요.</p></div><button type="button" className="btn btn-secondary btn-sm" onClick={refresh} disabled={taskQuery.loading || history.loading}>작업 상태 새로고침</button></div>
        <RemoteReadNotice {...history} label="추천 이력을" onRetry={refresh} />
        {history.data && (history.data.length ? <>
          {candidates!.length > 0 && <h3 className="history-group-title">저장된 추천 후보</h3>}{candidates!.map((item) => <RecommendationHistoryRow key={item.id} item={item} />)}
          {previous!.length > 0 && <h3 className="history-group-title">이전 처리 이력</h3>}{previous!.map((item) => <RecommendationHistoryRow key={item.id} item={item} />)}
        </> : <div className="card empty"><h3>저장된 추천 이력이 없어요</h3><p>추천 후보를 생성한 것으로 표시하지 않았어요.</p></div>)}
      </section>
    </>}
  </>;
}

function RecommendationHistoryRow({ item }: { item: RecommendationRead }) {
  const reason = visibleRecommendationReason(item.reason);
  return <article className="card history-row"><strong>{taskDateLabel(item.recommendedStartAt)} · {taskTimeLabel(item.recommendedStartAt)}–{taskTimeLabel(item.recommendedEndAt)}
    {item.recommendedStartAt.slice(0, 10) !== item.recommendedEndAt.slice(0, 10) && ` (${taskDateLabel(item.recommendedEndAt)} 종료)`}</strong>
    <span className={`badge ${item.status === 'SYNCED' ? 'success' : 'neutral'}`}>{item.status === 'PROPOSED' ? `후보 ${item.rank} · ` : ''}{STATUS_LABELS[item.status]}</span>
    {item.matchType && <span className="badge neutral">{MATCH_LABELS[item.matchType]}</span>}{reason && <p>{reason}</p>}
    {item.status === 'SYNC_FAILED' && <p>{item.syncRetryable ? '기존 플래너에서 안전한 동기화 재시도를 할 수 있어요.' : '동기화 상태를 먼저 확인해야 해요.'}</p>}
  </article>;
}
