import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthApiError } from '@/features/auth/api/auth-client';
import { TimetableApi } from '@/features/timetable/api/timetable.api';
import { TimetableWorkspace } from '@/features/timetable/components/timetable-workspace';
import { COLOR_LABELS, TIMETABLE_COLORS } from '@/features/timetable/timetable.types';
import { classSlot, timetableFixture } from '@/test/timetable-api-fixture';

function mount(fixture = timetableFixture()) {
  const api = new TimetableApi(fixture.client);
  return { ...fixture, api, ...render(<TimetableWorkspace api={api} />) };
}
const catalog = () => screen.getByRole('region', { name: '전체 등록된 수업' });
const field = (name: string) => screen.getByLabelText(new RegExp(name), { selector: 'input' });

describe('수업_관리_화면', () => {
  beforeEach(() => { Element.prototype.scrollIntoView = vi.fn(); });

  it('로딩과_성공한_빈_목록을_구분하고_예시_범례를_표시하지_않는다', async () => {
    let release!: (value: unknown) => void;
    const request = vi.fn().mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    render(<TimetableWorkspace api={new TimetableApi({ authenticatedRequest: request })} />);
    expect(screen.getByRole('status')).toHaveTextContent('불러오고');
    expect(screen.queryByText('첫 수업을 등록해 보세요')).not.toBeInTheDocument();
    await act(async () => release([]));
    expect(await screen.findByText('첫 수업을 등록해 보세요')).toBeVisible();
    expect(screen.queryByLabelText('수업 색상 범례')).not.toBeInTheDocument();
  });

  it('조회_실패는_빈_목록과_등록_폼을_표시하지_않고_재시도한다', async () => {
    const fixture = timetableFixture(); fixture.request.mockRejectedValueOnce(new Error('private network data')); mount(fixture);
    expect(await screen.findByRole('alert')).toHaveTextContent('수업을 불러오지 못했어요');
    expect(screen.queryByText('첫 수업을 등록해 보세요')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '새 수업 추가하기' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '다시 조회하기' }));
    expect(await screen.findByText('첫 수업을 등록해 보세요')).toBeVisible();
  });

  it('10색을_선택_저장하고_같은_과목도_슬롯별로_독립_표시한다', async () => {
    const fixture = timetableFixture(); mount(fixture); const user = userEvent.setup();
    await screen.findByText('첫 수업을 등록해 보세요');
    for (const [index, color] of TIMETABLE_COLORS.entries()) {
      await user.clear(field('수업명')); await user.type(field('수업명'), '같은 과목');
      await user.selectOptions(screen.getByLabelText('요일', { selector: 'select' }), index < 5 ? 'MONDAY' : 'TUESDAY');
      const start = `${String(8 + (index % 5) * 2).padStart(2, '0')}:00`;
      const end = `${String(9 + (index % 5) * 2).padStart(2, '0')}:00`;
      await user.clear(field('시작 시각')); await user.type(field('시작 시각'), start);
      await user.clear(field('종료 시각')); await user.type(field('종료 시각'), end);
      await user.click(screen.getByRole('radio', { name: COLOR_LABELS[color] }));
      await user.click(within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: /^수업 추가하기$/ }));
      await waitFor(() => expect(fixture.rows.size).toBe(index + 1));
    }
    expect([...fixture.rows.values()].map((slot) => slot.color)).toEqual([...TIMETABLE_COLORS]);
    expect(within(screen.getByLabelText('수업 색상 범례')).getAllByText('같은 과목')).toHaveLength(10);
    expect(within(catalog()).getAllByRole('listitem')).toHaveLength(10);
  });

  it('등록_응답과_새로고침_조회로_선택한_색상과_수업을_복원한다', async () => {
    const fixture = timetableFixture([classSlot]); const { unmount } = mount(fixture); const user = userEvent.setup();
    await screen.findByText('등록된 수업 1개');
    await user.click(within(catalog()).getByRole('button', { name: '운영체제 수업 수정' }));
    expect(field('수업명')).toHaveValue('운영체제');
    expect(screen.getByRole('radio', { name: '민트' })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: '로즈' }));
    await user.clear(field('장소')); await user.type(field('장소'), '변경 강의실');
    await user.click(screen.getByRole('button', { name: '수정 내용 저장' }));
    await screen.findByText('수업을 수정했어요.'); unmount(); mount(fixture);
    await screen.findByText('등록된 수업 1개');
    expect(within(catalog()).getByRole('listitem')).toHaveClass('color-rose');
    expect(within(catalog()).getByText('변경 강의실')).toBeVisible();
    expect(fixture.rows.get(1)?.startTime).toBe('09:00:00');
  });

  it('빈_이름_역전된_시간과_겹친_수업은_저장하지_않는다', async () => {
    const fixture = timetableFixture([classSlot]); mount(fixture); const user = userEvent.setup();
    await screen.findByText('등록된 수업 1개');
    await user.click(within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('과목명을 입력해 주세요');
    await user.type(field('수업명'), '새로운 수업');
    await user.click(within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' }));
    expect(screen.getByRole('alert')).toHaveTextContent('겹쳐요');
    await user.clear(field('종료 시각')); await user.type(field('종료 시각'), '08:00');
    await user.click(within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' }));
    expect(screen.getByRole('alert')).toHaveTextContent('종료 시간을 시작 시간보다 늦게');
    expect(fixture.request.mock.calls.filter(([, options]) => options?.method)).toHaveLength(0);
  });

  it('서버의_409_충돌과_필드_검증_오류를_입력_보존과_함께_표시한다', async () => {
    const fixture = timetableFixture(); mount(fixture); const user = userEvent.setup(); await screen.findByText('첫 수업을 등록해 보세요');
    await user.type(field('수업명'), '서버 검사 수업');
    fixture.request.mockRejectedValueOnce(new AuthApiError('raw internal data', 409, {}, 'CONFLICT_TIMETABLE_SLOT'));
    const submit = within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' });
    await user.click(submit); expect(await screen.findByRole('alert')).toHaveTextContent('이미 등록한 수업과 시간이 겹쳐요'); expect(field('수업명')).toHaveValue('서버 검사 수업');
    fixture.request.mockRejectedValueOnce(new AuthApiError('private', 400, { subjectName: '과목명을 확인해 주세요.' }, 'INVALID_REQUEST'));
    await user.click(submit); expect(await screen.findByRole('alert')).toHaveTextContent('과목명을 확인해 주세요');
    expect(fixture.rows.size).toBe(0);
  });

  it('삭제는_확인_전까지_호출하지_않고_취소와_성공_후_목록을_정확히_표시한다', async () => {
    const fixture = timetableFixture([classSlot]); mount(fixture); const user = userEvent.setup(); await screen.findByText('등록된 수업 1개');
    const remove = () => within(catalog()).getByRole('button', { name: '운영체제 수업 삭제' });
    await user.click(remove()); expect(fixture.rows.size).toBe(1);
    await user.click(screen.getByRole('button', { name: '유지하기' })); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(remove()); await user.click(screen.getByRole('button', { name: /^수업 삭제$/ }));
    expect(await screen.findByText('수업을 삭제했어요.')).toBeVisible(); expect(fixture.rows.size).toBe(0);
    expect(screen.queryByLabelText('수업 색상 범례')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^수업 추가$/ })).toHaveFocus();
  });

  it('토일_새벽_수업과_색상을_전체_목록과_모바일_요일에_보존한다', async () => {
    mount(timetableFixture([{ ...classSlot, dayOfWeek: 'SUNDAY', startTime: '01:00:30', endTime: '02:00:45', color: 'SLATE' }]));
    await screen.findByText('등록된 수업 1개');
    expect(within(catalog()).getByText('일요일 · 01:00:30–02:00:45')).toBeVisible();
    await userEvent.click(within(screen.getByRole('group', { name: '시간표 요일 선택' })).getByRole('button', { name: '일' }));
    expect(within(screen.getByRole('region', { name: '선택한 요일 수업' })).getByRole('listitem')).toHaveClass('color-slate');
  });

  it('상세_API를_조회하며_실패를_확인된_상세로_표시하지_않는다', async () => {
    const fixture = timetableFixture([classSlot]); mount(fixture); await screen.findByText('등록된 수업 1개');
    fixture.request.mockRejectedValueOnce(new AuthApiError('not found', 404, {}, 'NOT_FOUND_TIMESLOT'));
    await userEvent.click(within(catalog()).getByRole('button', { name: '운영체제 수업 상세' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('이 수업을 찾을 수 없어요');
    expect(screen.getByRole('button', { name: '수업 수정하기' })).toBeDisabled();
  });

  it('저장_통신_실패는_성공으로_단정하거나_자동_재전송하지_않고_재조회를_요구한다', async () => {
    const fixture = timetableFixture(); mount(fixture); await screen.findByText('첫 수업을 등록해 보세요');
    await userEvent.type(field('수업명'), '결과 미확인 수업'); fixture.request.mockRejectedValueOnce(new Error('private network data'));
    const submit = within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' });
    await userEvent.click(submit); expect(await screen.findByText('저장·삭제 결과를 확인하지 못했어요.')).toBeVisible();
    expect(submit).toBeDisabled(); expect(fixture.request.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: '현재 수업 다시 확인' })); await waitFor(() => expect(submit).not.toBeDisabled());
    expect(fixture.rows.size).toBe(0);
  });

  it('기존_수업_종료와_정확히_맞닿은_시작은_허용한다', async () => {
    const fixture = timetableFixture([classSlot]); mount(fixture); const user = userEvent.setup(); await screen.findByText('등록된 수업 1개');
    await user.type(field('수업명'), '이어지는 수업');
    await user.clear(field('시작 시각')); await user.type(field('시작 시각'), '10:30');
    await user.clear(field('종료 시각')); await user.type(field('종료 시각'), '11:30');
    await user.click(within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' }));
    expect(await screen.findByText('수업을 추가했어요.')).toBeVisible(); expect(fixture.rows.size).toBe(2);
  });

  it('삭제_실패시_목록을_유지하고_확인되지_않은_삭제를_반복하지_않는다', async () => {
    const fixture = timetableFixture([classSlot]); mount(fixture); await screen.findByText('등록된 수업 1개');
    fixture.request.mockRejectedValueOnce(new AuthApiError('private', 503));
    await userEvent.click(within(catalog()).getByRole('button', { name: '운영체제 수업 삭제' }));
    await userEvent.click(screen.getByRole('button', { name: /^수업 삭제$/ }));
    expect(await screen.findByText('저장·삭제 결과를 확인하지 못했어요.')).toBeVisible();
    expect(fixture.rows.size).toBe(1); expect(screen.getByRole('button', { name: /^수업 삭제$/ })).toBeDisabled();
    expect(screen.queryByText('수업을 삭제했어요.')).not.toBeInTheDocument();
  });

  it('재조회_실패는_마지막_수업을_보존하되_변경은_차단한다', async () => {
    const fixture = timetableFixture([classSlot]); mount(fixture); await screen.findByText('등록된 수업 1개');
    fixture.request.mockRejectedValueOnce(new AuthApiError('private', 403));
    await userEvent.click(screen.getByRole('button', { name: '수업 목록 새로고침' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('접근할 권한이 없어요');
    expect(within(catalog()).getByRole('listitem')).toBeVisible();
    expect(within(catalog()).getByRole('button', { name: '운영체제 수업 수정' })).toBeDisabled();
    expect(screen.queryByText('첫 수업을 등록해 보세요')).not.toBeInTheDocument();
  });

  it('처리_중_중복_제출은_하나의_등록만_보낸다', async () => {
    const fixture = timetableFixture(); mount(fixture); await screen.findByText('첫 수업을 등록해 보세요');
    await userEvent.type(field('수업명'), '중복 방지 수업');
    let release!: (value: unknown) => void;
    fixture.request.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
    const submit = within(screen.getByRole('region', { name: '새 수업 추가하기' })).getByRole('button', { name: '수업 추가하기' });
    await userEvent.dblClick(submit);
    expect(fixture.request.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
    expect(submit).toBeDisabled();
    await act(async () => release({ ...classSlot, subjectName: '중복 방지 수업' }));
    expect(await screen.findByText('수업을 추가했어요.')).toBeVisible();
  });
});
