import { Fragment, useMemo, useState } from 'react';
import type { AppRoute } from '@/app/app-routing';
import { navigateApp } from '@/app/app-routing';
import type { SettingsClient } from '@/features/preferences/preference.types';
import { PreferenceApi } from '@/features/preferences/api/preference.api';
import { PreferenceForm } from '@/features/preferences/components/preference-form';
import { CalendarApi } from '@/features/calendar/api/calendar.api';
import { CalendarConnection } from '@/features/calendar/components/calendar-connection';
import { TimetableApi } from '@/features/timetable/api/timetable.api';
import { TimetableWorkspace } from '@/features/timetable/components/timetable-workspace';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

const titles = ['나만의 한 주를 만들어 볼까요?', '일정 추천 기준을 정해볼까요?', '준비는 가볍게, 하루는 편하게.'];
const descriptions = ['수업 시간표를 알려주면, 그 사이에 딱 맞는 시간을 찾아요.', '평소 편한 시간대와 일정 스타일을 설정해 주세요. 기본값으로 시작해도 괜찮아요.', 'Google Calendar는 지금 연결해도, 나중에 연결해도 괜찮아요.'];

export function OnboardingPage({ client, route }: { client: SettingsClient; route: AppRoute }) {
  const [busy, setBusy] = useState(false);
  const timetable = useMemo(() => new TimetableApi(client), [client]);
  const preferences = useMemo(() => new PreferenceApi(client), [client]);
  const calendar = useMemo(() => new CalendarApi(client), [client]);
  const step = route.step || 1;
  const manage = route.mode === 'manage';
  const returnHref = `/#${route.returnTo || 'home'}`;
  const linkProps = { 'aria-disabled': busy || undefined, onClick: (event: React.MouseEvent) => { if (busy) event.preventDefault(); } };
  return <div className="onboard-layout">
    <a className="return-link" href={manage ? returnHref : '/#home'} {...linkProps}><TimetableIcon name="back" />{manage ? '원래 화면으로 돌아가기' : '홈으로 돌아가기'}</a>
    <div className="onboard-intro"><div className="section-kicker">{manage ? 'MY PREFERENCES' : 'A GOOD START TO YOUR SEMESTER'}</div><h1>{manage ? '나의 선호 · 연동' : titles[step - 1]}</h1><p>{descriptions[step - 1]}</p></div>
    {!manage && <div className="steps" aria-label={`초기 설정 ${step} / 3 단계`}>{['수업 시간표', '나의 선호', '캘린더 연결'].map((label, index) => <Fragment key={label}>
      {index > 0 && <span className="step-line" aria-hidden="true" />}<div className={`step ${index + 1 === step ? 'current' : index + 1 < step ? 'done' : ''}`} aria-current={index + 1 === step ? 'step' : undefined}>
        <span className="step-num">{index + 1 < step ? <TimetableIcon name="check" /> : index + 1}</span><span>{label}</span></div></Fragment>)}</div>}
    {step === 1 && <div className="course-onboarding"><TimetableWorkspace api={timetable} onBusyChange={setBusy} /></div>}
    {step === 2 && <PreferenceForm api={preferences} manage={manage} onBusyChange={setBusy} onSaved={() => navigateApp(manage ? returnHref : '/#onboarding/3')} />}
    {(step === 3 || manage) && <div className={manage ? 'managed-calendar' : undefined}><CalendarConnection api={calendar} manage={manage} returnHref={manage ? returnHref : '/#home'} /></div>}
    {!manage && <div className="setup-footer"><p className="muted">지금 설정하지 않아도 괜찮아요. 나중에 언제든 할 수 있어요.</p><div className="actions">
      {step > 1 && <a className="btn btn-secondary" href={`/#onboarding/${step - 1}`} {...linkProps}><TimetableIcon name="back" />이전</a>}
      {step < 3 && <a className="btn btn-text" href={`/#onboarding/${step + 1}`} {...linkProps}>{step === 2 ? '저장하지 않고 건너뛰기' : '나중에 할게요'}</a>}
      {step === 1 && <a className="btn btn-primary" href="/#onboarding/2" {...linkProps}>다음 단계<TimetableIcon name="next" /></a>}
    </div></div>}
  </div>;
}
