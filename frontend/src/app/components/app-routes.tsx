import { useEffect, useLayoutEffect, useState, useSyncExternalStore } from 'react';
import { AuthApiError, authClient } from '@/features/auth/api/auth-client';
import type { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { AuthRoutes } from '@/features/auth/components/auth-routes';
import type { ReactNode } from 'react';
import type { UserProfile } from '@/features/auth/types/auth.types';
import { getLocation, navigateApp, resolveAppRoute, subscribeLocation } from '@/app/app-routing';
import type { AppRoute } from '@/app/app-routing';
import { AppShell } from '@/app/components/app-shell';
import { TimetablePage } from '@/features/timetable/components/timetable-page';
import { OnboardingPage } from '@/features/onboarding/components/onboarding-page';
import { HomePage } from '@/features/home/components/home-page';
import { PlannerPage } from '@/features/tasks/components/planner-page';

export function AppRoutes({ client = authClient }: { client?: AuthClient }) {
  const location = useSyncExternalStore(subscribeLocation, getLocation);
  const pathname = window.location.pathname.replace(/\/$/, '') || '/';
  const publicRoute = pathname === '/login' || pathname === '/signup';
  useLayoutEffect(() => {
    const previous = document.body.classList.contains('auth-page');
    document.body.classList.toggle('auth-page', publicRoute);
    return () => { document.body.classList.toggle('auth-page', previous); };
  }, [publicRoute]);
  const version = useSyncExternalStore(client.subscribe, client.getSessionVersion);
  const [externalVersion, setExternalVersion] = useState(0);
  useEffect(() => {
    const storageChanged = (event: StorageEvent) => {
      if (event.storageArea && event.storageArea !== window.localStorage) return;
      if (event.key === null || event.key === AUTH_STORAGE_KEYS.accessToken || event.key === AUTH_STORAGE_KEYS.refreshToken) {
        client.invalidateSession();
        setExternalVersion((value) => value + 1);
      }
    };
    const restored = (event: PageTransitionEvent) => {
      if (event.persisted) { client.invalidateSession(); setExternalVersion((value) => value + 1); }
    };
    window.addEventListener('storage', storageChanged);
    window.addEventListener('pageshow', restored);
    return () => { window.removeEventListener('storage', storageChanged); window.removeEventListener('pageshow', restored); };
  }, [client]);

  if (publicRoute) return <AuthRoutes key={`${pathname}:${externalVersion}`} client={client} navigate={navigateApp} />;
  const route = pathname === '/' ? resolveAppRoute(window.location.hash) : resolveAppRoute('#not-found');
  return <ProtectedApp key={version} client={client} route={route} location={location} />;
}

function ProtectedApp({ client, route, location }: { client: AuthClient; route: AppRoute; location: string }) {
  const [attempt, setAttempt] = useState(0);
  return <SessionCheck key={attempt} client={client} route={route} location={location} retry={() => setAttempt((value) => value + 1)} />;
}

function SessionCheck({ client, route, location, retry }: { client: AuthClient; route: AppRoute; location: string; retry: () => void }) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [error, setError] = useState<AuthApiError | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  const present = client.hasSession();
  useEffect(() => {
    if (!present) {
      navigateApp(`/login?reason=${client.getSessionVersion() ? 'expired' : 'required'}&returnTo=${encodeURIComponent(route.href)}`, true);
      return;
    }
    let active = true;
    void client.getProfile().then((user) => { if (active) setProfile(user); }).catch((failure: unknown) => {
      if (active) setError(failure instanceof AuthApiError ? failure : new AuthApiError('인증 상태를 확인하지 못했어요. 다시 시도해 주세요.'));
    });
    return () => { active = false; };
    // 경로 이동은 확인된 사용자 세션을 유지하고, 인증 세대 변경은 부모가 다시 마운트한다.
  }, [client, present]);

  async function logout() {
    if (loggingOut) return;
    setLoggingOut(true);
    let reason = 'logged-out';
    try { await client.logout(); } catch (failure) {
      if (failure instanceof AuthApiError && failure.code === 'SESSION_CHANGED') return;
      reason = 'logout-unconfirmed';
    }
    navigateApp(`/login?reason=${reason}`, true);
  }

  if (!present || loggingOut) return <SessionNotice title={loggingOut ? '로그아웃 중이에요.' : '로그인 화면으로 이동하고 있어요.'} />;
  if (error) return <SessionNotice title={error.status === 403 ? '접근 권한을 확인해 주세요.' : '로그인 상태를 확인하지 못했어요.'}>
    <p role="alert">{error.message}</p>
    <div className="actions"><button className="btn btn-primary" onClick={retry}>다시 확인</button>
      <button className="btn btn-secondary" onClick={() => { void logout(); }}>로그아웃</button></div>
  </SessionNotice>;
  if (!profile) return <SessionNotice title="로그인 상태를 확인하고 있어요." />;
  return <AppShell key={profile.id} profile={profile} route={route} location={location} onLogout={logout}>
    {route.screen === 'home' ? <HomePage client={client} profile={profile} /> : route.screen === 'planner' ? <PlannerPage client={client} taskId={route.taskId} /> : route.screen === 'timetable' ? <TimetablePage client={client} /> : route.screen === 'onboarding' ? <OnboardingPage key={route.href} client={client} route={route} /> : <section className="card card-pad app-pending" aria-labelledby="pending-title">
      <span className="section-kicker">REACT 이식 진행 중</span>
      <h1 id="pending-title">{route.title}</h1>
      <p>{route.screen === 'not-found' ? '주소를 확인하거나 홈으로 돌아가 주세요.' : '이 화면의 기능은 후속 단계에서 연결할 예정이에요. 지금은 기존 플래너에서 이용해 주세요.'}</p>
      <div className="actions"><a className="btn btn-primary" href="/demo.html">기존 플래너 이용하기</a>
        {route.screen === 'not-found' && <a className="btn btn-secondary" href="/#home">홈으로 돌아가기</a>}</div>
    </section>}
  </AppShell>;
}

function SessionNotice({ title, children }: { title: string; children?: ReactNode }) {
  return <main className="app-session-notice"><section className="card card-pad" aria-live="polite">
    <h1>{title}</h1>{children || <p role="status">잠시만 기다려 주세요.</p>}
  </section></main>;
}
