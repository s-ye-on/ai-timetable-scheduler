import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { navigateApp } from '@/app/app-routing';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { createSettingsFixture, fixtureJson } from '@/test/settings-api-fixture';

const tokens = { accessToken: 'entry-access', refreshToken: 'entry-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const aboutTitle = /^복잡한 일정 고민,\s*Campus Planner\s*와 함께$/;

describe('기본_주소_최초_진입', () => {
  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState(null, '', '/');
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  });

  it('비로그인_기본_주소는_소개로_이동하고_새로고침해도_인증을_요구하지_않는다', async () => {
    const transport = vi.fn<typeof fetch>();
    const client = new AuthClient(localStorage, transport);
    const { unmount } = render(<AppRoutes client={client} />);
    expect(await screen.findByRole('heading', { name: aboutTitle })).toBeVisible();
    expect(window.location.pathname).toBe('/about');
    expect(window.location.hash).toBe('');
    expect(screen.getByRole('link', { name: '시작하기' })).toHaveAttribute('href', '/login');
    unmount(); render(<AppRoutes client={client} />);
    expect(await screen.findByRole('heading', { name: aboutTitle })).toBeVisible();
    expect(transport).not.toHaveBeenCalled();
  });

  it('로그인_상태의_기본_주소는_서버_인증을_확인한_홈으로_연결한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, tokens.accessToken);
    const fixture = createSettingsFixture();
    render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />);
    expect(await screen.findByRole('heading', { name: '학생님, 오늘도 나답게.' })).toBeVisible();
    expect(window.location.hash).toBe('#home');
    expect(fixture.transport.mock.calls.some(([path]) => path === '/api/auth/me')).toBe(true);
    expect(screen.queryByRole('heading', { name: aboutTitle })).not.toBeInTheDocument();
  });

  it('갱신_토큰만_남은_경우에도_기존_갱신_정책을_거쳐_홈으로_연결한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, tokens.refreshToken);
    const fixture = createSettingsFixture();
    const original = fixture.transport.getMockImplementation()!;
    fixture.transport.mockImplementation((path, options) => path === '/api/auth/refresh' ? Promise.resolve(fixtureJson(tokens)) : original(path, options));
    render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />);
    expect(await screen.findByRole('heading', { name: '학생님, 오늘도 나답게.' })).toBeVisible();
    expect(window.location.hash).toBe('#home');
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(1);
  });

  it('유효하지_않은_저장_토큰은_홈을_노출하지_않고_기존_재로그인_안내로_보낸다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired-entry-access');
    render(<AppRoutes client={new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(fixtureJson({}, 401)))} />);
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(window.location.pathname).toBe('/login');
    expect(new URLSearchParams(window.location.search).get('reason')).toBe('expired');
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBeNull();
    expect(screen.queryByRole('heading', { name: '학생님, 오늘도 나답게.' })).not.toBeInTheDocument();
  });

  it.each(['/#home', '/#timetable', '/#planner?taskId=42', '/#onboarding/3'])(
    '특정_화면_링크_%s는_소개로_보내지_않고_기존_로그인_복귀_경로를_유지한다', async (path) => {
      window.history.replaceState(null, '', path);
      render(<AppRoutes client={new AuthClient(localStorage, vi.fn<typeof fetch>())} />);
      expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
      expect(window.location.pathname).toBe('/login');
      expect(new URLSearchParams(window.location.search).get('returnTo')).toBe(path);
    },
  );

  it('쿼리가_있는_기존_루트_요청은_일반_첫_진입으로_가로채지_않는다', async () => {
    window.history.replaceState(null, '', '/?returnTo=planner');
    render(<AppRoutes client={new AuthClient(localStorage, vi.fn<typeof fetch>())} />);
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(window.location.pathname).toBe('/login');
    expect(new URLSearchParams(window.location.search).get('returnTo')).toBe('/#home');
  });

  it.each([['/about', aboutTitle], ['/privacy', '개인정보처리방침']] as const)(
    '로그인_상태에서도_%s는_홈으로_강제_이동하지_않는다', async (path, title) => {
      localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, tokens.accessToken);
      window.history.replaceState(null, '', path);
      const transport = vi.fn<typeof fetch>();
      render(<AppRoutes client={new AuthClient(localStorage, transport)} />);
      expect(await screen.findByRole('heading', { level: 1, name: title })).toBeVisible();
      expect(window.location.pathname).toBe(path);
      expect(transport).not.toHaveBeenCalled();
    },
  );

  it('소개에서_로그인하면_홈으로_이동하고_로그아웃_후_기본_주소는_다시_소개를_표시한다', async () => {
    const fixture = createSettingsFixture();
    const original = fixture.transport.getMockImplementation()!;
    fixture.transport.mockImplementation((path, options) => {
      if (path === '/api/auth/login') return Promise.resolve(fixtureJson(tokens));
      if (path === '/api/auth/logout') return Promise.resolve(new Response(null, { status: 204 }));
      return original(path, options);
    });
    render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />);
    await screen.findByRole('heading', { name: aboutTitle });
    act(() => navigateApp('/login'));
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox', { name: '이메일' }), 'fixture@example.com');
    await user.type(screen.getByLabelText('비밀번호', { selector: 'input' }), 'fixture-password');
    await user.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(await screen.findByRole('link', { name: '내 홈으로 이동' })).toHaveAttribute('href', '/#home');
    await waitFor(() => expect(window.location.hash).toBe('#home'), { timeout: 3000 });
    expect(await screen.findByRole('heading', { name: '학생님, 오늘도 나답게.' })).toBeVisible();
    await user.click((await screen.findAllByRole('button', { name: '학생 프로필 메뉴' }))[0]);
    await user.click(screen.getByRole('button', { name: /^로그아웃$/ }));
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(window.location.pathname + window.location.search).toBe('/login?reason=logged-out');
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBeNull();
    act(() => navigateApp('/'));
    expect(await screen.findByRole('heading', { name: aboutTitle })).toBeVisible();
    expect(window.location.pathname).toBe('/about');
    expect(screen.queryByText('학생님, 오늘도 나답게.')).not.toBeInTheDocument();
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/auth/logout')).toHaveLength(1);
  });
});
