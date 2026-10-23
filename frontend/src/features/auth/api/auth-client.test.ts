import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthApiError, AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';

const tokens = { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const profile = { id: 1, name: '학생', email: 'student@example.com' };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

describe('인증_API_연결', () => {
  beforeEach(() => localStorage.clear());

  it('실제_계약으로_로그인하고_토큰과_서버_이름만_저장한다', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(json(profile));
    const client = new AuthClient(localStorage, transport);
    await expect(client.login({ email: ' student@example.com ', password: ' secret-value ' })).resolves.toEqual(profile);
    expect(transport.mock.calls[0][0]).toBe('/api/auth/login');
    expect(JSON.parse(transport.mock.calls[0][1]!.body as string)).toEqual({ email: profile.email, password: ' secret-value ' });
    expect(new Headers(transport.mock.calls[0][1]!.headers).has('Authorization')).toBe(false);
    expect(new Headers(transport.mock.calls[1][1]!.headers).get('Authorization')).toBe('Bearer fixture-access');
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBe(tokens.accessToken);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.refreshToken)).toBe(tokens.refreshToken);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.userName)).toBe('학생');
    expect(Object.values(localStorage).join(' ')).not.toContain('secret-value');
  });

  it('기본_브라우저_fetch의_호출_컨텍스트를_변경하지_않는다', async () => {
    let count = 0;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = function (this: unknown) {
      if (this instanceof AuthClient) throw new TypeError('Illegal invocation');
      return Promise.resolve(json(count++ === 0 ? tokens : profile));
    } as typeof fetch;
    try {
      await expect(new AuthClient(localStorage).login({ email: profile.email, password: 'value' })).resolves.toEqual(profile);
    } finally { globalThis.fetch = originalFetch; }
  });

  it('로그인_실패는_사용자_존재_여부와_서버_오류_원문을_노출하지_않는다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ message: 'internal credential data' }, 401)));
    await expect(client.login({ email: profile.email, password: 'wrong' })).rejects.toThrow('이메일 또는 비밀번호를 확인해 주세요.');
    expect(client.hasSession()).toBe(false);
  });

  it('서버의_필드_검증_오류를_이메일과_비밀번호에_연결한다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ fieldErrors: [
      { field: 'email', message: '이메일 형식으로 입력해야 합니다.' }, { field: 'secret', message: '비공개' },
    ] }, 400)));
    const error = await client.login({ email: profile.email, password: 'value' }).catch((value: unknown) => value);
    expect(error).toBeInstanceOf(AuthApiError);
    expect((error as AuthApiError).fieldErrors).toEqual({ email: '이메일 형식으로 입력해야 합니다.' });
  });

  it('잘못된_성공_응답은_저장하지_않는다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({ accessToken: 'incomplete' })));
    await expect(client.login({ email: profile.email, password: 'value' })).rejects.toThrow('인증 응답을 확인할 수 없습니다.');
    expect(client.hasSession()).toBe(false);
  });

  it('액세스_만료시_회전된_토큰으로_한번만_재요청한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired-access');
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(json(profile));
    const client = new AuthClient(localStorage, transport);
    await expect(client.getProfile()).resolves.toEqual(profile);
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/me', '/api/auth/refresh', '/api/auth/me']);
    expect(JSON.parse(transport.mock.calls[1][1]!.body as string)).toEqual({ refreshToken: 'old-refresh' });
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.refreshToken)).toBe('fixture-refresh');
  });

  it('동시에_만료되어도_리프레시_토큰은_한번만_회전한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired-access');
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    let release: (() => void) | undefined;
    const barrier = new Promise<void>((resolve) => { release = resolve; });
    let refreshCount = 0;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (path, options) => {
      if (path === '/api/auth/refresh') { refreshCount++; await barrier; return json(tokens); }
      return new Headers(options?.headers).get('Authorization') === 'Bearer expired-access' ? json({}, 401) : json(profile);
    });
    const client = new AuthClient(localStorage, transport);
    const requests = [client.getProfile(), client.getProfile()];
    await vi.waitFor(() => expect(refreshCount).toBe(1));
    release!();
    await expect(Promise.all(requests)).resolves.toEqual([profile, profile]);
    expect(refreshCount).toBe(1);
  });

  it('갱신_거절시_토큰과_이전_사용자_작업을_제거한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired-access');
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'expired-refresh');
    localStorage.setItem(AUTH_STORAGE_KEYS.taskId, '42');
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({}, 401)));
    await expect(client.getProfile()).rejects.toThrow('로그인이 만료되었습니다.');
    expect(client.hasSession()).toBe(false);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBeNull();
  });

  it('갱신_후에도_401이면_무한_재시도하지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'expired-access');
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(json({}, 401));
    const client = new AuthClient(localStorage, transport);
    await expect(client.getProfile()).rejects.toThrow('로그인이 만료되었습니다.');
    expect(transport).toHaveBeenCalledTimes(3);
    expect(client.hasSession()).toBe(false);
  });

  it('일시적인_통신_실패는_유효한_저장_인증을_삭제하지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'valid-access');
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'valid-refresh');
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockRejectedValue(new Error('private connection details')));
    await expect(client.getProfile()).rejects.toThrow('연결이 원활하지 않아요.');
    expect(client.hasSession()).toBe(true);
  });

  it('403은_로그아웃이나_토큰_갱신으로_처리하지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'valid-access');
    const transport = vi.fn<typeof fetch>().mockResolvedValue(json({}, 403));
    const client = new AuthClient(localStorage, transport);
    await expect(client.getProfile()).rejects.toThrow('접근할 권한이 없습니다.');
    expect(client.hasSession()).toBe(true);
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('저장소가_차단되면_부분_인증을_남기지_않는다', async () => {
    const storage = { getItem: () => null, setItem: () => { throw new Error('blocked'); }, removeItem: vi.fn() };
    const client = new AuthClient(storage, vi.fn<typeof fetch>().mockResolvedValue(json(tokens)));
    await expect(client.login({ email: profile.email, password: 'value' })).rejects.toThrow('로그인 정보를 저장할 수 없어요.');
    expect(storage.removeItem).toHaveBeenCalledWith(AUTH_STORAGE_KEYS.accessToken);
  });

  it('다른_출처와_제어문자가_포함된_경로로_토큰을_전송하지_않는다', async () => {
    const transport = vi.fn<typeof fetch>();
    const client = new AuthClient(localStorage, transport);
    for (const path of ['https://invalid.example', '//invalid.example', '/\n/invalid.example', '/\\invalid.example']) {
      await expect(client.authenticatedRequest(path)).rejects.toThrow('허용되지 않는 요청 경로');
    }
    expect(transport).not.toHaveBeenCalled();
  });

  it('초기화된_세션을_늦게_도착한_갱신_응답이_복원하지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    let resolveRefresh: ((value: Response) => void) | undefined;
    const transport = vi.fn<typeof fetch>().mockImplementation(() => new Promise((resolve) => { resolveRefresh = resolve; }));
    const client = new AuthClient(localStorage, transport);
    const request = client.getProfile();
    client.clearSession();
    resolveRefresh!(json(tokens));
    await expect(request).rejects.toThrow('로그인 상태가 변경되었습니다.');
    expect(client.hasSession()).toBe(false);
  });
});
