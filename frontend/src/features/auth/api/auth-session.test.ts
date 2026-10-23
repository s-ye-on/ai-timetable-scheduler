import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthApiError, AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const tokens = { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', tokenType: 'Bearer', accessTokenExpiresIn: 900 };
const profile = { id: 1, name: '학생', email: 'student@example.com' };

describe('앱_인증_경계', () => {
  beforeEach(() => { localStorage.clear(); localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'fixture-access'); });

  it('도메인_오류_코드와_인증_외_필드_오류를_보존하고_서버_원문은_노출하지_않는다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValue(json({
      code: 'INVALID_TASK', message: 'private server detail', fieldErrors: [{ field: 'durationMinutes', message: '양수여야 합니다.' }],
    }, 400)));
    const error = await client.authenticatedRequest('/tasks').catch((value: unknown) => value) as AuthApiError;
    expect(error.status).toBe(400);
    expect(error.code).toBe('INVALID_TASK');
    expect(error.fieldErrors).toEqual({ durationMinutes: '양수여야 합니다.' });
    expect(error.message).not.toContain('private server detail');
  });

  it('로그아웃은_실제_API를_호출하고_인증과_이전_작업을_정리한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.taskId, '42');
    const transport = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new AuthClient(localStorage, transport);
    const listener = vi.fn();
    const unsubscribe = client.subscribe(listener);
    await client.logout();
    expect(transport.mock.calls[0][0]).toBe('/api/auth/logout');
    expect(transport.mock.calls[0][1]?.method).toBe('POST');
    expect(new Headers(transport.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer fixture-access');
    expect(client.hasSession()).toBe(false);
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBeNull();
    expect(listener).toHaveBeenCalled();
    unsubscribe();
  });

  it('서버_로그아웃_실패도_로컬_세션은_정리하되_성공으로_반환하지_않는다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockRejectedValue(new Error('private detail')));
    await expect(client.logout()).rejects.toThrow('연결이 원활하지 않아요.');
    expect(client.hasSession()).toBe(false);
  });

  it('정상_갱신은_앱_세대를_바꾸지_않고_로그아웃은_바꾼다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(json(profile));
    const client = new AuthClient(localStorage, transport);
    const version = client.getSessionVersion();
    await client.getProfile();
    expect(client.getSessionVersion()).toBe(version);
    client.clearSession();
    expect(client.getSessionVersion()).toBeGreaterThan(version);
  });

  it('다른_사용자_전환_이후_이전_요청의_응답을_폐기한다', async () => {
    let release!: (response: Response) => void;
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockImplementation(() => new Promise((resolve) => { release = resolve; })));
    const request = client.getProfile();
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'another-user-access');
    client.invalidateSession();
    release(json(profile));
    await expect(request).rejects.toThrow('로그인 상태가 변경되었습니다.');
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.userName)).toBeNull();
  });

  it('만료된_토큰의_로그아웃도_기존_갱신_정책으로_서버_해제를_완료한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(json(tokens)).mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = new AuthClient(localStorage, transport);
    await client.logout();
    expect(transport.mock.calls.map(([path]) => path)).toEqual(['/api/auth/logout', '/api/auth/refresh', '/api/auth/logout']);
    expect(client.hasSession()).toBe(false);
  });

  it('로그아웃_중_갱신_응답이_사용자_전환_후_도착해도_새_세션을_덮지_않는다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, 'old-refresh');
    let release!: (response: Response) => void;
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(json({}, 401)).mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
    const client = new AuthClient(localStorage, transport);
    const request = client.logout();
    await vi.waitFor(() => expect(transport).toHaveBeenCalledTimes(2));
    localStorage.setItem(AUTH_STORAGE_KEYS.accessToken, 'another-user-access');
    client.invalidateSession();
    release(json(tokens));
    await expect(request).rejects.toMatchObject({ code: 'SESSION_CHANGED' });
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.accessToken)).toBe('another-user-access');
  });

  it('동일_사용자의_다른_탭_갱신과_인증_재확인은_기존_작업_ID를_유지한다', async () => {
    localStorage.setItem(AUTH_STORAGE_KEYS.taskId, '42');
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockImplementation(async () => json(profile)));
    await client.getProfile();
    client.invalidateSession();
    await client.getProfile();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBe('42');
  });

  it('다른_사용자가_서버에서_확인되면_이전_작업_ID를_제거한다', async () => {
    const client = new AuthClient(localStorage, vi.fn<typeof fetch>().mockResolvedValueOnce(json(profile)).mockResolvedValueOnce(json({ ...profile, id: 2 })));
    await client.getProfile();
    localStorage.setItem(AUTH_STORAGE_KEYS.taskId, '42');
    client.invalidateSession();
    await client.getProfile();
    expect(localStorage.getItem(AUTH_STORAGE_KEYS.taskId)).toBeNull();
  });
});
