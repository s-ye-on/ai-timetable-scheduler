import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthClient, RegistrationCompletedError } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';

const credentials = { name: '학생', email: 'student@example.com', password: ' password-value ' };
const tokens = { accessToken: 'registered-access', refreshToken: 'registered-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const profile = { id: 2, name: '학생', email: credentials.email };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

describe('회원가입_API_연결', () => {
  beforeEach(() => localStorage.clear());

  it('가입_응답의_토큰으로_자동_로그인하고_비밀번호는_저장하지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.taskId, 'old-task');
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens, 201)).mockResolvedValueOnce(json(profile));
    const client = new AuthClient(localStorage, transport);
    await expect(client.register({ ...credentials, name: ' 학생 ', email: ' student@example.com ' })).resolves.toEqual(profile);
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/register', '/api/auth/me']);
    expect(JSON.parse(transport.mock.calls[0][1]!.body as string)).toEqual(credentials);
    expect(new Headers(transport.mock.calls[0][1]!.headers).has('Authorization')).toBe(false);
    expect(new Headers(transport.mock.calls[1][1]!.headers).get('Authorization')).toBe('Bearer registered-access');
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBe(tokens.accessToken);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.refreshToken)).toBe(tokens.refreshToken);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.userName)).toBe(profile.name);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBeNull();
    expect(Object.values(localStorage).join(' ')).not.toContain(credentials.password);
  });

  it('실제_이메일_중복_코드만_이메일_입력_오류로_연결한다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ code: 'CONFLICT_EMAIL', message: 'private data' }, 409)));
    const error = await client.register(credentials).catch((value: unknown) => value);
    expect(error).toMatchObject({ status: 409, fieldErrors: { email: '이미 사용 중인 이메일이에요. 다른 이메일을 입력하거나 로그인해 주세요.' } });
    expect((error as Error).message).not.toContain('private data');
    expect(client.hasSession()).toBe(false);
  });

  it('서버의_이름_이메일_비밀번호_검증을_허용된_필드에만_연결한다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ fieldErrors: [
      { field: 'name', message: '30자를 넘어갈 수 없습니다.' },
      { field: 'email', message: '이메일 형식으로 입력해야 합니다.' },
      { field: 'password', message: '비밀번호 길이는 최소 8자, 최대 100자 입니다.' },
      { field: 'secret', message: '비공개' },
    ] }, 400)));
    const error = await client.register(credentials).catch((value: unknown) => value);
    expect(error).toMatchObject({ status: 400, fieldErrors: {
      name: '30자를 넘어갈 수 없습니다.', email: '이메일 형식으로 입력해야 합니다.', password: '비밀번호 길이는 최소 8자, 최대 100자 입니다.',
    } });
  });

  it('가입_성공_후_사용자_조회_실패는_가입을_다시_전송하지_않도록_구분한다', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens, 201)).mockRejectedValueOnce(new Error('private connection'));
    const client = new AuthClient(localStorage, transport);
    await expect(client.register(credentials)).rejects.toBeInstanceOf(RegistrationCompletedError);
    expect(client.hasSession()).toBe(true);
    transport.mockResolvedValueOnce(json(profile));
    await expect(client.getProfile()).resolves.toEqual(profile);
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/register', '/api/auth/me', '/api/auth/me']);
  });

  it('가입_성공_후_잘못된_토큰이나_저장소_오류도_가입_완료와_구분한다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ accessToken: 'incomplete' }, 201)));
    await expect(client.register(credentials)).rejects.toBeInstanceOf(RegistrationCompletedError);
    expect(client.hasSession()).toBe(false);
    const storage = { getItem: () => null, setItem: () => { throw new Error('blocked'); }, removeItem: vi.fn() };
    const blocked = new AuthClient(storage, vi.fn<typeof fetch>().mockResolvedValue(json(tokens, 201)));
    await expect(blocked.register(credentials)).rejects.toBeInstanceOf(RegistrationCompletedError);
    expect(storage.removeItem).toHaveBeenCalledWith(AUTH_STORAGE_KEYS.accessToken);
  });

  it('불명확한_409와_서버_장애는_중복_이메일로_단정하지_않는다', async () => {
    for (const status of [409, 500]) {
      const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ code: 'OTHER', message: 'private' }, status)));
      await expect(client.register(credentials)).rejects.toMatchObject({ fieldErrors: {} });
    }
  });

  it('가입_응답_유실은_자동_재전송하지_않고_가입_여부_확인을_안내한다', async () => {
    const transport = vi.fn<typeof fetch>().mockRejectedValue(new Error('private connection'));
    const client = new AuthClient(localStorage, transport);
    await expect(client.register(credentials)).rejects.toThrow('가입 여부를 확인하지 못했어요.');
    expect(transport).toHaveBeenCalledTimes(1);
    expect(client.hasSession()).toBe(false);
  });
});
