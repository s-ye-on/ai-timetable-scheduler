import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { navigateApp } from '@/app/app-routing';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { createTaskFixture, fixtureTask, fixtureRecommendation } from '@/test/task-api-fixture';
import { fixtureJson } from '@/test/settings-api-fixture';
import type { RecommendationRead } from '@/features/tasks/task.types';

beforeEach(() => {
  localStorage.clear(); localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'fixture-access');
  window.history.replaceState(null, '', '/#planner?taskId=1');
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
function fixture(status: RecommendationRead['status'] = 'PROPOSED', retryable = false) {
  const f = createTaskFixture([fixtureTask(1, status === 'PROPOSED' ? 'UNSCHEDULED' : 'SCHEDULED'), fixtureTask(2)],
    { 1: [{ ...fixtureRecommendation(1, status), syncRetryable: retryable }], 2: [fixtureRecommendation(2)] });
  f.setConnected(true); return f;
}
function mount(f = fixture()) { return { ...f, ...render(<AppRoutes client={new AuthClient(localStorage, f.transport)} />) }; }
function posts(f: ReturnType<typeof fixture>) { return f.transport.mock.calls.filter(([path, options]) => String(path).endsWith('/select') && options?.method === 'POST'); }
async function choose() { await userEvent.click(await screen.findByRole('button', { name: '이 시간 선택' })); }
function complete(f: ReturnType<typeof fixture>, status: RecommendationRead['status'] = 'SYNCED') {
  f.setTasks([fixtureTask(1, 'SCHEDULED'), fixtureTask(2)]);
  f.histories[1] = [{ ...fixtureRecommendation(1, status), syncRetryable: status !== 'SYNCED' }];
  return fixtureJson(f.histories[1][0]);
}

it('저장한_Task의_후보_선택은_임시이며_최종_등록에서만_POST하고_SYNCED를_확인한다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') ? Promise.resolve(complete(f)) : original(path, options));
  mount(f); await choose();
  expect(screen.getByRole('button', { name: '선택한 시간' })).toHaveAttribute('aria-pressed', 'true');
  expect(posts(f)).toHaveLength(0);
  await userEvent.dblClick(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  expect(await screen.findByRole('region', { name: '일정 등록 완료' })).toHaveTextContent('일정이 등록되었어요!');
  expect(posts(f)).toHaveLength(1); expect(posts(f)[0][0]).toBe('/recommendations/10/select');
  expect(screen.queryByRole('button', { name: 'Google Calendar에 등록' })).not.toBeInTheDocument();
});

it('새_추천을_생성한_후에도_임시_선택과_등록을_별도로_수행한다', async () => {
  const f = fixture(); f.histories[1] = []; const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => {
    if (path === '/tasks/1/recommendations' && options?.method === 'POST') {
      f.histories[1] = [fixtureRecommendation(1)]; return Promise.resolve(fixtureJson({ message: '새 추천', recommendations: f.histories[1] }));
    }
    return String(path).endsWith('/select') ? Promise.resolve(complete(f)) : original(path, options);
  });
  mount(f); await userEvent.click(await screen.findByRole('button', { name: '추천 시간 찾기' }));
  await choose(); expect(posts(f)).toHaveLength(0);
  await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  await screen.findByRole('region', { name: '일정 등록 완료' }); expect(posts(f)).toHaveLength(1);
});

it.each(['SELECTED', 'SYNC_FAILED'] as const)('%s_현재_후보는_서버가_허용할_때만_같은_API로_복구한다', async (status) => {
  const f = fixture(status, true); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') ? Promise.resolve(complete(f)) : original(path, options));
  mount(f); await userEvent.click(await screen.findByRole('button', { name: 'Calendar 동기화 다시 시도' }));
  await screen.findByRole('region', { name: '일정 등록 완료' }); expect(posts(f)).toHaveLength(1);
  expect(posts(f)[0][0]).toBe('/recommendations/10/select');
});

it.each(['SELECTED', 'SYNC_FAILED'] as const)('%s라도_syncRetryable_false이면_재시도하지_않는다', async (status) => {
  const f = mount(fixture(status, false));
  expect(await screen.findByRole('region', { name: 'Calendar 동기화 복구' })).toHaveTextContent('자동으로 재시도할 수 없는 상태');
  expect(screen.queryByRole('button', { name: 'Calendar 동기화 다시 시도' })).not.toBeInTheDocument(); expect(posts(f)).toHaveLength(0);
});

it('HTTP200_SYNC_FAILED는_성공으로_표시하지_않고_복구를_제공한다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') ? Promise.resolve(complete(f, 'SYNC_FAILED')) : original(path, options));
  mount(f); await choose(); await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  expect(await screen.findByRole('region', { name: 'Calendar 동기화 복구' })).toHaveTextContent('동기화에 실패');
  await waitFor(() => expect(screen.getByRole('button', { name: 'Calendar 동기화 다시 시도' })).toBeEnabled());
  expect(screen.queryByRole('region', { name: '일정 등록 완료' })).not.toBeInTheDocument(); expect(posts(f)).toHaveLength(1);
});

it.each(['RECOMMENDATION_NO_LONGER_AVAILABLE', 'GOOGLE_CALENDAR_FREE_BUSY_FAILED'])('%s는_완료가_아니며_상태를_재조회한다', async (code) => {
  const f = fixture(); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') ? Promise.resolve(fixtureJson({ code, message: 'secret hidden' }, code === 'RECOMMENDATION_NO_LONGER_AVAILABLE' ? 409 : 502)) : original(path, options));
  mount(f); await choose(); const reads = f.transport.mock.calls.length;
  await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(code === 'RECOMMENDATION_NO_LONGER_AVAILABLE' ? '현재는 이 시간에 등록할 수 없어요' : 'Google Calendar 일정을 확인하지 못했어요');
  await waitFor(() => expect(f.transport.mock.calls.slice(reads).some(([path]) => path === '/tasks/1')).toBe(true));
  expect(screen.queryByText('secret hidden')).not.toBeInTheDocument(); expect(posts(f)).toHaveLength(1);
  expect(screen.queryByRole('region', { name: '일정 등록 완료' })).not.toBeInTheDocument();
});

it.each(['GOOGLE_CALENDAR_EVENT_MISMATCH', 'GOOGLE_CALENDAR_SYNC_RECOVERY_REQUIRED'])('%s는_수동_확인을_안내하고_재시도하지_않는다', async (code) => {
  const f = fixture('SYNC_FAILED', true); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') ? Promise.resolve(fixtureJson({ code }, 409)) : original(path, options));
  mount(f); await userEvent.click(await screen.findByRole('button', { name: 'Calendar 동기화 다시 시도' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('직접 확인');
  expect(screen.queryByRole('button', { name: 'Calendar 동기화 다시 시도' })).not.toBeInTheDocument();
  expect(posts(f)).toHaveLength(1);
});

it('응답이_유실되어도_GET에서_SYNCED가_확인되면_다시_등록하지_않는다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => {
    if (String(path).endsWith('/select')) { complete(f); return Promise.reject(new TypeError('lost response')); }
    return original(path, options);
  });
  mount(f); await choose(); await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  await screen.findByRole('region', { name: '일정 등록 완료' }); expect(posts(f)).toHaveLength(1);
});

it('응답_유실_후_상태조회도_실패하면_등록과_재추천을_잠근다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!; let lost = false;
  f.transport.mockImplementation((path, options) => {
    if (String(path).endsWith('/select')) { lost = true; return Promise.reject(new TypeError('lost')); }
    if (lost && String(path).startsWith('/tasks/1')) return Promise.resolve(fixtureJson({}, 503));
    return original(path, options);
  });
  mount(f); await choose(); await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  await waitFor(() => expect(screen.getByText('서버의 등록 결과를 확인하고 있어요')).toBeVisible());
  expect(screen.queryByRole('button', { name: '이 시간 선택' })).not.toBeInTheDocument();
  expect(screen.queryByRole('region', { name: '일정 등록 완료' })).not.toBeInTheDocument(); expect(posts(f)).toHaveLength(1);
});

it('미연동은_추천을_유지하되_등록을_차단하고_연결_후에도_최신_검증을_안내한다', async () => {
  const f = fixture(); f.setConnected(false); mount(f); await choose();
  expect(screen.getByRole('button', { name: 'Google Calendar에 등록' })).toBeDisabled();
  expect(screen.getByText(/Google 일정은 충돌 검사에 포함되지 않아요/)).toBeVisible(); expect(posts(f)).toHaveLength(0);
  f.setConnected(true); await userEvent.click(screen.getByRole('button', { name: '작업 상태 새로고침' }));
  await choose(); expect(screen.getByRole('button', { name: 'Google Calendar에 등록' })).toBeEnabled();
  expect(screen.getByText(/후보를 확정할 때에는 최신 조건을 다시 검증/)).toBeVisible();
});

it('Task를_이동했다_돌아와도_진행중인_동일_Task_등록과_재추천을_중복하지_않는다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!; let release!: (r: Response) => void;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  mount(f); await choose(); await userEvent.dblClick(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  act(() => navigateApp('/#planner?taskId=2')); await screen.findByRole('region', { name: '할 일 2' });
  act(() => navigateApp('/#planner?taskId=1')); await screen.findByRole('region', { name: '할 일 1' });
  expect(screen.getByRole('button', { name: '다시 추천받기' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Google Calendar에 등록' })).toBeDisabled();
  expect(posts(f)).toHaveLength(1);
  await act(async () => release(complete(f))); await screen.findByRole('region', { name: '일정 등록 완료' });
});

it('새로고침한_확정_Task는_GET으로_복원하고_과거_SYNCED를_현재_성공으로_오인하지_않는다', async () => {
  const f = fixture('SYNC_FAILED', true);
  f.histories[1].push({ ...fixtureRecommendation(1, 'SYNCED', 99), recommendedStartAt: '2026-10-25T10:00:00', recommendedEndAt: '2026-10-25T11:00:00' });
  const app = mount(f); await screen.findByRole('region', { name: 'Calendar 동기화 복구' });
  app.unmount(); mount(f); await screen.findByRole('region', { name: 'Calendar 동기화 복구' });
  expect(screen.queryByRole('region', { name: '일정 등록 완료' })).not.toBeInTheDocument(); expect(posts(f)).toHaveLength(0);
});

it('등록_후_Task만_조회되어도_이력과_Calendar_확인_전에는_재등록하지_않는다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!;
  let submitted = false; const releases: Array<() => Promise<void>> = [];
  f.transport.mockImplementation((path, options) => {
    if (String(path).endsWith('/select')) { submitted = true; return Promise.reject(new TypeError('lost')); }
    if (submitted && (String(path).includes('includeHistory') || path === '/api/calendar/status')) {
      return new Promise((resolve) => { releases.push(async () => resolve(await original(path, options))); });
    }
    return original(path, options);
  });
  mount(f); await choose(); await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  await waitFor(() => expect(releases).toHaveLength(2));
  expect(screen.queryByRole('button', { name: 'Google Calendar에 등록' })).not.toBeInTheDocument();
  expect(await screen.findByRole('button', { name: '추천 시간 찾기' })).toBeDisabled();
  await act(async () => { await releases[0](); });
  expect(screen.getByRole('button', { name: 'Google Calendar에 등록' })).toBeDisabled();
  await act(async () => { await releases[1](); });
  await waitFor(() => expect(screen.getByRole('button', { name: '다시 추천받기' })).toBeEnabled());
  expect(posts(f)).toHaveLength(1);
});

it('등록_인증이_만료되고_갱신도_거절되면_로그인으로_복귀하며_재등록하지_않는다', async () => {
  const f = fixture(); const original = f.transport.getMockImplementation()!;
  f.transport.mockImplementation((path, options) => String(path).endsWith('/select') || path === '/api/auth/refresh'
    ? Promise.resolve(fixtureJson({}, 401)) : original(path, options));
  mount(f); await choose(); await userEvent.click(screen.getByRole('button', { name: 'Google Calendar에 등록' }));
  await screen.findByRole('button', { name: /^로그인$/ });
  expect(window.location.pathname).toBe('/login');
  expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBeNull();
  expect(screen.queryByRole('button', { name: 'Google Calendar에 등록' })).not.toBeInTheDocument();
  expect(posts(f)).toHaveLength(1);
});
