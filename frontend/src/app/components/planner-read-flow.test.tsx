import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { createSettingsFixture, fixtureJson } from '@/test/settings-api-fixture';
import { createTaskFixture, fixtureTask, fixtureRecommendation } from '@/test/task-api-fixture';

beforeEach(() => {
  localStorage.clear(); localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'fixture-access');
  window.history.replaceState(null, '', '/#home'); vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => { vi.restoreAllMocks(); });
function mount(path: string, fixture = createTaskFixture()) {
  window.history.replaceState(null, '', path);
  return { ...fixture, ...render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />) };
}
async function showSavedTasks() {
  const summary = await screen.findByText('저장한 할 일', { exact: true });
  if (!summary.closest('details')?.open) await userEvent.click(summary);
}

it('실제_사용자_홈에_예시_일정_대신_확정된_일정의_빈_상태를_표시한다', async () => {
  const fixture = createSettingsFixture(); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks' ? Promise.resolve(fixtureJson([])) : original(path, options));
  render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />);
  expect(await screen.findByRole('heading', { name: '학생님, 오늘도 나답게.' })).toBeVisible();
  expect(await screen.findByText('아직 확정된 일정이 없어요')).toBeVisible();
  expect(screen.getByRole('link', { name: /저장한 할 일 보기/ })).toHaveAttribute('href', '/#planner');
});

it('확정된_일정에는_SCHEDULED만_표시하고_확정_시각순으로_정렬한다', async () => {
  const late = fixtureTask(1, 'SCHEDULED', '늦은 일정');
  const early = { ...fixtureTask(2, 'SCHEDULED', '빠른 일정'), scheduledStartAt: '2026-10-25T09:00', scheduledEndAt: '2026-10-25T10:00' };
  mount('/#home', createTaskFixture([late, fixtureTask(3), fixtureTask(4, 'COMPLETED'), early, fixtureTask(5, 'CANCELED')]));
  const region = await screen.findByRole('region', { name: '확정된 일정' });
  await within(region).findByText('빠른 일정');
  expect(within(region).getAllByRole('link').map((node) => node.getAttribute('href'))).toEqual(['/#planner?taskId=2', '/#planner?taskId=1']);
  for (const title of ['할 일 3', '할 일 4', '할 일 5']) expect(within(region).queryByText(title)).not.toBeInTheDocument();
});

it('내부_일정_확정과_현재_시각에_일치하는_Calendar_동기화_결과를_구분한다', async () => {
  const tasks = [fixtureTask(1, 'SCHEDULED'), fixtureTask(2, 'SCHEDULED'), fixtureTask(3, 'SCHEDULED')];
  const old = { ...fixtureRecommendation(3, 'SYNCED'), recommendedStartAt: '2026-10-25T19:00', recommendedEndAt: '2026-10-25T20:00' };
  mount('/#home', createTaskFixture(tasks, { 1: [fixtureRecommendation(1, 'SYNCED')], 2: [fixtureRecommendation(2, 'SYNC_FAILED')], 3: [old] }));
  expect(await screen.findByText('Google Calendar 동기화 완료')).toBeVisible();
  expect(await screen.findByText('Calendar 동기화 실패 · 재시도 가능')).toBeVisible();
  expect(await screen.findByText('Calendar 동기화 확인 필요')).toBeVisible();
  expect(screen.getAllByText('일정 확정됨')).toHaveLength(3);
});

it('저장한_할_일에는_UNSCHEDULED만_표시하고_닫거나_다시_열_수_있다', async () => {
  const fixture = mount('/#planner', createTaskFixture([fixtureTask(1), fixtureTask(2, 'SCHEDULED'), fixtureTask(3, 'COMPLETED'), fixtureTask(4, 'CANCELED')]));
  expect(await screen.findByRole('link', { name: /할 일 1/ })).toBeVisible();
  for (const title of ['할 일 2', '할 일 3', '할 일 4']) expect(screen.queryByText(title)).not.toBeInTheDocument();
  await userEvent.click(screen.getByText('저장한 할 일', { exact: true }));
  expect(screen.getByRole('link', { name: /할 일 1/, hidden: true })).not.toBeVisible();
  expect(fixture.transport.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true);
});

it('목록을_선택하면_Task와_전체_이력을_조회하고_최종_등록_전에는_쓰기_요청하지_않는다', async () => {
  const fixture = mount('/#planner', createTaskFixture([fixtureTask(1)], { 1: [fixtureRecommendation(1)] }));
  await userEvent.click(await screen.findByRole('link', { name: /할 일 1/ }));
  expect(await screen.findByRole('region', { name: '할 일 1' })).toBeVisible();
  expect(await screen.findByText('저장된 추천 근거')).toBeVisible();
  expect(window.location.hash).toBe('#planner?taskId=1');
  expect(screen.getByText('기존 날짜 제한 정책 유지')).toBeVisible();
  expect(screen.queryByText('130')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '이 시간 선택' })).toHaveAttribute('aria-pressed', 'false');
  expect(screen.getByRole('button', { name: 'Google Calendar에 등록' })).toBeDisabled();
  expect(fixture.transport.mock.calls.map(([path]) => path)).toContain('/tasks/1/recommendations?includeHistory=true');
  expect(fixture.transport.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true);
});

it('후보는_rank순이고_만료_제외_이력은_후보와_구분한다', async () => {
  const one = fixtureRecommendation(1, 'PROPOSED', 1), two = { ...fixtureRecommendation(1, 'PROPOSED', 2), rank: 2 };
  mount('/#planner?taskId=1', createTaskFixture([fixtureTask(1)], { 1: [fixtureRecommendation(1, 'EXPIRED', 3), two, one] }));
  await screen.findByText('저장된 추천 후보');
  expect(screen.getAllByText(/후보 [12] · 저장된 후보/).map((node) => node.textContent)).toEqual(['후보 1 · 저장된 후보 · 최신 검증 전', '후보 2 · 저장된 후보 · 최신 검증 전']);
  expect(screen.getByText('이전 처리 이력')).toBeVisible(); expect(screen.getByText('만료된 후보')).toBeVisible();
});

it('URL_직접_접근과_새로고침은_저장_객체가_아닌_서버_상세를_다시_읽는다', async () => {
  const fixture = createTaskFixture([fixtureTask(1)]);
  const first = mount('/#planner?taskId=1', fixture);
  await screen.findByRole('region', { name: '할 일 1' }); first.unmount();
  fixture.setTasks([fixtureTask(1, 'UNSCHEDULED', '갱신된 제목')]);
  mount('/#planner?taskId=1', fixture);
  expect(await screen.findByRole('region', { name: '갱신된 제목' })).toBeVisible();
  expect(fixture.transport.mock.calls.filter(([path]) => path === '/tasks/1')).toHaveLength(2);
});

it('Task_전환과_뒤로가기는_URL의_Task를_다시_복원한다', async () => {
  mount('/#planner', createTaskFixture([fixtureTask(1), fixtureTask(2)])); const user = userEvent.setup();
  await user.click(await screen.findByRole('link', { name: /할 일 1/ })); await screen.findByRole('region', { name: '할 일 1' });
  await showSavedTasks();
  await user.click(await screen.findByRole('link', { name: /할 일 2/ })); await screen.findByRole('region', { name: '할 일 2' });
  await act(async () => { window.history.back(); });
  expect(await screen.findByRole('region', { name: '할 일 1' })).toBeVisible();
  expect(screen.queryByRole('region', { name: '할 일 2' })).not.toBeInTheDocument();
});

it('같은_할_일을_다시_선택해도_최신_상세와_이력을_재조회한다', async () => {
  const fixture = mount('/#planner?taskId=1', createTaskFixture([fixtureTask(1)])); await screen.findByRole('region', { name: '할 일 1' });
  await showSavedTasks();
  fixture.setTasks([fixtureTask(1, 'UNSCHEDULED', '서버에서 변경된 제목')]);
  await userEvent.click(screen.getByRole('link', { name: /할 일 1/ }));
  expect(await screen.findByRole('region', { name: '서버에서 변경된 제목' })).toBeVisible();
  expect(fixture.transport.mock.calls.filter(([path]) => path === '/tasks/1')).toHaveLength(2);
});

it('다른_Task로_이동한_뒤_도착한_이전_Task와_이력을_무시한다', async () => {
  const fixture = createTaskFixture([fixtureTask(1, 'UNSCHEDULED', '느린 작업'), fixtureTask(2, 'UNSCHEDULED', '현재 작업')]);
  const original = fixture.transport.getMockImplementation()!; const releases: Array<() => void> = [];
  fixture.transport.mockImplementation((path, options) => String(path).startsWith('/tasks/1') ? new Promise((resolve) => {
    releases.push(() => resolve(fixtureJson(path === '/tasks/1' ? fixtureTask(1, 'UNSCHEDULED', '느린 작업') : [{ ...fixtureRecommendation(1), reason: '느린 후보 근거' }])));
  }) : original(path, options));
  mount('/#planner?taskId=1', fixture);
  await showSavedTasks();
  await userEvent.click(await screen.findByRole('link', { name: /현재 작업/ })); await screen.findByRole('region', { name: '현재 작업' });
  await act(async () => releases.forEach((release) => release()));
  expect(screen.queryByRole('region', { name: '느린 작업' })).not.toBeInTheDocument(); expect(screen.queryByText('느린 후보 근거')).not.toBeInTheDocument();
});

it.each([404, 403])('삭제되거나_접근할_수_없는_Task_%s는_이전_상세_대신_오류를_표시한다', async (status) => {
  const fixture = createTaskFixture([fixtureTask(1)]); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => String(path).startsWith('/tasks/99') ? Promise.resolve(fixtureJson({}, status)) : original(path, options));
  mount('/#planner?taskId=99', fixture);
  expect(await screen.findByRole('alert')).toHaveTextContent('할 일 상세를 불러오지 못했어요');
  expect(screen.queryByText('저장된 추천 이력이 없어요')).not.toBeInTheDocument();
  await showSavedTasks();
  expect(screen.getByRole('link', { name: /할 일 1/ })).toBeVisible();
});

it('이력만_실패하면_Task_조건은_보존하고_추천_없음이나_동기화_완료로_위장하지_않는다', async () => {
  const fixture = createTaskFixture([fixtureTask(1, 'SCHEDULED')]); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => String(path).includes('/recommendations?') ? Promise.resolve(fixtureJson({}, 503)) : original(path, options));
  mount('/#planner?taskId=1', fixture);
  expect(await screen.findByRole('region', { name: '할 일 1' })).toBeVisible();
  expect(await screen.findByRole('alert')).toHaveTextContent('추천 이력을 불러오지 못했어요');
  expect(screen.getByText('Calendar 상태 조회 실패')).toBeVisible();
  expect(screen.queryByText('저장된 추천 이력이 없어요')).not.toBeInTheDocument();
});

it.each(['SCHEDULED', 'COMPLETED', 'CANCELED'] as const)('상세에서_%s로_확인된_Task는_오래된_미확정_목록에서_제외한다', async (status) => {
  const fixture = createTaskFixture([fixtureTask(1)]); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/1' ? Promise.resolve(fixtureJson(fixtureTask(1, status))) : original(path, options));
  mount('/#planner?taskId=1', fixture);
  expect(await screen.findByRole('region', { name: '할 일 1' })).toBeVisible();
  await showSavedTasks();
  expect(await screen.findByText('저장한 미확정 할 일이 없어요')).toBeVisible();
  expect(screen.queryByRole('link', { name: /할 일 1/ })).not.toBeInTheDocument();
});

it('조회_실패와_실제_빈_목록을_구분하고_서로_다른_홈_영역을_독립적으로_표시한다', async () => {
  const fixture = createTaskFixture(); const original = fixture.transport.getMockImplementation()!;
  let fail = true;
  fixture.transport.mockImplementation((path, options) => path === '/tasks' && fail ? Promise.resolve(fixtureJson({}, 500)) : original(path, options));
  mount('/#home', fixture);
  expect(await screen.findByRole('alert')).toHaveTextContent('확정된 일정을 불러오지 못했어요');
  expect(screen.queryByText('아직 확정된 일정이 없어요')).not.toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '아직 등록된 수업이 없어요' })).toBeVisible();
  expect(await screen.findByText(/내 선호 · 상관없음/)).toBeVisible();
  fail = false; await userEvent.click(screen.getByRole('button', { name: '다시 조회하기' }));
  expect(await screen.findByText('아직 확정된 일정이 없어요')).toBeVisible();
});

it('저장_목록_조회_실패를_미확정_할_일이_없는_것처럼_표시하지_않는다', async () => {
  const fixture = createTaskFixture(); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks' ? Promise.resolve(fixtureJson({}, 503)) : original(path, options));
  mount('/#planner', fixture);
  expect(await screen.findByRole('alert')).toHaveTextContent('저장한 할 일을 불러오지 못했어요');
  expect(screen.queryByText('저장한 미확정 할 일이 없어요')).not.toBeInTheDocument();
});

it('사용자_변경_뒤의_늦은_상세와_후보는_다른_사용자_화면에_노출되지_않는다', async () => {
  const fixture = createTaskFixture([fixtureTask(1)]); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => {
    const changed = new Headers(options?.headers).get('Authorization') === 'Bearer next-user';
    if (changed) return Promise.resolve(fixtureJson(path === '/api/auth/me' ? { id: 2, name: '다른학생', email: 'second@example.com' } : path === '/tasks' ? [] : {}, String(path).startsWith('/tasks/1') ? 404 : 200));
    return path === '/tasks/1' ? new Promise((resolve) => { release = resolve; }) : original(path, options);
  });
  mount('/#planner?taskId=1', fixture); await showSavedTasks(); await screen.findByRole('link', { name: /할 일 1/ });
  await act(async () => { localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'next-user'); window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEYS.accessToken, storageArea: localStorage })); });
  expect(await screen.findAllByRole('button', { name: '다른학생 프로필 메뉴' })).toHaveLength(2);
  await act(async () => release(fixtureJson(fixtureTask(1, 'UNSCHEDULED', '이전 사용자 비공개 제목'))));
  expect(screen.queryByText('이전 사용자 비공개 제목')).not.toBeInTheDocument();
  expect(screen.queryByText('저장된 추천 근거')).not.toBeInTheDocument();
});

it('과거_추천_사유에_저장된_내부_점수만_가리고_실제_근거는_보존한다', async () => {
  const recommendation = { ...fixtureRecommendation(1), reason: '선호 시간대와 일치합니다, 일정 전후 최소 여유 시간을 반영했습니다, 점수 87점' };
  const fixture = mount('/#planner?taskId=1', createTaskFixture([fixtureTask(1)], { 1: [recommendation] }));
  expect(await screen.findByText('선호 시간대와 일치합니다, 일정 전후 최소 여유 시간을 반영했습니다')).toBeVisible();
  expect(screen.queryByText(/87점/)).not.toBeInTheDocument();
  expect(fixture.histories[1][0].reason).toContain('점수 87점');
});

it('Task_전환_후_이전_목록_DOM이_중복으로_남지_않는다', async () => {
  mount('/#planner?taskId=1', createTaskFixture([fixtureTask(1), fixtureTask(2)]));
  await screen.findByRole('region', { name: '할 일 1' });
  await showSavedTasks();
  await userEvent.click(screen.getByRole('link', { name: /할 일 2/ }));
  await screen.findByRole('region', { name: '할 일 2' });
  expect(document.querySelectorAll('.saved-tasks-panel')).toHaveLength(1);
  expect(document.querySelectorAll('.task-detail')).toHaveLength(1);
});
