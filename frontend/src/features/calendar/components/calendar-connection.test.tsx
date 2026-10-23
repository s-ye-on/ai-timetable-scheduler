import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CalendarConnection } from '@/features/calendar/components/calendar-connection';
import { CalendarApi } from '@/features/calendar/api/calendar.api';
import { AuthApiError, AuthClient } from '@/features/auth/api/auth-client';
import { createSettingsFixture, fixtureJson } from '@/test/settings-api-fixture';

afterEach(() => { vi.restoreAllMocks(); });
function childWindow() {
  let closed = false;
  const child = { get closed() { return closed; }, close: vi.fn(() => { closed = true; }), opener: {} as unknown,
    location: { replace: vi.fn() } };
  const open = vi.spyOn(window, 'open').mockReturnValue(child as unknown as Window);
  return { child, open };
}
function mount() {
  const fixture = createSettingsFixture();
  const api = new CalendarApi(new AuthClient(localStorage, fixture.transport));
  return { fixture, api, ...render(<CalendarConnection api={api} />) };
}

describe('Calendar_연결_사용자_흐름', () => {
  it('미연결도_추천은_가능하고_확정에는_연결이_필요함을_표시한다', async () => {
    const { fixture } = mount();
    expect(await screen.findByText('아직 연결하지 않았어요')).toBeVisible();
    expect(screen.getByText(/Google 일정은 추천에 반영되지 않으며/)).toBeVisible();
    expect(screen.getByText('최종 일정 등록에는 연결이 필요해요')).toBeVisible();
    expect(screen.getByRole('link', { name: '나중에 연결하고 시작하기' })).toHaveAttribute('href', '/#home');
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/calendar/oauth/url')).toHaveLength(0);
  });
  it('팝업_차단시_OAuth_state를_발급하지_않고_재시도_방법을_안내한다', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null); const { fixture } = mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('팝업을 허용');
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/calendar/oauth/url')).toHaveLength(0);
  });
  it('실제_서버가_반환한_Google_URL로_이동하되_동의만으로_성공을_표시하지_않는다', async () => {
    const { child, open } = childWindow(); const { fixture, unmount } = mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    await waitFor(() => expect(child.location.replace).toHaveBeenCalledTimes(1));
    expect(child.opener).toBeNull(); expect(open).toHaveBeenCalledTimes(1);
    expect(screen.getByText('아직 연결하지 않았어요')).toBeVisible();
    expect(screen.queryByText('Google Calendar 연결됨')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '인증 확인 대기 중' }));
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/calendar/oauth/url')).toHaveLength(1);
    fixture.setConnected(true);
    await act(async () => window.dispatchEvent(new Event('focus')));
    expect(await screen.findByText('Google Calendar 연결됨')).toBeVisible();
    expect(screen.getByText(/연결 전 생성한 후보는 검증 완료 상태가 아니에요/)).toBeVisible();
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/calendar/status')).toHaveLength(2);
    unmount(); expect(child.close).toHaveBeenCalled();
  });
  it('인증_취소나_Callback_실패후_status_false면_완료로_단정하지_않고_재시작할_수_있다', async () => {
    const { child } = childWindow(); const { fixture } = mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    await waitFor(() => expect(child.location.replace).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: '인증 창 닫고 상태 확인' }));
    expect(await screen.findByText(/연결이 아직 확인되지 않았어요/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Google Calendar 연결하기' })).toBeEnabled();
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/calendar/status')).toHaveLength(2);
    expect(screen.queryByText('Google Calendar 연결됨')).not.toBeInTheDocument();
  });
  it('인증창_종료를_감지하면_서버를_다시_조회한다', async () => {
    const { child } = childWindow(); const { fixture } = mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    await waitFor(() => expect(child.location.replace).toHaveBeenCalled());
    fixture.setConnected(true); child.close();
    expect(await screen.findByText('Google Calendar 연결됨', {}, { timeout: 2000 })).toBeVisible();
    expect(fixture.transport.mock.calls.filter(([path]) => path === '/api/calendar/status')).toHaveLength(2);
  });
  it('조회_실패는_미연결로_위장하지_않으며_재조회로_연결_상태를_복원한다', async () => {
    const client = { authenticatedRequest: vi.fn().mockRejectedValueOnce(new AuthApiError('private', 503)).mockResolvedValue({ connected: true }) };
    render(<CalendarConnection api={new CalendarApi(client)} />);
    expect(await screen.findByText('연결 상태 확인 필요')).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent('미연결 상태로 단정할 수 없으니');
    expect(screen.queryByText('아직 연결하지 않았어요')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Google Calendar 연결하기' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '상태 확인' }));
    expect(await screen.findByText('Google Calendar 연결됨')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('OAuth_호스트_불일치는_인증창을_닫고_원문_state를_노출하지_않는다', async () => {
    const { child } = childWindow();
    const client = { authenticatedRequest: vi.fn().mockImplementation(async (path) => path.endsWith('/status') ? { connected: false }
      : { authUrl: 'https://accounts.google.com/o/oauth2/auth?redirect_uri=http%3A%2F%2Fother.example%3A8080%2Fapi%2Fcalendar%2Foauth%2Fcallback&state=fixture-private-state' }) };
    render(<CalendarConnection api={new CalendarApi(client)} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('호스트로 접속');
    expect(screen.getByRole('alert')).not.toHaveTextContent('fixture-private-state');
    expect(child.location.replace).not.toHaveBeenCalled(); expect(child.close).toHaveBeenCalled();
  });
  it('서버_OAuth_설정_실패는_안전한_안내를_표시하고_새_상태를_성공으로_바꾸지_않는다', async () => {
    const { child } = childWindow(); const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
    fixture.transport.mockImplementation((path, options) => path === '/api/calendar/oauth/url'
      ? Promise.resolve(fixtureJson({ code: 'GOOGLE_OAUTH_CONFIGURATION_INVALID', message: 'private-secret' }, 503)) : original(path, options));
    render(<CalendarConnection api={new CalendarApi(new AuthClient(localStorage, fixture.transport))} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('OAuth 설정 확인');
    expect(screen.getByRole('alert')).not.toHaveTextContent('private-secret');
    expect(child.close).toHaveBeenCalled(); expect(screen.getByText('아직 연결하지 않았어요')).toBeVisible();
  });
  it('기존_연결이_있어도_재연결_완료나_FreeBusy_정상으로_주장하지_않는다', async () => {
    const fixture = createSettingsFixture(); fixture.setConnected(true); const { child } = childWindow();
    render(<CalendarConnection api={new CalendarApi(new AuthClient(localStorage, fixture.transport))} />);
    expect(await screen.findByText('Google Calendar 연결됨')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '다시 연결하기' }));
    await waitFor(() => expect(child.location.replace).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: '인증 창 닫고 상태 확인' }));
    expect(await screen.findByText('Google Calendar 연결됨')).toBeVisible();
    expect(screen.queryByText(/재연결.*완료/)).not.toBeInTheDocument();
    expect(fixture.transport.mock.calls.some(([path]) => String(path).includes('freeBusy'))).toBe(false);
  });
  it('해제된_화면의_늦은_OAuth_URL로_인증_이동하지_않는다', async () => {
    const { child } = childWindow();
    let release!: (value: unknown) => void;
    const client = { authenticatedRequest: vi.fn().mockImplementation(async (path) => path.endsWith('/status') ? { connected: false } : new Promise((resolve) => { release = resolve; })) };
    const { unmount } = render(<CalendarConnection api={new CalendarApi(client)} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Google Calendar 연결하기' }));
    unmount();
    await act(async () => release({ authUrl: 'https://accounts.google.com/o/oauth2/auth?redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fcalendar%2Foauth%2Fcallback&state=fixture' }));
    expect(child.close).toHaveBeenCalled(); expect(child.location.replace).not.toHaveBeenCalled();
  });
});
