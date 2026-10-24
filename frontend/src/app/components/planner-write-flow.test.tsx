import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { createTaskFixture, fixtureTask, fixtureRecommendation } from '@/test/task-api-fixture';
import { fixtureJson } from '@/test/settings-api-fixture';
import { navigateApp } from '@/app/app-routing';

beforeEach(() => {
  localStorage.clear(); localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'fixture-access');
  window.history.replaceState(null, '', '/#planner'); vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});

it('비어있거나_500자를_초과하는_입력은_분석하지_않는다', async () => {
  const fixture = mount(); const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: /AI로 일정 분석하기/ }));
  expect(screen.getByRole('alert')).toHaveTextContent('분석할 일정 내용을 입력해 주세요.');
  await user.type(screen.getByLabelText('어떤 일정을 계획하고 있나요?'), '가'.repeat(501));
  await user.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  expect(screen.getByRole('alert')).toHaveTextContent('500자 이내로 입력해 주세요.');
  expect(fixture.transport.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});

it.each([
  ['MISSING_LLM_TITLE', 400, '어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요.'],
  ['MISSING_LLM_CATEGORY', 400, '일정 내용을 분류하지 못했어요. 영화 감상·운동·독서도 요청할 수 있어요. 내용을 조금 더 구체적으로 적어 주세요.'],
  ['LLM_CLARIFICATION_REQUIRED', 400, '계획할 활동이나 날짜·시간 조건을 확인해 주세요. 원하는 활동, 오전·오후와 꼭 지켜야 할 조건을 명확하게 입력해 주세요.'],
  ['UNSUPPORTED_LLM_START_SECONDS', 400, '초 단위 시작 시간은 지원하지 않아요.'],
  ['UNSUPPORTED_LLM_RELATIVE_START_RANGE', 400, '최소 시작 시각 조건은 지원하지 않아요.'],
  ['OPENAI_API_FAILED', 502, 'AI 분석 서비스에 연결하지 못했어요'],
] as const)('분석_오류_%s는_안전하게_안내하고_이전_Task로_진행하지_않는다', async (code, status, text) => {
  const fixture = planningFixture(); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/natural-language' ? Promise.resolve(fixtureJson({ code, message: '민감한 원문은 표시 금지' }, status)) : original(path, options));
  mount(fixture); const user = userEvent.setup();
  await user.type(await screen.findByLabelText('어떤 일정을 계획하고 있나요?'), '모호한 요청');
  await user.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent(text);
  expect(screen.queryByText('민감한 원문은 표시 금지')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '추천 시간 찾기' })).not.toBeInTheDocument();
  expect(window.location.hash).toBe('#planner');
});

it('이전_Task에서_새_분석으로_이동한_뒤_실패하면_이전_조건과_후보를_남기지_않는다', async () => {
  const fixture = createTaskFixture([fixtureTask(1)], { 1: [fixtureRecommendation(1)] });
  const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/natural-language' ? Promise.resolve(fixtureJson({ code: 'LLM_CLARIFICATION_REQUIRED' }, 400)) : original(path, options));
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  await screen.findByText('저장된 추천 근거');
  await userEvent.click(screen.getByRole('link', { name: /새 일정 입력하기/ }));
  await userEvent.type(screen.getByLabelText('어떤 일정을 계획하고 있나요?'), '다른 요청');
  await userEvent.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ })); await screen.findByRole('alert');
  expect(screen.queryByRole('region', { name: '할 일 1' })).not.toBeInTheDocument();
  expect(screen.queryByText('저장된 추천 근거')).not.toBeInTheDocument();
});

it('분석_중복_클릭과_입력_변경_후_지연된_응답의_화면_이동을_차단한다', async () => {
  const fixture = planningFixture(); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/natural-language' ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  mount(fixture); const user = userEvent.setup();
  await user.type(await screen.findByLabelText('어떤 일정을 계획하고 있나요?'), '공부 1시간');
  await user.dblClick(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  expect(screen.getByRole('button', { name: /분석하고 저장하는 중/ })).toBeDisabled();
  await user.clear(screen.getByLabelText('어떤 일정을 계획하고 있나요?')); await user.type(screen.getByLabelText('어떤 일정을 계획하고 있나요?'), '다른 공부 2시간');
  await act(async () => release(fixtureJson(fixtureTask(7), 201)));
  expect(window.location.hash).toBe('#planner'); expect(screen.queryByRole('region', { name: '할 일 7' })).not.toBeInTheDocument();
  expect(fixture.transport.mock.calls.filter(([path]) => path === '/tasks/natural-language')).toHaveLength(1);
});

it('분석_통신_실패는_자동_재전송하지_않고_먼저_저장_목록_확인을_안내한다', async () => {
  const fixture = planningFixture(); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/natural-language' ? Promise.reject(new TypeError('network secret must not appear')) : original(path, options));
  mount(fixture); const user = userEvent.setup();
  await user.type(await screen.findByLabelText('어떤 일정을 계획하고 있나요?'), '공부 1시간');
  await user.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('자동으로 다시 저장하지 않았어요');
  expect(screen.getByRole('link', { name: '저장한 할 일 확인하기' })).toBeVisible();
  expect(screen.queryByText(/network secret/)).not.toBeInTheDocument();
  expect(fixture.transport.mock.calls.filter(([path]) => path === '/tasks/natural-language')).toHaveLength(1);
});

it('홈에서도_동일한_분석_API로_저장하고_추천은_자동_생성하지_않는다', async () => {
  window.history.replaceState(null, '', '/#home'); const fixture = mount();
  await userEvent.type(await screen.findByLabelText('어떤 일정을 계획하고 있나요?'), '공부 1시간');
  await userEvent.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  await screen.findByRole('region', { name: '알고리즘 공부' });
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it.each(['GOOGLE_CALENDAR_FREE_BUSY_FAILED', 'GOOGLE_OAUTH_TOKEN_REFRESH_FAILED'])('추천_%s를_빈_일정이나_추천_없음으로_표시하지_않는다', async (code) => {
  const fixture = createTaskFixture([fixtureTask(1)], { 1: [fixtureRecommendation(1)] }); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/1/recommendations' ? Promise.resolve(fixtureJson({ code }, 502)) : original(path, options));
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  await screen.findByText('저장된 추천 근거'); await userEvent.click(screen.getByRole('button', { name: '다시 추천받기' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Google Calendar 일정을 확인하지 못했어요');
  expect(screen.queryByText('저장된 추천 근거')).not.toBeInTheDocument();
  expect(screen.queryByText('조건에 맞는 추천 시간이 없어요')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '저장된 작업 상태 확인하기' }));
  expect(await screen.findByText('저장된 추천 근거')).toBeVisible();
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it('정상적인_후보_없음은_서버_메시지와_함께_표시하며_임의_완화하지_않는다', async () => {
  const fixture = createTaskFixture([fixtureTask(1)]); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/1/recommendations' ? Promise.resolve(fixtureJson({ message: '필수 시각에 수업이 있어요.', recommendations: [] })) : original(path, options));
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  await userEvent.click(await screen.findByRole('button', { name: '추천 시간 찾기' }));
  expect(await screen.findByText('조건에 맞는 추천 시간이 없어요')).toBeVisible();
  expect(screen.getByText('필수 시각에 수업이 있어요.')).toBeVisible();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.queryByText('저장된 추천 이력이 없어요')).not.toBeInTheDocument();
});

it('추천_응답이_유실되면_GET_확인_전_중복_생성을_차단한다', async () => {
  const fixture = createTaskFixture([fixtureTask(1)]); const original = fixture.transport.getMockImplementation()!;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/1/recommendations' ? Promise.reject(new TypeError('lost response')) : original(path, options));
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  await userEvent.click(await screen.findByRole('button', { name: '추천 시간 찾기' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('자동으로 다시 추천하지 않았어요');
  expect(screen.getByRole('button', { name: '추천 시간 찾기' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: '저장된 작업 상태 확인하기' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '추천 시간 찾기' })).toBeEnabled());
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it('복원과_새로고침은_GET만_실행하고_메시지나_원조건을_추측하지_않는다', async () => {
  const rec = { ...fixtureRecommendation(1), matchType: null }; const fixture = createTaskFixture([fixtureTask(1)], { 1: [rec] });
  window.history.replaceState(null, '', '/#planner?taskId=1'); const view = mount(fixture);
  await screen.findByText('이전 후보 · 유형 정보 없음');
  expect(screen.queryByText('정확히 일치하는 시간대가 존재해요!')).not.toBeInTheDocument();
  view.unmount(); mount(fixture); await screen.findByRole('region', { name: '할 일 1' });
  expect(fixture.transport.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});

it('추천_중복_클릭과_Task_전환_후_늦은_응답을_차단한다', async () => {
  const fixture = createTaskFixture([fixtureTask(1), fixtureTask(2)]); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => path === '/tasks/1/recommendations' ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  await userEvent.dblClick(await screen.findByRole('button', { name: '추천 시간 찾기' }));
  await act(async () => navigateApp('/#planner?taskId=2'));
  await screen.findByRole('region', { name: '할 일 2' });
  await act(async () => release(fixtureJson({ message: '이전 Task 메시지', recommendations: [fixtureRecommendation(1)] })));
  expect(screen.queryByText('이전 Task 메시지')).not.toBeInTheDocument();
  expect(fixture.transport.mock.calls.filter(([path]) => path === '/tasks/1/recommendations')).toHaveLength(1);
});

it.each(['analysis', 'generation'] as const)('%s_중_로그아웃하면_늦은_성공_응답이_인증화면을_덮지_않는다', async (kind) => {
  const fixture = createTaskFixture([fixtureTask(1)]); const original = fixture.transport.getMockImplementation()!;
  let release!: (response: Response) => void;
  fixture.transport.mockImplementation((path, options) => (path === '/tasks/natural-language' || path === '/tasks/1/recommendations') && options?.method === 'POST'
    ? new Promise((resolve) => { release = resolve; }) : original(path, options));
  window.history.replaceState(null, '', kind === 'analysis' ? '/#planner' : '/#planner?taskId=1'); mount(fixture);
  if (kind === 'analysis') {
    await userEvent.type(await screen.findByLabelText('어떤 일정을 계획하고 있나요?'), '공부 1시간');
    await userEvent.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  } else await userEvent.click(await screen.findByRole('button', { name: '추천 시간 찾기' }));
  await act(async () => { localStorage.clear(); window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage })); });
  await screen.findByRole('heading', { name: '다시 만나 반가워요.' });
  await act(async () => release(fixtureJson(kind === 'analysis' ? fixtureTask(7) : { message: '늦은 추천', recommendations: [fixtureRecommendation(1)] })));
  expect(screen.getByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  expect(screen.queryByText('늦은 추천')).not.toBeInTheDocument();
  expect(screen.queryByRole('region', { name: '할 일 7' })).not.toBeInTheDocument();
  expect(window.location.pathname).toBe('/login');
});

it.each(['SCHEDULED', 'COMPLETED', 'CANCELED'] as const)('%s_Task는_추천_생성을_제공하지_않는다', async (status) => {
  const fixture = createTaskFixture([fixtureTask(1, status)]); window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  await screen.findByRole('region', { name: '할 일 1' });
  expect(screen.queryByRole('button', { name: /추천 시간 찾기|다시 추천받기/ })).not.toBeInTheDocument();
  expect(fixture.transport.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});

it('서버_rank순을_유지하고_대안_GENERAL_null을_EXACT로_오인하지_않는다', async () => {
  const items = [
    { ...fixtureRecommendation(1, 'PROPOSED', 11), rank: 1, score: 120, matchType: 'DATE_CHANGED' as const },
    { ...fixtureRecommendation(1, 'PROPOSED', 12), rank: 2, score: 140, matchType: 'GENERAL' as const },
    { ...fixtureRecommendation(1, 'PROPOSED', 13), rank: 3, score: 87, matchType: null },
  ];
  const fixture = createTaskFixture([fixtureTask(1)], { 1: [...items].reverse() }); window.history.replaceState(null, '', '/#planner?taskId=1'); mount(fixture);
  const cards = await screen.findAllByRole('article', { name: /추천 후보/ });
  expect(cards.map((card) => card.getAttribute('aria-label'))).toEqual(['추천 후보 1', '추천 후보 2', '추천 후보 3']);
  expect(within(cards[0]).getByText('다른 대안도 있어요')).toBeVisible();
  expect(within(cards[1]).getByText('원조건 없음')).toBeVisible();
  expect(screen.queryByText('정확히 일치하는 시간대가 존재해요!')).not.toBeInTheDocument();
  expect(screen.queryByText('140')).not.toBeInTheDocument();
  expect(screen.getByText(/성공 확률이 아니에요/)).toBeVisible();
});

it('필수_선호_허용기간_마감과_정확시각은_서버에_저장된_값으로_표시한다', async () => {
  const task = { ...fixtureTask(1), preferredDate: '2026-10-29', preferredStartTime: '19:10:00', preferredTimeRange: 'EVENING',
    dateRequired: false, timeRequired: true, preferredStartDate: '2026-10-26', preferredEndDate: '2026-11-01', deadline: '2026-10-31' };
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(createTaskFixture([task]));
  const detail = await screen.findByRole('region', { name: '할 일 1' });
  expect(within(detail).getByText('선호 날짜 · 대안 가능')).toBeVisible();
  expect(within(detail).getByText('필수 시간')).toBeVisible();
  expect(within(detail).getByText('19:10:00 · 저녁')).toBeVisible();
  expect(within(detail).getByText('2026-10-26 ~ 2026-11-01')).toBeVisible();
  expect(within(detail).getByText('2026-10-31')).toBeVisible();
});

it('원조건이_여러개여도_서버_유형대로_표시하고_없는_대안을_만들지_않는다', async () => {
  const candidates = [1, 2, 3].map((rank) => ({ ...fixtureRecommendation(1, 'PROPOSED', rank), rank, matchType: 'EXACT' as const }));
  window.history.replaceState(null, '', '/#planner?taskId=1'); mount(createTaskFixture([fixtureTask(1)], { 1: candidates }));
  expect(await screen.findAllByText('정확히 일치하는 시간대가 존재해요!')).toHaveLength(3);
  expect(screen.queryByText('다른 대안도 있어요')).not.toBeInTheDocument();
});

function planningFixture() {
  const fixture = createTaskFixture(); const read = fixture.transport.getMockImplementation()!;
  const task = { ...fixtureTask(7), title: '알고리즘 공부', preferredDate: '2026-10-29', preferredStartTime: '19:10:00', preferredTimeRange: 'EVENING', dateRequired: false, timeRequired: true };
  fixture.transport.mockImplementation(async (path, options) => {
    if (path === '/tasks/natural-language' && options?.method === 'POST') {
      fixture.setTasks([task]); const { scheduledStartAt: _start, scheduledEndAt: _end, ...created } = task;
      return fixtureJson(created, 201);
    }
    if (path === '/tasks/7/recommendations' && options?.method === 'POST') {
      const candidates = [{ ...fixtureRecommendation(7, 'PROPOSED', 72), rank: 2, score: 120, matchType: 'TIME_CHANGED' as const },
        { ...fixtureRecommendation(7, 'PROPOSED', 71), rank: 1, score: 130, matchType: 'EXACT' as const }];
      fixture.histories[7] = candidates;
      return fixtureJson({ message: '서버에서 확인한 추천 안내', recommendations: candidates });
    }
    return read(path, options);
  });
  return fixture;
}
function mount(fixture = planningFixture()) {
  return { ...fixture, ...render(<AppRoutes client={new AuthClient(localStorage, fixture.transport)} />) };
}

it('분석_Task저장_후_명시적인_별도_요청으로만_추천하고_등록하지_않는다', async () => {
  const fixture = mount(); const user = userEvent.setup();
  await user.type(await screen.findByLabelText('어떤 일정을 계획하고 있나요?'), '목요일 19시 10분에 알고리즘 공부 1시간');
  await user.click(screen.getByRole('button', { name: /AI로 일정 분석하기/ }));
  expect(await screen.findByRole('region', { name: '알고리즘 공부' })).toBeVisible();
  expect(window.location.hash).toBe('#planner?taskId=7');
  expect(screen.getByText('19:10:00 · 저녁')).toBeVisible();
  expect(fixture.transport.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
  await user.click(screen.getByRole('button', { name: '추천 시간 찾기' }));
  expect(await screen.findByText('서버에서 확인한 추천 안내')).toBeVisible();
  expect(await screen.findByText('정확히 일치하는 시간대가 존재해요!')).toBeVisible();
  expect(screen.getByText('다른 대안도 있어요')).toBeVisible();
  expect(screen.getAllByLabelText(/추천 적합도/).map((node) => node.textContent)).toEqual(['92.9점추천 적합도', '85.7점추천 적합도']);
  expect(fixture.transport.mock.calls.some(([path]) => String(path).endsWith('/select'))).toBe(false);
  await waitFor(() => expect(fixture.transport.mock.calls.filter(([path, options]) => path === '/tasks/7/recommendations' && options?.method === 'POST')).toHaveLength(1));
});
