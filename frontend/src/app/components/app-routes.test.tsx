import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';

const profile = { id: 1, name: '첫학생', email: 'first@example.com' };
const second = { id: 2, name: '다른학생', email: 'second@example.com' };
const tokens = { accessToken: 'new-access', refreshToken: 'new-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
function location(path: string) { window.history.replaceState(null, '', path); }
function seed() { localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'fixture-access'); }
function mount(transport = vi.fn<typeof fetch>().mockImplementation(async () => json(profile))) {
  const client = new AuthClient(localStorage, transport);
  return { client, transport, ...render(<AppRoutes client={client} />) };
}

describe('보호된_앱_라우팅', () => {
  beforeEach(() => { localStorage.clear(); location('/#home'); vi.spyOn(window, 'scrollTo').mockImplementation(() => {}); });

  it('인증이_없으면_보호_데이터를_표시하지_않고_안전한_복귀_경로와_함께_로그인으로_보낸다', async () => {
    location('/#planner?taskId=123');
    const { transport } = mount();
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(window.location.pathname).toBe('/login');
    expect(new URLSearchParams(window.location.search).get('returnTo')).toBe('/#planner?taskId=123');
    expect(screen.queryByLabelText('주요 탐색')).not.toBeInTheDocument();
    expect(transport).not.toHaveBeenCalled();
  });

  it('서버_확인_전에는_저장된_이전_사용자와_레이아웃을_표시하지_않는다', async () => {
    seed(); localStorage.setItem(AUTH_STORAGE_KEYS.userName, '이전이름');
    let release!: (response: Response) => void;
    mount(vi.fn<typeof fetch>().mockImplementation(() => new Promise((resolve) => { release = resolve; })));
    expect(screen.getByRole('status')).toHaveTextContent('잠시만');
    expect(screen.queryByText('이전이름')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('주요 탐색')).not.toBeInTheDocument();
    await act(async () => release(json(profile)));
    expect(await screen.findByLabelText('주요 탐색')).toBeVisible();
  });

  it('직접_진입과_새로고침은_현재_URL을_복원하되_미구현_기능을_호출하지_않는다', async () => {
    seed(); location('/#onboarding/2?mode=manage&returnTo=planner');
    const { transport, unmount } = mount();
    expect(await screen.findByRole('heading', { name: '선호 · 연동' })).toBeVisible();
    expect(screen.getByText(/후속 단계에서 연결/)).toBeVisible();
    expect(screen.getByRole('link', { name: '기존 플래너 이용하기' })).toHaveAttribute('href', '/demo.html');
    unmount(); mount(transport);
    expect(await screen.findByRole('heading', { name: '선호 · 연동' })).toBeVisible();
    expect(transport.mock.calls.every(([path]) => path === '/api/auth/me')).toBe(true);
  });

  it('메뉴_이동과_뒤로_가기가_제목_선택_초점에_반영된다', async () => {
    seed(); mount();
    await screen.findByLabelText('주요 탐색');
    const user = userEvent.setup();
    await user.click(screen.getByRole('link', { name: /^내 시간표$/ }));
    expect(await screen.findByRole('heading', { name: '내 시간표' })).toBeVisible();
    expect(document.title).toBe('내 시간표 - Campus Planner');
    expect(screen.getByRole('link', { name: '내 시간표' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('main')).toHaveFocus();
    await act(async () => { window.history.back(); });
    await waitFor(() => expect(screen.getByRole('heading', { name: '내 홈' })).toBeVisible());
  });

  it('잘못된_URL은_가짜_기능_대신_복귀_안내를_표시한다', async () => {
    seed(); location('/unknown#planner'); mount();
    expect(await screen.findByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeVisible();
    expect(screen.getByRole('link', { name: '홈으로 돌아가기' })).toHaveAttribute('href', '/#home');
  });

  it('403은_토큰을_제거하거나_갱신하지_않고_재확인할_수_있다', async () => {
    seed(); const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 403)).mockResolvedValueOnce(json(profile));
    mount(transport);
    expect(await screen.findByRole('heading', { name: '접근 권한을 확인해 주세요.' })).toBeVisible();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBe('fixture-access');
    await userEvent.click(screen.getByRole('button', { name: '다시 확인' }));
    expect(await screen.findByLabelText('주요 탐색')).toBeVisible();
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/me', '/api/auth/me']);
  });

  it('통신_실패는_인증_만료와_구분하고_재시도한다', async () => {
    seed(); mount(vi.fn<typeof fetch>().mockRejectedValueOnce(new Error('private detail')).mockResolvedValueOnce(json(profile)));
    expect(await screen.findByRole('alert')).toHaveTextContent('연결이 원활하지 않아요.');
    expect(window.location.pathname).toBe('/');
    await userEvent.click(screen.getByRole('button', { name: '다시 확인' }));
    expect(await screen.findByLabelText('주요 탐색')).toBeVisible();
  });

  it('401_갱신_성공시_보호_화면을_표시하고_실패하면_재로그인을_안내한다', async () => {
    seed(); localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    const { client, unmount } = mount(vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(json(profile)));
    expect(await screen.findByLabelText('주요 탐색')).toBeVisible();
    expect(client.hasSession()).toBe(true);
    unmount();
    mount(vi.fn<typeof fetch>().mockResolvedValue(json({}, 401)));
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(screen.getByText('다시 로그인해 주세요.')).toBeVisible();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBeNull();
  });

  it('사용자_변경과_늦은_응답은_이전_프로필과_메뉴를_복원하지_않는다', async () => {
    seed(); let release!: (response: Response) => void;
    const transport = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise((resolve) => { release = resolve; })).mockResolvedValue(json(second));
    mount(transport);
    await act(async () => {
      localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'another-user-access');
      window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEYS.accessToken, storageArea: localStorage }));
    });
    expect(await screen.findAllByRole('button', { name: '다른학생 프로필 메뉴' })).toHaveLength(2);
    await act(async () => release(json(profile)));
    expect(screen.queryByText('첫학생')).not.toBeInTheDocument();
    expect(screen.getByText('다른학생')).toBeVisible();
  });

  it('다른_탭_로그아웃은_기존_앱과_열린_메뉴를_제거한다', async () => {
    seed(); mount();
    const buttons = await screen.findAllByRole('button', { name: '첫학생 프로필 메뉴' });
    await userEvent.click(buttons[0]);
    expect(screen.getByRole('dialog', { name: '프로필' })).toBeVisible();
    await act(async () => { localStorage.clear(); window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage })); });
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('첫학생')).not.toBeInTheDocument();
  });

  it('프로필_메뉴는_Escape로_닫히고_원래_버튼으로_초점을_돌린다', async () => {
    seed(); mount(); const user = userEvent.setup();
    const [button] = await screen.findAllByRole('button', { name: '첫학생 프로필 메뉴' });
    await user.click(button);
    expect(screen.getByRole('button', { name: '프로필 메뉴 닫기' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(button).toHaveFocus();
  });

  it('인증_화면의_스타일_범위와_기존_로그인_성공_목적지를_유지한다', async () => {
    location('/login');
    const transport = vi.fn<typeof fetch>().mockImplementation(async (path) => json(path === '/api/auth/login' ? tokens : profile));
    mount(transport);
    expect(document.body).toHaveClass('auth-page');
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox', { name: '이메일' }), profile.email);
    await user.type(screen.getByLabelText('비밀번호', { selector: 'input' }), 'fixture-password');
    await user.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(await screen.findByRole('link', { name: '내 홈으로 이동' })).toHaveAttribute('href', '/demo.html');
    expect(transport.mock.calls.filter(([path]) => path === '/api/auth/login')).toHaveLength(1);
    expect(screen.queryByLabelText('주요 탐색')).not.toBeInTheDocument();
  });

  it('가입_자동로그인은_세션_알림으로_중단되지_않고_기존_플래너를_연결한다', async () => {
    location('/signup');
    const transport = vi.fn<typeof fetch>().mockImplementation(async (path) => json(path === '/api/auth/register' ? tokens : profile));
    mount(transport); const user = userEvent.setup();
    await user.type(screen.getByRole('textbox', { name: '이름' }), profile.name);
    await user.type(screen.getByRole('textbox', { name: '이메일' }), profile.email);
    await user.type(screen.getByLabelText('비밀번호', { selector: 'input' }), 'fixture-password');
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(await screen.findByRole('link', { name: /플래너로 이동/ })).toHaveAttribute('href', '/demo.html');
    expect(transport.mock.calls.filter(([path]) => path === '/api/auth/register')).toHaveLength(1);
  });

  it('확인된_앱의_백그라운드_인증_만료도_이전_화면을_즉시_제거한다', async () => {
    seed();
    const { client } = mount(vi.fn<typeof fetch>().mockResolvedValueOnce(json(profile)).mockResolvedValue(json({}, 401)));
    await screen.findByLabelText('주요 탐색');
    expect(document.body).not.toHaveClass('auth-page');
    await act(async () => { await client.authenticatedRequest('/tasks').catch(() => {}); });
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(screen.queryByLabelText('주요 탐색')).not.toBeInTheDocument();
    expect(document.body).toHaveClass('auth-page');
  });

  it('브라우저_이전_페이지_캐시에서_복귀하면_인증을_다시_확인한다', async () => {
    seed(); localStorage.setItem(AUTH_STORAGE_KEYS.taskId, '42'); const { transport } = mount();
    await screen.findByLabelText('주요 탐색');
    await act(async () => {
      const event = new Event('pageshow');
      Object.defineProperty(event, 'persisted', { value: true });
      window.dispatchEvent(event);
    });
    expect(await screen.findByLabelText('주요 탐색')).toBeVisible();
    expect(transport.mock.calls.filter(([path]) => path === '/api/auth/me')).toHaveLength(2);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBe('42');
  });

  it('로그아웃_응답이_다른_사용자의_로그인을_덮거나_화면을_이동하지_않는다', async () => {
    seed(); let release!: (response: Response) => void;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (path, options) => {
      if (path === '/api/auth/logout') return new Promise((resolve) => { release = resolve; });
      return json(new Headers(options?.headers).get('Authorization') === 'Bearer another-user-access' ? second : profile);
    });
    mount(transport);
    await userEvent.click((await screen.findAllByRole('button', { name: '첫학생 프로필 메뉴' }))[0]);
    await userEvent.click(screen.getByRole('button', { name: /^로그아웃$/ }));
    expect(screen.queryByLabelText('주요 탐색')).not.toBeInTheDocument();
    await act(async () => {
      localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'another-user-access');
      window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEYS.accessToken, storageArea: localStorage }));
    });
    expect(await screen.findAllByRole('button', { name: '다른학생 프로필 메뉴' })).toHaveLength(2);
    await act(async () => release(new Response(null, { status: 204 })));
    expect(window.location.pathname).toBe('/');
    expect(screen.getByText('다른학생')).toBeVisible();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBe('another-user-access');
  });

  it.each([true, false])('서버_로그아웃_성공여부_%s와_무관하게_이전_사용자_화면을_정리한다', async (success) => {
    seed(); localStorage.setItem(AUTH_STORAGE_KEYS.taskId, '42');
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(profile));
    if (success) transport.mockResolvedValue(new Response(null, { status: 204 }));
    else transport.mockRejectedValue(new Error('private detail'));
    mount(transport);
    await userEvent.click((await screen.findAllByRole('button', { name: '첫학생 프로필 메뉴' }))[0]);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '로그아웃' }));
    expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
    expect(screen.queryByText('첫학생')).not.toBeInTheDocument();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBeNull();
    expect(transport.mock.calls[1][0]).toBe('/api/auth/logout');
    expect(transport.mock.calls[1][1]?.method).toBe('POST');
    if (!success) expect(screen.getByText('이 기기에서 로그아웃했어요.')).toBeVisible();
  });
});
