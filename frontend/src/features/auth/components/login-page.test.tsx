import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import { LoginPage } from '@/features/auth/components/login-page';

const tokens = { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const profile = { id: 1, name: '테스트 학생', email: 'student@example.com' };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });

function setup(transport = vi.fn<typeof fetch>()) {
  const client = new AuthClient(localStorage, transport);
  const navigate = vi.fn();
  render(<LoginPage client={client} navigate={navigate} />);
  return { transport, navigate, client };
}

async function fillCredentials(password = 'password-value') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('이메일'), profile.email);
  await user.type(screen.getByLabelText('비밀번호', { exact: true }), password);
  return user;
}

describe('로그인_화면', () => {
  beforeEach(() => { localStorage.clear(); window.history.replaceState({}, '', '/'); });

  it('예시_계정과_가짜_화면_링크를_노출하지_않는다', () => {
    setup();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('다시 만나 반가워요.');
    expect(screen.queryByText('예시 계정 안내')).not.toBeInTheDocument();
    expect(screen.queryByText('student@campus.example')).not.toBeInTheDocument();
    expect(screen.queryByText('campus2026')).not.toBeInTheDocument();
    // 2단계에서 실제 회원가입 화면을 구현했으므로 준비 중 버튼을 실제 경로로 교체한다.
    expect(screen.getByRole('link', { name: '회원가입' })).toHaveAttribute('href', '/signup');
    expect(screen.queryByRole('button', { name: '회원가입' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('이메일')).toHaveValue('');
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveValue('');
  });

  it('필수_입력_오류는_API를_호출하지_않고_첫_입력에_초점을_준다', async () => {
    const { transport } = setup();
    await userEvent.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(screen.getByLabelText('이메일')).toHaveFocus();
    expect(screen.getByLabelText('이메일')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getAllByText('비밀번호를 입력해 주세요.').length).toBeGreaterThan(0);
    expect(transport).not.toHaveBeenCalled();
  });

  it('잘못된_이메일은_클라이언트에서_안내한다', async () => {
    const { transport } = setup();
    await userEvent.type(screen.getByLabelText('이메일'), 'not-an-email');
    await userEvent.type(screen.getByLabelText('비밀번호', { exact: true }), 'value');
    await userEvent.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription('올바른 이메일 형식으로 입력해 주세요.');
    expect(transport).not.toHaveBeenCalled();
  });

  it('비밀번호를_표시하고_다시_숨길_수_있다', async () => {
    setup();
    await userEvent.type(screen.getByLabelText('비밀번호', { exact: true }), 'typed-value');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 표시' }));
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('type', 'text');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 숨기기' }));
    expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveAttribute('type', 'password');
  });

  it('로그인_중에는_중복_제출과_입력_변경을_막는다', async () => {
    let finish: ((value: Response) => void) | undefined;
    const transport = vi.fn<typeof fetch>()
      .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }))
      .mockResolvedValueOnce(json(profile));
    setup(transport);
    const user = await fillCredentials();
    await user.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(screen.getByRole('button', { name: '로그인 중…' })).toBeDisabled();
    expect(screen.getByLabelText('이메일')).toHaveAttribute('readonly');
    fireEvent.submit(screen.getByRole('button', { name: '로그인 중…' }).closest('form')!);
    expect(transport).toHaveBeenCalledTimes(1);
    finish!(json(tokens));
    await screen.findByRole('heading', { name: '로그인되었습니다.' });
  });

  it('로그인_실패를_표시하고_재시도할_수_있다', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValue(json({ message: '비공개 서버 오류 원문' }, 401));
    setup(transport);
    const user = await fillCredentials();
    await user.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(await screen.findByText('이메일 또는 비밀번호를 확인해 주세요.')).toBeInTheDocument();
    expect(screen.queryByText('비공개 서버 오류 원문')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^로그인$/ })).toBeEnabled();
    expect(screen.queryByText('로그인되었습니다.')).not.toBeInTheDocument();
  });

  it('통신_실패는_잘못된_비밀번호와_구분한다', async () => {
    setup(vi.fn<typeof fetch>().mockRejectedValue(new Error('private transport data')));
    const user = await fillCredentials();
    await user.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(await screen.findByText('연결이 원활하지 않아요. 네트워크 상태를 확인하고 다시 시도해 주세요.')).toBeInTheDocument();
    expect(screen.queryByText('private transport data')).not.toBeInTheDocument();
  });

  it('인증_성공_후에만_비밀번호를_지우고_React_홈으로_이동한다', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(json(profile));
    const { navigate } = setup(transport);
    const user = await fillCredentials('short');
    await user.click(screen.getByRole('button', { name: /^로그인$/ }));
    expect(await screen.findByRole('heading', { name: '로그인되었습니다.' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '내 홈으로 이동' })).toHaveAttribute('href', '/#home');
    expect(screen.queryByDisplayValue('short')).not.toBeInTheDocument();
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/#home'), { timeout: 2500 });
  });

  it('저장된_인증을_서버에서_확인한_뒤_복원한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'saved-access');
    const { transport } = setup(vi.fn<typeof fetch>().mockResolvedValue(json(profile)));
    expect(screen.getByRole('button', { name: '로그인 상태 확인 중…' })).toBeDisabled();
    await screen.findByRole('heading', { name: '로그인되었습니다.' });
    expect(transport.mock.calls[0][0]).toBe('/api/auth/me');
  });

  it('만료된_저장_인증은_정리하고_로그인_폼을_다시_제공한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired-access');
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'expired-refresh');
    setup(vi.fn<typeof fetch>().mockResolvedValue(json({}, 401)));
    expect(await screen.findByText('로그인이 만료되었습니다. 다시 로그인해 주세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^로그인$/ })).toBeEnabled();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBeNull();
  });

  it('인증_만료_안내_주소를_지원한다', () => {
    window.history.replaceState({}, '', '/login?reason=expired');
    setup();
    expect(screen.getByText('로그인 유효 시간이 지났어요. 다시 로그인한 뒤 이어서 이용해 주세요.')).toBeInTheDocument();
  });
});
