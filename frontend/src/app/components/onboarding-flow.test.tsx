import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { createSettingsFixture, DEFAULT_PREFERENCE, fixtureJson } from '@/test/settings-api-fixture';
import { navigateApp } from '@/app/app-routing';

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'fixture-access');
  window.history.replaceState(null, '', '/#onboarding/1');
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => { vi.restoreAllMocks(); });
function mount(path: string, fixture = createSettingsFixture()) {
  window.history.replaceState(null, '', path);
  return { ...fixture, ...render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />) };
}

it('온보딩_직접_접근에서_실제_수업_관리와_선택_단계를_표시한다', async () => {
  const transport = vi.fn<typeof fetch>().mockImplementation(async (path) => new Response(JSON.stringify(
    path === '/api/auth/me' ? { id: 1, name: '학생', email: 'fixture@example.com' } : [],
  )));
  render(<AppRoutes client={new AuthClient(localStorage, transport)} />);
  expect(await screen.findByRole('heading', { name: '나만의 한 주를 만들어 볼까요?' })).toBeVisible();
  expect(await screen.findByText('등록된 수업 0개')).toBeVisible();
  expect(screen.getByRole('link', { name: '나중에 할게요' })).toHaveAttribute('href', '/#onboarding/2');
});

it('세_선택_단계를_모두_건너뛰면_Calendar_미연결과_기본_선호로_홈에_도착한다', async () => {
  const fixture = mount('/#onboarding/1'); const user = userEvent.setup();
  await user.click(await screen.findByRole('link', { name: '나중에 할게요' }));
  expect(await screen.findByLabelText('일정 사이 최소 여유 시간')).toHaveValue(10);
  await user.click(screen.getByRole('link', { name: '저장하지 않고 건너뛰기' }));
  expect(await screen.findByText('아직 연결하지 않았어요')).toBeVisible();
  await user.click(screen.getByRole('link', { name: '나중에 연결하고 시작하기' }));
  expect(await screen.findByRole('heading', { name: '내 홈' })).toBeVisible();
  expect(screen.getByText(/후속 단계에서 연결/)).toBeVisible();
  expect(fixture.getPreference()).toEqual(DEFAULT_PREFERENCE);
  expect(fixture.transport.mock.calls.some(([path, options]) => path === '/api/calendar/oauth/url' || path === '/recommendations' || options?.method === 'PUT')).toBe(false);
});

it('선호_저장과_이전_이동은_서버_값을_재조회하고_기본값_복원은_즉시_반영한다', async () => {
  const fixture = mount('/#onboarding/2'); const user = userEvent.setup();
  const gap = await screen.findByLabelText('일정 사이 최소 여유 시간');
  await user.clear(gap); await user.type(gap, '125');
  await user.click(screen.getByRole('radio', { name: /^저녁/ }));
  await user.click(screen.getByRole('radio', { name: '가능한 빨리' }));
  await user.click(screen.getByRole('button', { name: /선호 저장하고 다음/ }));
  expect(await screen.findByRole('heading', { name: '준비는 가볍게, 하루는 편하게.' })).toBeVisible();
  expect(fixture.getPreference()).toMatchObject({ minimumGapMinutes: 125, preferredTimeRange: 'EVENING', deadlineTiming: 'ASAP', customized: true });
  await user.click(screen.getByRole('link', { name: '이전' }));
  expect(await screen.findByLabelText('일정 사이 최소 여유 시간')).toHaveValue(125);
  await user.click(screen.getByRole('button', { name: '기본값으로 초기화' }));
  expect(await screen.findByText('기본값으로 복원했어요.')).toBeVisible();
  expect(screen.getByLabelText('일정 사이 최소 여유 시간')).toHaveValue(10);
  expect(fixture.getPreference()).toEqual(DEFAULT_PREFERENCE);
  expect(window.location.hash).toBe('#onboarding/2');
});

it.each(['planner', 'timetable'] as const)('일반_설정_저장시_%s로_복귀하고_Calendar_온보딩으로_진행하지_않는다', async (destination) => {
  const fixture = mount(`/#onboarding/2?mode=manage&returnTo=${destination}`);
  await screen.findByLabelText('일정 사이 최소 여유 시간');
  expect(screen.queryByLabelText(/초기 설정/)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /선호 저장하고 돌아가기/ }));
  await waitFor(() => expect(window.location.hash).toBe(`#${destination}`));
  expect(fixture.getPreference().customized).toBe(true);
});

it('시간표에서_연_일반_설정은_현재_화면을_복귀_경로로_사용한다', async () => {
  mount('/#timetable'); await screen.findByRole('heading', { name: '내 시간표 관리' });
  expect(screen.getByRole('link', { name: '선호 · 연동' })).toHaveAttribute('href', '/#onboarding/2?mode=manage&returnTo=timetable');
});

it('5분이_아닌_입력과_역전된_시간은_저장하지_않고_수정할_수_있다', async () => {
  const fixture = mount('/#onboarding/2'); const user = userEvent.setup();
  const gap = await screen.findByLabelText('일정 사이 최소 여유 시간');
  await user.clear(gap); await user.type(gap, '7');
  await user.click(screen.getByRole('button', { name: /선호 저장하고 다음/ }));
  expect(screen.getByText('최소 여유 시간은 0 이상, 5분 단위로 입력해 주세요.')).toBeVisible();
  expect(gap).toHaveFocus();
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(0);
  await user.clear(gap); await user.type(gap, '15');
  await user.clear(screen.getByLabelText('가용 종료 시간')); await user.type(screen.getByLabelText('가용 종료 시간'), '08:00');
  await user.click(screen.getByRole('button', { name: /선호 저장하고 다음/ }));
  expect(screen.getByText('종료 시간을 시작 시간보다 늦게 설정해 주세요.')).toBeVisible();
  expect(fixture.getPreference()).toEqual(DEFAULT_PREFERENCE);
});

it('조회_실패는_기본값이_아니며_재시도로_복원하고_선택_단계는_건너뛸_수_있다', async () => {
  const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
  let fail = true;
  fixture.transport.mockImplementation((path, options) => path === '/preferences' && fail ? Promise.resolve(fixtureJson({}, 503)) : original(path, options));
  mount('/#onboarding/2', fixture);
  expect(await screen.findByRole('alert')).toHaveTextContent('선호 설정을 불러오지 못했어요');
  expect(screen.queryByLabelText('일정 사이 최소 여유 시간')).not.toBeInTheDocument();
  fail = false; await userEvent.click(screen.getByRole('button', { name: '저장된 선호 다시 불러오기' }));
  expect(await screen.findByLabelText('일정 사이 최소 여유 시간')).toHaveValue(10);
});

it('저장_응답_유실은_다음으로_이동하지_않고_재조회_전_중복_저장을_막는다', async () => {
  const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation(async (path, options) => {
    const result = await original(path, options);
    if (path === '/preferences' && options?.method === 'PUT') throw new Error('private connection detail');
    return result;
  });
  mount('/#onboarding/2', fixture); const user = userEvent.setup();
  const gap = await screen.findByLabelText('일정 사이 최소 여유 시간'); await user.clear(gap); await user.type(gap, '25');
  await user.click(screen.getByRole('button', { name: /선호 저장하고 다음/ }));
  expect(await screen.findByText(/저장 결과를 확인하지 못했어요/)).toBeVisible();
  expect(screen.getByRole('button', { name: /선호 저장하고 다음/ })).toBeDisabled();
  expect(window.location.hash).toBe('#onboarding/2');
  await user.click(screen.getByRole('button', { name: '저장된 선호 다시 불러오기' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /선호 저장하고 다음/ })).toBeEnabled());
  expect(screen.getByLabelText('일정 사이 최소 여유 시간')).toHaveValue(25);
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(1);
});

it('저장중_중복_제출과_단계_건너뛰기를_차단한다', async () => {
  const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => options?.method === 'PUT' ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  mount('/#onboarding/2', fixture); await screen.findByLabelText('일정 사이 최소 여유 시간');
  await userEvent.dblClick(screen.getByRole('button', { name: /선호 저장하고 다음/ }));
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(1);
  const skip = screen.getByRole('link', { name: '저장하지 않고 건너뛰기' }); expect(skip).toHaveAttribute('aria-disabled', 'true');
  await userEvent.click(skip); expect(window.location.hash).toBe('#onboarding/2');
  await act(async () => release(fixtureJson({ ...DEFAULT_PREFERENCE, customized: true })));
  expect(await screen.findByRole('heading', { name: '준비는 가볍게, 하루는 편하게.' })).toBeVisible();
});

it('이전_사용자의_늦은_선호_응답은_로그아웃_후_화면에_남지_않는다', async () => {
  const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => path === '/preferences' ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  mount('/#onboarding/2', fixture);
  await screen.findByText('선호 설정을 불러오고 있어요.');
  await act(async () => { navigateApp('/#onboarding/2?mode=manage&returnTo=home'); });
  // 실제 인증 세대 변경은 M1과 동일한 storage 경로를 사용한다.
  await act(async () => { localStorage.clear(); window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage })); });
  expect(await screen.findByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  if (release) await act(async () => release(fixtureJson(DEFAULT_PREFERENCE)));
  expect(screen.queryByLabelText('일정 사이 최소 여유 시간')).not.toBeInTheDocument();
});

it('기존_선호가_있는_사용자의_건너뛰기는_저장_설정을_초기화하지_않는다', async () => {
  const fixture = createSettingsFixture(); fixture.setPreference({ ...DEFAULT_PREFERENCE, minimumGapMinutes: 30, customized: true });
  mount('/#onboarding/2', fixture);
  expect(await screen.findByLabelText('일정 사이 최소 여유 시간')).toHaveValue(30);
  await userEvent.click(screen.getByRole('link', { name: '저장하지 않고 건너뛰기' }));
  expect(await screen.findByText('아직 연결하지 않았어요')).toBeVisible();
  expect(fixture.getPreference().minimumGapMinutes).toBe(30);
  expect(fixture.transport.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false);
});

it('복원_실패는_기존_입력을_유지하고_기본값_복원_성공으로_표시하지_않는다', async () => {
  const fixture = createSettingsFixture(); fixture.setPreference({ ...DEFAULT_PREFERENCE, minimumGapMinutes: 30, customized: true });
  const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => options?.method === 'DELETE' ? Promise.resolve(fixtureJson({}, 403)) : original(path, options));
  mount('/#onboarding/2', fixture); await screen.findByLabelText('일정 사이 최소 여유 시간');
  await userEvent.click(screen.getByRole('button', { name: '기본값으로 초기화' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('접근할 권한');
  expect(screen.getByLabelText('일정 사이 최소 여유 시간')).toHaveValue(30);
  expect(fixture.getPreference().minimumGapMinutes).toBe(30);
  expect(screen.queryByText('기본값으로 복원했어요.')).not.toBeInTheDocument();
});

it('온보딩_수업_저장중에는_다음_단계_이동을_막고_서버_저장후_이동한다', async () => {
  const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => path === '/timetable-slots' && options?.method === 'POST'
    ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  mount('/#onboarding/1', fixture); const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/수업명/), '알고리즘');
  const form = screen.getByRole('heading', { name: '새 수업 추가하기' }).closest('section')!;
  await user.click(form.querySelector('button[type=submit]')!);
  await waitFor(() => expect(screen.getByRole('link', { name: '다음 단계' })).toHaveAttribute('aria-disabled', 'true'));
  await user.click(screen.getByRole('link', { name: '다음 단계' })); expect(window.location.hash).toBe('#onboarding/1');
  await act(async () => release(fixtureJson({ id: 1, subjectName: '알고리즘', dayOfWeek: 'MONDAY', startTime: '09:00:00', endTime: '10:30:00', location: '', color: 'BLUE' })));
  expect(await screen.findByText('수업을 추가했어요.')).toBeVisible();
  await user.click(screen.getByRole('link', { name: '다음 단계' }));
  expect(await screen.findByLabelText('일정 사이 최소 여유 시간')).toHaveValue(10);
});
