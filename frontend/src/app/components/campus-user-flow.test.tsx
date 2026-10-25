import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { navigateApp } from '@/app/app-routing';
import { AuthClient } from '@/features/auth/api/auth-client';
import { createTaskFixture, fixtureTask, fixtureRecommendation } from '@/test/task-api-fixture';
import { fixtureJson } from '@/test/settings-api-fixture';

beforeEach(() => {
  localStorage.clear(); window.history.replaceState(null, '', '/signup');
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});

it('신규_가입부터_선택형_온보딩_미연동_추천_최종등록_홈_복원_로그아웃까지_이어진다', async () => {
  const f = createTaskFixture(); const original = f.transport.getMockImplementation()!;
  const tokens = { accessToken: 'integration-access', refreshToken: 'integration-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
  f.transport.mockImplementation((path, options) => {
    if (path === '/api/auth/register') return Promise.resolve(fixtureJson(tokens, 201));
    if (path === '/api/auth/logout') return Promise.resolve(new Response(null, { status: 204 }));
    if (path === '/tasks/natural-language') {
      f.setTasks([fixtureTask(1)]); return Promise.resolve(fixtureJson(fixtureTask(1), 201));
    }
    if (path === '/tasks/1/recommendations' && options?.method === 'POST') {
      f.histories[1] = [fixtureRecommendation(1)];
      return Promise.resolve(fixtureJson({ message: '확인된 조건으로 추천했어요.', recommendations: f.histories[1] }));
    }
    if (path === '/recommendations/10/select') {
      f.setTasks([fixtureTask(1, 'SCHEDULED')]); f.histories[1] = [fixtureRecommendation(1, 'SYNCED')];
      return Promise.resolve(fixtureJson(f.histories[1][0]));
    }
    return original(path, options);
  });
  const app = render(<AppRoutes client={new AuthClient(localStorage, f.transport)} />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('이름'), '학생');
  await user.type(screen.getByLabelText('이메일'), 'student@example.com');
  await user.type(screen.getByLabelText('비밀번호', { selector: 'input' }), 'fixture-password');
  await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
  await waitFor(() => expect(window.location.hash).toBe('#onboarding/1'), { timeout: 3000 });
  expect(await screen.findByText('등록된 수업 0개')).toBeVisible();
  await user.click(screen.getByRole('link', { name: '나중에 할게요' }));
  await user.click(await screen.findByRole('link', { name: '저장하지 않고 건너뛰기' }));
  await user.click(await screen.findByRole('link', { name: '나중에 연결하고 시작하기' }));
  expect(await screen.findByText('아직 확정된 일정이 없어요')).toBeVisible();
  expect(f.transport.mock.calls.some(([path]) => path === '/api/calendar/oauth/url')).toBe(false);
  await user.type(screen.getByLabelText('어떤 일정을 계획하고 있나요?'), '공부 1시간');
  await user.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  await screen.findByRole('region', { name: '할 일 1' });
  expect(f.transport.mock.calls.some(([path]) => path === '/tasks/1/recommendations')).toBe(false);
  await user.click(screen.getByRole('button', { name: '추천 시간 찾기' }));
  await user.click(await screen.findByRole('button', { name: '이 시간 선택' }));
  expect(screen.getByRole('button', { name: 'Google Calendar에 등록' })).toBeDisabled();
  expect(f.transport.mock.calls.some(([path]) => String(path).endsWith('/select'))).toBe(false);
  f.setConnected(true); await user.click(screen.getByRole('button', { name: '작업 상태 새로고침' }));
  await user.click(await screen.findByRole('button', { name: '이 시간 선택' }));
  await user.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  const success = await screen.findByRole('region', { name: '일정 등록 완료' });
  await user.click(within(success).getByRole('link', { name: '홈으로 돌아가기' }));
  await user.click(await screen.findByRole('link', { name: /할 일 1/ }));
  await screen.findByRole('region', { name: '일정 등록 완료' });
  const writes = () => f.transport.mock.calls.filter(([, options]) => options?.method === 'POST');
  expect(writes().map(([path]) => path)).toEqual(['/api/auth/register', '/tasks/natural-language', '/tasks/1/recommendations', '/recommendations/10/select']);
  app.unmount(); render(<AppRoutes client={new AuthClient(localStorage, f.transport)} />);
  await screen.findByRole('region', { name: '일정 등록 완료' });
  expect(writes()).toHaveLength(4);
  await user.click((await screen.findAllByRole('button', { name: '학생 프로필 메뉴' }))[0]);
  await user.click(screen.getByRole('button', { name: /^로그아웃$/ }));
  await screen.findByRole('heading', { name: '다시 만나 반가워요.' });
  expect(screen.queryByRole('region', { name: '일정 등록 완료' })).not.toBeInTheDocument();
});

it('기존_사용자가_회원가입_주소를_열어도_온보딩_대신_홈으로_이동한다', async () => {
  const f = createTaskFixture(); const client = new AuthClient(localStorage, f.transport);
  localStorage.setItem('demoAccessToken', 'fixture-access');
  render(<AppRoutes client={client} />);
  expect(await screen.findByRole('link', { name: '플래너로 이동' })).toHaveAttribute('href', '/#home');
  await waitFor(() => expect(window.location.hash).toBe('#home'), { timeout: 3000 });
  await screen.findByRole('heading', { name: '학생님, 오늘도 나답게.' });
  act(() => navigateApp('/#onboarding/2?mode=manage&returnTo=home'));
  await userEvent.click(await screen.findByRole('button', { name: /선호 저장하고 돌아가기/ }));
  await waitFor(() => expect(window.location.hash).toBe('#home'));
  expect(f.transport.mock.calls.some(([path]) => path === '/api/auth/register')).toBe(false);
});
