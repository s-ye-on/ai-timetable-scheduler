import { useCallback, useEffect, useRef, useState } from 'react';
import { CalendarApi, calendarFailure, checkedAuthorizationUrl, OAuthAddressError } from '@/features/calendar/api/calendar.api';
import { AppIcon } from '@/app/components/app-icon';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

export function CalendarConnection({ api, manage = false, returnHref = '/#home', onReturn }: {
  api: CalendarApi; manage?: boolean; returnHref?: string; onReturn?: () => void;
}) {
  const [connected, setConnected] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [statusError, setStatusError] = useState('');
  const [oauthError, setOauthError] = useState('');
  const [returned, setReturned] = useState(false);
  const active = useRef(false);
  const generation = useRef(0);
  const popup = useRef<Window | null>(null);
  const opening = useRef(false);
  const check = useCallback(async () => {
    const version = ++generation.current; setLoading(true);
    try {
      const result = await api.status();
      if (active.current && version === generation.current) { setConnected(result); setStatusError(''); }
    } catch (failure) {
      if (active.current && version === generation.current) { setConnected(null); setStatusError(calendarFailure(failure)); }
    } finally { if (active.current && version === generation.current) setLoading(false); }
  }, [api]);
  useEffect(() => {
    active.current = true; void check();
    return () => { active.current = false; generation.current++; popup.current?.close(); popup.current = null; };
  }, [check]);
  useEffect(() => {
    if (!waiting) return;
    const refresh = () => { if (document.visibilityState !== 'hidden') { setReturned(true); void check(); } };
    const timer = window.setInterval(() => {
      if (popup.current?.closed) { popup.current = null; setWaiting(false); setReturned(true); void check(); }
    }, 750);
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { window.clearInterval(timer); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [waiting, check]);

  async function connect() {
    if (opening.current || waiting) return;
    setOauthError(''); setReturned(false);
    // 클릭 안에서 먼저 창을 열어 비동기 응답 후 팝업 차단을 피한다.
    let child: Window | null;
    try { child = window.open('about:blank', '_blank', 'popup,width=560,height=720'); } catch { child = null; }
    if (!child) { setOauthError('인증 창을 열 수 없어요. 이 사이트의 팝업을 허용한 뒤 다시 연결해 주세요.'); return; }
    opening.current = true; popup.current = child; setStarting(true);
    try {
      child.opener = null;
      const raw = await api.authorizationUrl();
      if (!active.current || child.closed) { child.close(); return; }
      const url = checkedAuthorizationUrl(raw, window.location);
      child.location.replace(url); setWaiting(true);
    } catch (failure) {
      child.close(); popup.current = null;
      if (active.current) setOauthError(failure instanceof OAuthAddressError ? failure.message : calendarFailure(failure));
    } finally { opening.current = false; if (active.current) setStarting(false); }
  }
  return <section className="card calendar-setup" aria-labelledby="calendar-title">
    <span className="calendar-art"><AppIcon name="timetable" /></span><h2 id="calendar-title">정한 시간을,<br />내 캘린더에서도 만나세요.</h2>
    <p>추천을 살펴본 뒤 직접 고른 일정을<br />Google Calendar에 등록할 수 있어요.</p>
    <ul className="benefits">{['이미 있는 Google 일정과 겹치는지 확인해요.', '내가 선택한 일정을 캘린더에 등록해요.', '지금 건너뛰어도 분석과 추천은 이용할 수 있어요.'].map((text) => <li key={text}><TimetableIcon name="check" />{text}</li>)}</ul>
    <div className="connection-state"><div className="od-field"><strong>{loading ? '연결 상태를 확인하고 있어요' : connected === true ? 'Google Calendar 연결됨' : connected === false ? '아직 연결하지 않았어요' : '연결 상태 확인 필요'}</strong>
      <small>{connected === true ? '최종 등록 시 충돌 여부를 다시 확인해요.' : '인증 후 돌아와 연결 상태를 새로고침해 주세요.'}</small></div>
      <button type="button" className="btn btn-secondary btn-sm" disabled={loading || starting} onClick={() => { setReturned(waiting); void check(); }}>상태 확인</button></div>
    {loading && <p className="small muted" role="status">서버의 Calendar 연결 상태를 확인하고 있어요.</p>}
    {statusError && <p className="notice error" role="alert">{statusError}</p>}
    {oauthError && <p className="notice error" role="alert">{oauthError}</p>}
    {waiting && <div className="notice" role="status">Google 인증 창에서 진행한 뒤 창을 닫고 돌아와 주세요. 동의만으로 연결 완료로 표시하지 않아요.</div>}
    {returned && !loading && connected === false && <p className="notice warn" role="status">연결이 아직 확인되지 않았어요. 인증 창의 취소·오류 안내를 확인한 뒤 상태를 다시 확인하거나 연결을 새로 시작해 주세요.</p>}
    <div className="notice"><div><strong>최종 일정 등록에는 연결이 필요해요</strong><p>연결하지 않아도 추천 후보까지 볼 수 있어요. Google 일정은 추천에 반영되지 않으며, 내부 일정만 별도로 확정하는 기능은 제공하지 않아요.</p></div></div>
    {connected === true && <p className="small muted calendar-policy">연결 전 생성한 후보는 검증 완료 상태가 아니에요. 재추천하거나 선택 시 최신 조건을 다시 확인해야 해요.</p>}
    <div className="actions">{connected !== true ? <button type="button" className="btn btn-primary" disabled={loading || connected === null || starting || waiting} onClick={() => { void connect(); }}>{starting ? '인증 창 준비 중…' : waiting ? '인증 확인 대기 중' : 'Google Calendar 연결하기'}</button>
      : <><button type="button" className="btn btn-secondary" disabled={loading || starting || waiting} onClick={() => { void connect(); }}>다시 연결하기</button><a className="btn btn-primary" href={returnHref} onClick={onReturn ? (event) => { event.preventDefault(); onReturn(); } : undefined}>{manage ? '원래 화면으로 돌아가기' : '내 플래너 시작하기'}<TimetableIcon name="next" /></a></>}</div>
    {waiting && <button type="button" className="btn btn-text calendar-cancel" onClick={() => { popup.current?.close(); popup.current = null; setWaiting(false); setReturned(true); void check(); }}>인증 창 닫고 상태 확인</button>}
    <a className="subtle-link" href={returnHref} onClick={onReturn ? (event) => { event.preventDefault(); onReturn(); } : undefined}>{manage ? '원래 화면으로 돌아가기' : connected === true ? '홈으로 돌아가기' : '나중에 연결하고 시작하기'}</a>
    {oauthError && <a className="subtle-link" href="/demo.html">기존 플래너에서 확인하기</a>}
  </section>;
}
