import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { AuthRoutes } from '@/features/auth/components/auth-routes';
import { SignupPage } from '@/features/auth/components/signup-page';

const tokens = { accessToken: 'signup-access', refreshToken: 'signup-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const profile = { id: 2, name: '새 학생', email: 'new@example.com' };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

function setup(transport = vi.fn<typeof fetch>()) {
  const client = new AuthClient(localStorage, transport);
  render(<SignupPage client={client} />);
  return { client, transport };
}

async function fillForm(password = 'password-value') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('이름'), profile.name);
  await user.type(screen.getByLabelText('이메일'), profile.email);
  await user.type(screen.getByLabelText('비밀번호', { exact: true }), password);
  return user;
}

describe('회원가입_화면', () => {
  beforeEach(() => { localStorage.clear(); window.history.replaceState({}, '', '/signup'); });

  it('원본의_세_입력과_로그인_경로를_제공하고_두_안내_영역을_제거한다', () => {
    setup();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('나만의 하루를시작해 볼까요?');
    expect(screen.getByRole('link', { name: /^로그인$/ })).toHaveAttribute('href', '/login');
    expect(screen.getByLabelText('이름')).toHaveAttribute('maxlength', '30');
    expect(screen.getByLabelText('이메일')).toHaveAttribute('maxlength', '30');
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('maxlength', '100');
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.queryByText('예시 가입 안내')).not.toBeInTheDocument();
    expect(screen.queryByText(/작은 계획이 모여/)).not.toBeInTheDocument();
    expect(screen.queryByText(/수업 시간표와 자연어 일정 추천을 한곳에서/)).not.toBeInTheDocument();
    expect(screen.queryByText(/API 미연결|student@campus.example|프로토타입/)).not.toBeInTheDocument();
  });

  it('빈_입력은_API를_호출하지_않고_이름에_초점을_준다', async () => {
    const { transport } = setup();
    await userEvent.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(screen.getByLabelText('이름')).toHaveFocus();
    for (const field of ['이름', '이메일', '비밀번호']) {
      expect(screen.getByLabelText(field, { exact: true })).toHaveAttribute('aria-invalid', 'true');
    }
    expect(transport).not.toHaveBeenCalled();
  });

  it('필수_입력_오류를_수정한_뒤_첫_클릭으로_가입을_제출한다', async () => {
    const { transport } = setup(vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens, 201)).mockResolvedValueOnce(json(profile)));
    await userEvent.click(screen.getByRole('button', { name: /^회원가입$/ }));
    const user = await fillForm();
    // 버튼을 누르는 순간 blur로 오류 영역이 접혀 위치가 바뀌지 않게 수정 입력 중에 해제한다.
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('aria-invalid', 'false');
    expect(screen.queryByText('비밀번호를 입력해 주세요.')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(await screen.findByRole('heading', { name: '가입을 환영해요!' })).toBeInTheDocument();
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/register', '/api/auth/me']);
  });

  it.each([7, 101])('비밀번호_%i자_입력은_서버_기준에_맞게_거절한다', async (length) => {
    const { transport } = setup();
    await fillForm();
    fireEvent.change(screen.getByLabelText('비밀번호', { exact: true }), { target: { value: 'a'.repeat(length) } });
    await userEvent.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAccessibleDescription(/비밀번호는 8~100자로 입력해 주세요/);
    expect(transport).not.toHaveBeenCalled();
  });

  it.each(['name', 'email'] as const)('최대_길이를_넘는_%s_입력은_API로_전송하지_않는다', async (field) => {
    const { transport } = setup();
    await fillForm();
    const input = screen.getByLabelText(field === 'name' ? '이름' : '이메일');
    fireEvent.change(input, { target: { value: field === 'name' ? '가'.repeat(31) : `${'a'.repeat(22)}@test.com` } });
    await userEvent.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(transport).not.toHaveBeenCalled();
  });

  it('잘못된_이메일과_공백_이름을_검증하고_비밀번호를_표시할_수_있다', async () => {
    const { transport } = setup();
    await fillForm();
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'invalid' } });
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 표시' }));
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('type', 'text');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 숨기기' }));
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription('올바른 이메일 형식으로 입력해 주세요.');
    expect(transport).not.toHaveBeenCalled();
  });

  it('요청_중에는_입력을_고정하고_중복_제출을_차단한다', async () => {
    let finish: ((value: Response) => void) | undefined;
    const transport = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }))
      .mockResolvedValueOnce(json(profile));
    setup(transport);
    const user = await fillForm();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    const button = screen.getByRole('button', { name: '가입 처리 중…' });
    expect(button).toBeDisabled();
    expect(screen.getByLabelText('이름')).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: '비밀번호 표시' })).toBeDisabled();
    fireEvent.submit(button.closest('form')!);
    expect(transport).toHaveBeenCalledTimes(1);
    finish!(json(tokens, 201));
    await screen.findByRole('heading', { name: '가입을 환영해요!' });
  });

  it('이메일_중복은_해당_입력과_로그인_경로를_안내하고_재입력을_허용한다', async () => {
    setup(vi.fn<typeof fetch>().mockResolvedValue(json({ code: 'CONFLICT_EMAIL' }, 409)));
    const user = await fillForm();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    await waitFor(() => expect(screen.getByLabelText('이메일')).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription(/이미 사용 중인 이메일/);
    expect(screen.getByLabelText('이메일')).toHaveFocus();
    expect(screen.getByRole('button', { name: /^회원가입$/ })).toBeEnabled();
    expect(screen.getByRole('link', { name: /^로그인$/ })).toHaveAttribute('href', '/login');
    await user.type(screen.getByLabelText('이름'), '수정');
    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription(/이미 사용 중인 이메일/);
  });

  it('서버의_이름_검증_오류를_표시하고_내부_오류는_노출하지_않는다', async () => {
    setup(vi.fn<typeof fetch>().mockResolvedValue(json({ message: 'private server data', fieldErrors: [{ field: 'name', message: '30자를 넘어갈 수 없습니다.' }] }, 400)));
    const user = await fillForm();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    await waitFor(() => expect(screen.getByLabelText('이름')).toHaveAccessibleDescription('30자를 넘어갈 수 없습니다.'));
    expect(screen.queryByText('private server data')).not.toBeInTheDocument();
  });

  it('가입_성공은_발급된_토큰과_서버_사용자_정보를_확인한_뒤에만_표시한다', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens, 201)).mockResolvedValueOnce(json(profile));
    setup(transport);
    const user = await fillForm();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    const title = await screen.findByRole('heading', { name: '가입을 환영해요!' });
    expect(title).toHaveFocus();
    expect(screen.getByText(/자동 로그인이 완료됐어요/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '플래너로 이동' })).toHaveAttribute('href', '/#onboarding/1');
    expect(screen.queryByDisplayValue('password-value')).not.toBeInTheDocument();
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/register', '/api/auth/me']);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.userName)).toBe(profile.name);
    expect(window.location.pathname).toBe('/signup');
  });

  it.each([8, 100])('서버에서_허용하는_비밀번호_%i자_경계를_유지한다', async (length) => {
    setup(vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens, 201)).mockResolvedValueOnce(json(profile)));
    const user = await fillForm('a'.repeat(length));
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(await screen.findByRole('heading', { name: '가입을 환영해요!' })).toBeInTheDocument();
  });

  it('계정_생성_후_조회가_실패하면_등록을_반복하지_않고_인증_조회만_복구한다', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens, 201)).mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(json(profile));
    setup(transport);
    const user = await fillForm();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    await screen.findByRole('heading', { name: '회원가입은 완료됐어요.' });
    expect(screen.queryByRole('button', { name: /^회원가입$/ })).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue('password-value')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '로그인 상태 다시 확인' }));
    await screen.findByRole('heading', { name: '가입을 환영해요!' });
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/register', '/api/auth/me', '/api/auth/me']);
  });

  it('가입_요청_통신_오류는_성공으로_단정하지_않고_로그인을_안내한다', async () => {
    const { transport } = setup(vi.fn<typeof fetch>().mockRejectedValue(new Error('private connection details')));
    const user = await fillForm();
    await user.click(screen.getByRole('button', { name: /^회원가입$/ }));
    expect(await screen.findByText(/가입 여부를 확인하지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '가입을 환영해요!' })).not.toBeInTheDocument();
    expect(screen.queryByText('private connection details')).not.toBeInTheDocument();
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('기존_로그인은_서버_확인_후_유지하고_새_계정을_중복_생성하지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'existing-access');
    const { transport } = setup(vi.fn<typeof fetch>().mockResolvedValue(json(profile)));
    expect(screen.getByRole('button', { name: '로그인 상태 확인 중…' })).toBeDisabled();
    await screen.findByRole('heading', { name: '이미 로그인되어 있어요.' });
    expect(screen.getByRole('link', { name: '플래너로 이동' })).toHaveAttribute('href', '/#home');
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/me']);
    expect(screen.queryByRole('button', { name: /^회원가입$/ })).not.toBeInTheDocument();
  });

  it('만료된_이전_인증은_정리하고_회원가입_입력을_허용한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired');
    setup(vi.fn<typeof fetch>().mockResolvedValue(json({}, 401)));
    await screen.findByText('로그인이 만료되었습니다. 다시 로그인해 주세요.');
    expect(screen.getByRole('button', { name: /^회원가입$/ })).toBeEnabled();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBeNull();
  });

  it.each(['/signup', '/signup/'])('%s_직접_접속은_회원가입_화면과_제목을_표시한다', (path) => {
    window.history.replaceState({}, '', path);
    render(<AuthRoutes />);
    expect(screen.getByLabelText('이름')).toBeInTheDocument();
    expect(document.title).toBe('회원가입 - Campus Planner');
  });

  it('로그인_경로는_기존_로그인_화면을_유지한다', () => {
    window.history.replaceState({}, '', '/login');
    render(<AuthRoutes />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('다시 만나 반가워요.');
    expect(document.title).toBe('로그인 - Campus Planner');
  });
});
