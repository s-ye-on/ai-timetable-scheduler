import { useCallback, useEffect, useMemo, useState } from 'react';
import type { UserProfile } from '@/features/auth/types/auth.types';
import type { SettingsClient } from '@/features/preferences/preference.types';
import { PERIOD_LABELS } from '@/features/preferences/preference.types';
import { PreferenceApi, preferenceDisplayTime } from '@/features/preferences/api/preference.api';
import { CalendarApi } from '@/features/calendar/api/calendar.api';
import { TimetableApi } from '@/features/timetable/api/timetable.api';
import { TaskReadApi, scheduledTasks } from '@/features/tasks/api/task-read.api';
import { taskDateLabel, taskTimeLabel, seoulToday } from '@/features/tasks/task-time';
import { TaskSyncQuery } from '@/features/tasks/components/task-sync-badge';
import { PlannerComposerPreview } from '@/features/tasks/components/planner-composer-preview';
import { HomeTimetable } from '@/features/home/components/home-timetable';
import { useRemoteRead } from '@/shared/hooks/use-remote-read';
import { RemoteReadNotice } from '@/shared/components/remote-read-notice';
import { AppIcon } from '@/app/components/app-icon';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

export function HomePage({ client, profile }: { client: SettingsClient; profile: UserProfile }) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const timer = window.setInterval(() => setNow(new Date()), 60000); return () => window.clearInterval(timer); }, []);
  const apis = useMemo(() => ({ tasks: new TaskReadApi(client), timetable: new TimetableApi(client), preferences: new PreferenceApi(client), calendar: new CalendarApi(client) }), [client]);
  const date = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(now);
  return <div className="home-page planning-ui"><div className="page-heading"><div className="home-greeting"><span className="greeting-dot" aria-hidden="true" /><div>
    <h1>{profile.name}님, 오늘도 나답게.</h1><p>수업도, 하고 싶은 일도. 나에게 맞는 속도로 계획해요.</p></div></div><time className="date-label" dateTime={seoulToday(now)}><AppIcon name="timetable" />{date}</time></div>
    <PlannerComposerPreview />
    <div className="home-grid"><HomeTimetable api={apis.timetable} now={now} /><aside className="right-rail">
      <ScheduledTasks api={apis.tasks} /><HomeCalendar api={apis.calendar} preferenceApi={apis.preferences} />
    </aside></div>
  </div>;
}
function ScheduledTasks({ api }: { api: TaskReadApi }) {
  const request = useCallback(() => api.list(), [api]); const query = useRemoteRead(request);
  const tasks = query.data ? scheduledTasks(query.data) : [];
  return <section className="card rail-card" aria-labelledby="scheduled-title"><div className="rail-title"><h2 id="scheduled-title">확정된 일정</h2><span className="tiny">시간순</span></div>
    <RemoteReadNotice {...query} label="확정된 일정을" onRetry={query.refresh} />
    {query.data && (tasks.length ? <><ul className="task-list scheduled-task-list">{tasks.map((task) => <li className="task-row" key={`${task.id}:${query.attempt}:${task.scheduledStartAt}:${task.scheduledEndAt}`}>
      <span className="task-symbol color-blue"><AppIcon name="timetable" /></span><div className="task-text"><a href={`/#planner?taskId=${task.id}`}>
        <strong>{task.title}</strong>{task.scheduledStartAt && task.scheduledEndAt ? <><p>{taskDateLabel(task.scheduledStartAt)}</p><p>{taskTimeLabel(task.scheduledStartAt)}–{taskTimeLabel(task.scheduledEndAt)}{task.scheduledStartAt.slice(0, 10) !== task.scheduledEndAt.slice(0, 10) && ` (${taskDateLabel(task.scheduledEndAt)} 종료)`}</p></> : <p>확정 날짜·시간을 다시 확인해 주세요.</p>}</a>
        <span className="badge neutral">일정 확정됨</span><TaskSyncQuery task={task} api={api} />
      </div></li>)}</ul><button type="button" className="link-btn" onClick={query.refresh}>일정 목록 새로고침</button></> : <div className="empty"><span className="empty-symbol"><AppIcon name="timetable" /></span>
      <h3>아직 확정된 일정이 없어요</h3><p>일정 추천에서 저장한 할 일을 열고 시간을 확인해 보세요.</p><a className="link-btn" href="/#planner">저장한 할 일 보기<TimetableIcon name="next" /></a></div>)}
  </section>;
}
function HomeCalendar({ api, preferenceApi }: { api: CalendarApi; preferenceApi: PreferenceApi }) {
  const request = useCallback(() => api.status(), [api]); const query = useRemoteRead(request);
  return <section className="card rail-card" aria-label="Calendar와 나의 선호"><div className="od-row connection-heading"><span className="connection-symbol"><AppIcon name="timetable" /></span>
    <div className="od-field"><strong className="connection-title">Google Calendar</strong><span className="small muted">{query.loading ? '연결 상태 확인 중' : query.error ? '연결 상태 확인 필요' : query.data === true ? '연결됨' : '아직 연결하지 않았어요'}</span></div></div>
    <RemoteReadNotice {...query} label="Calendar 상태를" onRetry={query.refresh} />
    {!query.loading && !query.error && (query.data ? <><p>선택한 일정을 Google Calendar에 등록할 수 있어요. 최종 등록 때 최신 충돌 여부를 다시 확인해요.</p><button className="btn btn-secondary" type="button" onClick={query.refresh}>연결 상태 새로고침</button></>
      : <><p>연결 없이도 분석과 추천은 가능해요. Google 일정은 반영되지 않으며 최종 일정 등록에는 연결이 필요해요.</p><a className="btn btn-secondary" href="/#onboarding/2?mode=manage&returnTo=home">Google Calendar 연결하기</a></>)}
    <PreferenceSummary api={preferenceApi} />
  </section>;
}
function PreferenceSummary({ api }: { api: PreferenceApi }) {
  const request = useCallback(() => api.read(), [api]); const query = useRemoteRead(request); const preference = query.data;
  return <div className="preference-note"><AppIcon name="settings" /><div><RemoteReadNotice {...query} label="나의 선호를" onRetry={query.refresh} />
    {preference && <p>내 선호 · {PERIOD_LABELS[preference.preferredTimeRange]}<br />{preferenceDisplayTime(preference.scheduleStartTime)}–{preferenceDisplayTime(preference.scheduleEndTime)} · 여유 {preference.minimumGapMinutes}분</p>}
    <a href="/#onboarding/2?mode=manage&returnTo=home">선호 설정 살펴보기</a></div></div>;
}
