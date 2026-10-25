import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { TaskRead, TaskReadClient, RecommendationRead } from '@/features/tasks/task.types';
import { TaskReadApi, currentSync } from '@/features/tasks/api/task-read.api';
import { TaskSyncBadge } from '@/features/tasks/components/task-sync-badge';
import { PlannerComposer } from '@/features/tasks/components/planner-composer';
import { TaskPlanningApi, type RecommendationGeneration } from '@/features/tasks/api/task-planning.api';
import { usePlannerAction } from '@/features/tasks/hooks/use-planner-action';
import { plannerError } from '@/features/tasks/planner-error';
import { RecommendationCandidates, MATCH_LABELS } from '@/features/tasks/components/recommendation-candidates';
import { CalendarApi } from '@/features/calendar/api/calendar.api';
import { CalendarConnection } from '@/features/calendar/components/calendar-connection';
import { useRemoteRead } from '@/shared/hooks/use-remote-read';
import { RemoteReadNotice } from '@/shared/components/remote-read-notice';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';
import { taskDateLabel, taskTimeLabel } from '@/features/tasks/task-time';
import { PERIOD_LABELS } from '@/features/preferences/preference.types';
import { visibleRecommendationReason } from '@/features/tasks/recommendation-reason';
import { useRegistrationGate } from '@/features/tasks/hooks/use-registration-gate';
import { registrationError } from '@/features/tasks/registration-error';

const TASK_LABELS = { UNSCHEDULED: '미확정', SCHEDULED: '일정 확정됨', COMPLETED: '완료된 할 일', CANCELED: '취소된 할 일' };
const CATEGORIES = { STUDY: '공부', ASSIGNMENT: '과제', APPOINTMENT: '약속', PERSONAL: '개인 일정' };
const STATUS_LABELS = { PROPOSED: '저장된 후보 · 최신 검증 전', SELECTED: '선택됨 · 동기화 확인 필요', REJECTED: '제외된 후보', EXPIRED: '만료된 후보', SYNCED: 'Calendar 동기화 완료', SYNC_FAILED: 'Calendar 동기화 실패' };

export function PlannerPage({ client, taskId }: { client: TaskReadClient; taskId?: number }) {
  const api = useMemo(() => new TaskReadApi(client), [client]);
  const [resolved, setResolved] = useState<TaskRead | null>(null);
  const [reopen, setReopen] = useState(0);
  const onResolved = useCallback((task: TaskRead) => { if (task.id === taskId) setResolved(task); }, [taskId]);
  return <div className="planner-layout planning-ui"><SavedTasks key={`list:${taskId || 'none'}:${reopen}`} api={api} activeId={taskId} resolved={resolved?.id === taskId ? resolved : null} onReopen={() => { setResolved(null); setReopen((value) => value + 1); }} />
    {taskId ? <RestoredTask key={`detail:${taskId}:${reopen}`} api={api} client={client} id={taskId} onResolved={onResolved} /> : <><div className="page-heading"><div><h1>나에게 맞는 시간 찾기</h1><p>편하게 입력하거나 저장한 할 일을 열어 이어서 계획해요.</p></div></div><PlannerComposer client={client} /></>}
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
      : <div className="empty"><span className="empty-symbol"><TimetableIcon name="book" /></span><h3>저장한 미확정 할 일이 없어요</h3><p>일정을 분석하고 저장하면 여기에 표시돼요.</p></div>)}
    <button type="button" className="link-btn" onClick={query.refresh} disabled={query.loading}>할 일 목록 새로고침</button>
  </div></details>;
}
function RestoredTask({ api, client, id, onResolved }: { api: TaskReadApi; client: TaskReadClient; id: number; onResolved: (task: TaskRead) => void }) {
  const readTask = useCallback(() => api.read(id), [api, id]); const readHistory = useCallback(() => api.history(id), [api, id]);
  const taskQuery = useRemoteRead(readTask); const history = useRemoteRead(readHistory); const task = taskQuery.data;
  const planning = useMemo(() => new TaskPlanningApi(client), [client]);
  const calendarApi = useMemo(() => new CalendarApi(client), [client]);
  const readCalendar = useCallback(() => calendarApi.status(), [calendarApi]); const calendar = useRemoteRead(readCalendar);
  const generation = usePlannerAction<RecommendationGeneration>();
  const registration = usePlannerAction<RecommendationRead>();
  const gate = useRegistrationGate(client, id);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [showCalendar, setShowCalendar] = useState(false);
  const [check, setCheck] = useState<{ task: number; history: number; calendar: number } | null>(null);
  const lastCheck = useRef(-1);
  const registrationFailure = registration.error ? registrationError(registration.error) : null;
  const failure = generation.error ? plannerError(generation.error, 'generation') : null;
  useEffect(() => { if (task) onResolved(task); }, [task, onResolved]);
  function refresh() {
    if (generation.pending || gate.pending) return;
    generation.invalidate(); setSelectedId(null);
    setCheck({ task: taskQuery.attempt + 1, history: history.attempt + 1, calendar: calendar.attempt + 1 });
    taskQuery.refresh(); history.refresh(); calendar.refresh();
  }
  useEffect(() => {
    if (gate.requiresCheck && !gate.pending && lastCheck.current !== gate.revision) {
      lastCheck.current = gate.revision; refresh();
    }
  }, [gate.requiresCheck, gate.pending, gate.revision]);
  useEffect(() => {
    if (check && !taskQuery.loading && !history.loading && !calendar.loading && taskQuery.data && history.data && calendar.data !== null
      && !taskQuery.error && !history.error && !calendar.error
      && taskQuery.attempt >= check.task && history.attempt >= check.history && calendar.attempt >= check.calendar) {
      gate.checked(); setCheck(null);
    }
  }, [check, taskQuery, history, calendar, gate]);
  const reading = taskQuery.loading || history.loading || calendar.loading || !!taskQuery.error || !!history.error || !!calendar.error;
  const busy = generation.pending || gate.pending || gate.requiresCheck || !!check;
  const current = task && !taskQuery.loading && !history.loading && !history.error && history.data ? currentSync(task, history.data) : null;
  const reserved = history.data?.some((item) => item.status === 'SELECTED' || item.status === 'SYNC_FAILED');
  const canChoose = task?.status === 'UNSCHEDULED' && !busy && !reading && !reserved;
  function submit(item: RecommendationRead, retry = false) {
    if (busy || reading || calendar.data !== true || registrationFailure?.manual) return;
    if (retry ? !current || current.id !== item.id || !item.syncRetryable || !['SELECTED', 'SYNC_FAILED'].includes(item.status)
      : !canChoose || item.status !== 'PROPOSED' || selectedId !== item.id) return;
    if (!gate.begin()) return;
    void registration.run(() => planning.select(id, item.id)).finally(gate.finish);
  }
  function generate() {
    if (!task || task.status !== 'UNSCHEDULED' || taskQuery.loading || history.loading || failure?.uncertain || busy || reserved) return;
    setSelectedId(null);
    void generation.run(() => planning.generate(id), () => { taskQuery.refresh(); history.refresh(); calendar.refresh(); });
  }
  const candidates = !generation.pending && !generation.error ? history.data?.filter((item) => item.status === 'PROPOSED').sort((a, b) => a.rank - b.rank || a.id - b.id) : null;
  const previous = history.data?.filter((item) => item.status !== 'PROPOSED');
  return <>
    <div className="planner-nav"><a className="return-link" href="/#home"><TimetableIcon name="back" />홈으로 돌아가기</a><a className="return-link" href="/#planner">새 일정 입력하기<TimetableIcon name="next" /></a></div>
    <div className="page-heading"><div><h1>내 일정에 딱 맞는 시간</h1><p>서버에 저장된 조건과 추천 이력을 다시 확인해요.</p></div></div>
      {!gate.pending && (gate.requiresCheck || check) && <div className="notice warning" role="status"><div><strong>서버의 등록 결과를 확인하고 있어요</strong><p>확인 전에는 재등록·재추천하지 않아요. 조회에 실패했다면 상태를 다시 확인해 주세요.</p><button className="btn btn-secondary btn-sm" onClick={refresh} disabled={taskQuery.loading || history.loading || calendar.loading}>등록 상태 다시 확인</button></div></div>}
      {registrationFailure && current?.status !== 'SYNCED' && <div className="notice error" role="alert"><div><strong>{registrationFailure.title}</strong><p>{registrationFailure.message}</p></div></div>}

    <RemoteReadNotice {...taskQuery} label="할 일 상세를" onRetry={refresh} />
    {task && <><div className="restored-inline" role="status"><TimetableIcon name="check" />저장된 할 일을 다시 불러왔어요.</div>
      <section className="card card-pad task-detail" aria-labelledby="task-detail-title"><div className="analysis-header"><span className="task-symbol color-lavender"><TimetableIcon name="book" /></span><div className="od-fill"><h2 id="task-detail-title">{task.title}</h2><p>저장된 일정 조건이에요.</p></div><span className="badge neutral">{TASK_LABELS[task.status]}</span></div>
        {task.status !== 'UNSCHEDULED' && <div className="notice"><div><strong>{TASK_LABELS[task.status]}</strong><p>미확정 할 일과 구분하며 Calendar 등록 결과는 아래에서 확인해요.</p></div></div>}
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
        </dl><div className="analysis-actions"><p>조건을 확인한 후 추천을 생성해요. 분석·추천만으로 일정이 확정되지 않아요.</p>
          {task.status === 'UNSCHEDULED' && <button className="btn btn-primary" type="button" onClick={generate} disabled={busy || reserved || taskQuery.loading || history.loading || !!history.error || !!failure?.uncertain}>{generation.pending ? '가능한 시간 찾는 중…' : candidates?.length ? '다시 추천받기' : '추천 시간 찾기'}</button>}</div>
      </section>
      <div className="notice" role="status">{calendar.data === false ? 'Google Calendar 미연동 상태예요. Google 일정은 충돌 검사에 포함되지 않아요. 추천은 가능하며 최종 등록에는 연결이 필요해요.' : calendar.data === true ? 'Google Calendar 일정을 반영해 추천해요. 후보를 확정할 때에는 최신 조건을 다시 검증해야 해요.' : 'Google Calendar 연결 상태를 확인 중이거나 확인하지 못했어요. 연결 여부를 추측하지 않으며, 추천 시 서버에서 일정을 확인해요.'}</div>
      {generation.pending && <p className="notice" role="status">수업·선호와 현재 일정을 확인해 추천하고 있어요.</p>}
      {failure && <div className="notice error" role="alert"><div><strong>{failure.title}</strong><p>{failure.message}</p><button className="btn btn-secondary btn-sm" type="button" onClick={refresh}>저장된 작업 상태 확인하기</button></div></div>}
      <section className="section-gap" aria-labelledby="history-title"><div className="result-heading"><div><h2 id="history-title">저장된 추천 이력</h2><p>후보 선택 후 최종 등록을 눌러야 최신 검증과 Calendar 등록을 진행해요.</p></div><button type="button" className="btn btn-secondary btn-sm" onClick={refresh} disabled={generation.pending || gate.pending || taskQuery.loading || history.loading}>작업 상태 새로고침</button></div>
        <p className="fit-help">추천 적합도는 규칙 기반 점수를 100점 척도로 표시한 값이며, 성공 확률이 아니에요.</p>
        {generation.data && <p className="notice recommendation-message" role="status">{generation.data.message}</p>}
        <RemoteReadNotice {...history} label="추천 이력을" onRetry={refresh} />
        {candidates && candidates.length === 0 && generation.data && <div className="card empty"><h3>조건에 맞는 추천 시간이 없어요</h3><p>필수 조건은 임의로 바꾸지 않았어요. 새 요청을 입력하거나 조건과 선호를 확인해 주세요.</p></div>}
        {history.data && !generation.pending && !generation.error && (history.data.length ? <>
          {!!candidates?.length && <><h3 className="history-group-title">저장된 추천 후보</h3><RecommendationCandidates items={candidates} selectedId={selectedId} onSelect={setSelectedId} disabled={!canChoose} />
            <div className="card confirm-bar"><div><h3>{selectedId ? '선택한 시간에 일정을 등록할까요?' : '원하는 시간을 선택해 주세요'}</h3><p>아직 등록되지 않았어요. 최종 등록 시 최신 충돌을 다시 검사해요.</p></div><button type="button" className="btn btn-primary" disabled={!canChoose || calendar.data !== true || !candidates.some((item) => item.id === selectedId) || !!registrationFailure?.manual} onClick={() => { const selected = candidates.find((item) => item.id === selectedId); if (selected) submit(selected); }}>Google Calendar에 등록</button></div>
          </>}
          {previous!.length > 0 && <h3 className="history-group-title">이전 처리 이력</h3>}{previous!.map((item) => <RecommendationHistoryRow key={item.id} item={item} />)}
        </> : !generation.data && <div className="card empty"><h3>저장된 추천 이력이 없어요</h3><p>추천 후보를 생성한 것으로 표시하지 않았어요.</p></div>)}
      </section>
      {gate.pending && <div className="card loading-card" role="status"><h2>Google Calendar에 등록하는 중이에요</h2><p>창을 이동해도 요청을 다시 보내지 않아요. 등록 결과를 확인해 주세요.</p></div>}
      {calendar.data === false && !showCalendar && <div className="notice"><button type="button" className="btn btn-secondary" onClick={() => setShowCalendar(true)} disabled={busy}>Google Calendar 연결하기</button><p>연결 후 작업 상태를 다시 확인해 주세요. 연결만으로 후보 검증이 완료되는 것은 아니에요.</p></div>}
      {showCalendar && <CalendarConnection api={calendarApi} manage returnHref={`/#planner?taskId=${id}`} onReturn={() => { setShowCalendar(false); refresh(); }} />}
      {current?.status === 'SYNCED' && !busy && <section className="card success-card" aria-label="일정 등록 완료"><div className="success-mark"><TimetableIcon name="check" /></div><h2>일정이 등록되었어요!</h2><p>서버에서 Google Calendar 동기화 완료를 확인했어요.</p><div className="success-detail"><h3>{task.title}</h3><p>{taskDateLabel(current.recommendedStartAt)} · {taskTimeLabel(current.recommendedStartAt)}–{taskTimeLabel(current.recommendedEndAt)}</p></div><a className="btn btn-primary" href="/#home">홈으로 돌아가기</a></section>}
      {task.status === 'SCHEDULED' && current?.status !== 'SYNCED' && !busy && !reading && <section className="card card-pad sync-recovery" aria-label="Calendar 동기화 복구"><h2>{current?.status === 'SYNC_FAILED' ? 'Calendar 동기화에 실패했어요' : 'Calendar 등록 결과를 확인해야 해요'}</h2><p>내부 일정은 확정되어 있어요. 다른 후보를 등록하거나 새로 추천받아 우회하지 않아요.</p>
        {current?.syncRetryable && ['SELECTED', 'SYNC_FAILED'].includes(current.status) && !registrationFailure?.manual ? <><p>같은 후보로 기존 이벤트를 확인하고 안전하게 복구할 수 있어요.</p><button type="button" className="btn btn-primary" onClick={() => submit(current, true)} disabled={busy || reading || calendar.data !== true}>Calendar 동기화 다시 시도</button></> : <p>자동으로 재시도할 수 없는 상태예요. Google Calendar의 실제 일정과 저장된 상태를 확인해 주세요.</p>}
        <button type="button" className="btn btn-secondary" onClick={refresh} disabled={gate.pending || generation.pending || taskQuery.loading || history.loading}>등록 상태 다시 확인</button>
      </section>}
    </>}
  </>;
}

function RecommendationHistoryRow({ item }: { item: RecommendationRead }) {
  const reason = visibleRecommendationReason(item.reason);
  return <article className="card history-row"><strong>{taskDateLabel(item.recommendedStartAt)} · {taskTimeLabel(item.recommendedStartAt)}–{taskTimeLabel(item.recommendedEndAt)}
    {item.recommendedStartAt.slice(0, 10) !== item.recommendedEndAt.slice(0, 10) && ` (${taskDateLabel(item.recommendedEndAt)} 종료)`}</strong>
    <span className={`badge ${item.status === 'SYNCED' ? 'success' : 'neutral'}`}>{item.status === 'PROPOSED' ? `후보 ${item.rank} · ` : ''}{STATUS_LABELS[item.status]}</span>
    {item.matchType && <span className="badge neutral">{MATCH_LABELS[item.matchType]}</span>}{reason && <p>{reason}</p>}
    {item.status === 'SYNC_FAILED' && <p>{item.syncRetryable ? '아래 동기화 복구 영역에서 같은 후보로 안전하게 다시 시도할 수 있어요.' : '동기화 상태를 먼저 확인해야 해요.'}</p>}
  </article>;
}
