export interface AppRoute {
  screen: 'home' | 'planner' | 'timetable' | 'onboarding' | 'not-found';
  title: string;
  href: string;
  step?: number;
  taskId?: number;
  mode?: 'manage';
  returnTo?: 'home' | 'planner' | 'timetable';
}

/** 앱 안의 알려진 경로와 매개변수만 복원한다. 외부 복귀 URL은 사용하지 않는다. */
export function resolveAppRoute(hash: string): AppRoute {
  const [path, query = ''] = hash.replace(/^#/, '').split('?');
  const params = new URLSearchParams(query);
  const screen = path || 'home';
  if (screen === 'home' || screen === 'planner' || screen === 'timetable') {
    const value = params.get('taskId');
    const taskId = screen === 'planner' && value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : undefined;
    return { screen, title: { home: '내 홈', planner: '일정 추천', timetable: '내 시간표' }[screen],
      href: `/#${screen}${taskId ? `?taskId=${taskId}` : ''}`, taskId };
  }
  if (/^onboarding\/[123]$/.test(screen)) {
    const step = Number(screen.at(-1));
    const mode = step === 2 && params.get('mode') === 'manage' ? 'manage' : undefined;
    const destination = params.get('returnTo');
    const returnTo = mode && (destination === 'home' || destination === 'planner' || destination === 'timetable') ? destination : mode ? 'home' : undefined;
    return { screen: 'onboarding', title: mode ? '선호 · 연동' : '처음 설정', step, mode, returnTo,
      href: `/#onboarding/${step}${mode ? `?mode=manage&returnTo=${returnTo}` : ''}` };
  }
  return { screen: 'not-found', title: '페이지를 찾을 수 없어요', href: '/#home' };
}

export function navigateApp(path: string, replace = false): void {
  if (replace) window.history.replaceState(null, '', path);
  else window.history.pushState(null, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

export function subscribeLocation(listener: () => void): () => void {
  window.addEventListener('hashchange', listener);
  window.addEventListener('popstate', listener);
  return () => {
    window.removeEventListener('hashchange', listener);
    window.removeEventListener('popstate', listener);
  };
}

export const getLocation = () => window.location.pathname + window.location.search + window.location.hash;
