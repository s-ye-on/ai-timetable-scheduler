import { AUTH_STORAGE_KEYS } from '@/features/auth/auth.constants';
import type { AuthTokens, LoginCredentials, AuthFieldErrors, RegistrationCredentials, UserProfile } from '@/features/auth/types/auth.types';

export class AuthApiError extends Error {
  constructor(
    message: string,
    readonly status = 0,
    readonly fieldErrors: AuthFieldErrors & Record<string, string> = {},
    readonly code?: string,
  ) {
    super(message);
    this.name = 'AuthApiError';
  }
}

/** 계정 생성은 성공했으므로 등록 요청을 재전송하지 않고 인증 확인만 재시도한다. */
export class RegistrationCompletedError extends AuthApiError {
  constructor() {
    super('회원가입은 완료됐지만 자동 로그인 상태를 확인하지 못했어요. 다시 확인하거나 로그인해 주세요.');
    this.name = 'RegistrationCompletedError';
  }
}

type TokenStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
type Transport = typeof fetch;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function responseBody(response: Response): Promise<unknown> {
  try { return await response.json(); } catch { return null; }
}

function authError(status: number, body: unknown, registration = false, credentials = false): AuthApiError {
  const code = isRecord(body) && typeof body.code === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(body.code) ? body.code : undefined;
  if (registration && status === 409 && isRecord(body) && body.code === 'CONFLICT_EMAIL') {
    return new AuthApiError('이메일을 확인해 주세요.', status, {
      email: '이미 사용 중인 이메일이에요. 다른 이메일을 입력하거나 로그인해 주세요.',
    }, code);
  }
  const fields: AuthFieldErrors & Record<string, string> = {};
  if (status === 400 && isRecord(body) && Array.isArray(body.fieldErrors)) {
    for (const error of body.fieldErrors) {
      if (isRecord(error) && typeof error.field === 'string' && /^[a-zA-Z][a-zA-Z0-9.\[\]]{0,99}$/.test(error.field)
        && (!credentials || error.field === 'email' || error.field === 'password' || (registration && error.field === 'name'))
        && typeof error.message === 'string') Object.defineProperty(fields, error.field, { value: error.message, enumerable: true, configurable: true });
    }
  }
  const message = status === 401 ? '이메일 또는 비밀번호를 확인해 주세요.'
    : status === 400 ? '입력 내용을 확인해 주세요.'
    : status === 403 ? '이 작업에 접근할 권한이 없습니다.'
    : '서버 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
  return new AuthApiError(message, status, fields, code);
}

function parseTokens(value: unknown): AuthTokens {
  if (!isRecord(value) || typeof value.accessToken !== 'string' || !value.accessToken
    || typeof value.refreshToken !== 'string' || !value.refreshToken
    || value.tokenType !== 'Bearer' || typeof value.accessTokenExpiresIn !== 'number'
    || !Number.isFinite(value.accessTokenExpiresIn) || value.accessTokenExpiresIn <= 0) {
    throw new AuthApiError('인증 응답을 확인할 수 없습니다. 다시 시도해 주세요.');
  }
  return value as unknown as AuthTokens;
}

/** JWT 정책은 서버가 결정한다. 토큰·비밀번호·서버 오류 원문은 로그에 남기지 않는다. */
export class AuthClient {
  private refreshPromise: Promise<void> | null = null;
  private revision = 0;
  private verifiedUserId: number | null = null;
  private readonly listeners = new Set<() => void>();

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  readonly getSessionVersion = (): number => this.revision;

  /** 다른 탭의 사용자 변경이나 BFCache 복귀 시 저장 토큰을 다시 서버에서 확인한다. */
  invalidateSession(): void {
    this.revision++;
    this.refreshPromise = null;
    this.notifySession();
  }

  private notifySession(): void {
    for (const listener of this.listeners) listener();
  }

  constructor(
    private readonly storage: TokenStorage,
    private readonly transport: Transport = (input, options) => fetch(input, options),
  ) {}

  hasSession(): boolean {
    return Boolean(this.readToken('accessToken') || this.readToken('refreshToken'));
  }

  clearSession(): void {
    this.revision++;
    this.refreshPromise = null;
    this.verifiedUserId = null;
    for (const key of Object.values(AUTH_STORAGE_KEYS)) {
      try { this.storage.removeItem(key); } catch { /* 저장소 제한 시에도 메모리 인증을 복원하지 않는다. */ }
    }
    this.notifySession();
  }

  async logout(): Promise<void> {
    const revision = this.revision;
    try {
      await this.authenticatedRequest('/api/auth/logout', { method: 'POST' });
    } catch (error) {
      if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다.', 0, {}, 'SESSION_CHANGED');
      throw error;
    } finally {
      // 새 로그인 도중 완료된 이전 로그아웃이 새 사용자를 지우지 않는다.
      if (revision === this.revision) this.clearSession();
    }
  }

  async login(credentials: LoginCredentials): Promise<UserProfile> {
    return this.authenticate('/api/auth/login', { email: credentials.email.trim(), password: credentials.password });
  }

  async register(credentials: RegistrationCredentials): Promise<UserProfile> {
    return this.authenticate('/api/auth/register', {
      name: credentials.name.trim(), email: credentials.email.trim(), password: credentials.password,
    }, true);
  }

  private async authenticate(path: string, credentials: LoginCredentials | RegistrationCredentials, registration = false): Promise<UserProfile> {
    this.clearSession();
    const revision = this.revision;
    let response: Response;
    try {
      response = await this.send(path, { method: 'POST', body: JSON.stringify(credentials) });
    } catch (error) {
      if (registration) throw new AuthApiError('가입 여부를 확인하지 못했어요. 네트워크를 확인해 주세요. 이미 가입되었다면 로그인할 수 있어요.');
      throw error;
    }
    const body = await responseBody(response);
    if (!response.ok) throw authError(response.status, body, registration, true);
    if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다. 다시 시도해 주세요.', 0, {}, 'SESSION_CHANGED');
    try {
      this.storeTokens(parseTokens(body));
      const profile = await this.getProfile();
      this.revision++;
      this.notifySession();
      return profile;
    } catch (error) {
      if (registration) throw new RegistrationCompletedError();
      throw error;
    }
  }

  async getProfile(): Promise<UserProfile> {
    const revision = this.revision;
    const body = await this.authenticatedRequest('/api/auth/me');
    if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다.', 0, {}, 'SESSION_CHANGED');
    if (!isRecord(body) || typeof body.id !== 'number' || typeof body.name !== 'string'
      || typeof body.email !== 'string') throw new AuthApiError('사용자 정보를 확인하지 못했어요. 다시 시도해 주세요.');
    if (this.verifiedUserId !== null && this.verifiedUserId !== body.id) {
      try { this.storage.removeItem(AUTH_STORAGE_KEYS.taskId); } catch { /* 다른 사용자의 보조 저장은 사용하지 않는다. */ }
    }
    this.verifiedUserId = body.id;
    try { this.storage.setItem(AUTH_STORAGE_KEYS.userName, body.name); } catch {
      throw new AuthApiError('로그인 정보를 저장할 수 없어요. 브라우저의 사이트 저장 설정을 확인해 주세요.');
    }
    return body as unknown as UserProfile;
  }

  async authenticatedRequest(path: string, options: RequestInit = {}, allowRefresh = true): Promise<unknown> {
    // 다른 출처로 토큰이 전달되는 호출을 허용하지 않는다.
    if (!path.startsWith('/') || path.startsWith('//') || /[\u0000-\u0020\\]/.test(path)) {
      throw new AuthApiError('허용되지 않는 요청 경로입니다.');
    }
    const revision = this.revision;
    let accessToken = this.readToken('accessToken');
    if (!accessToken && this.readToken('refreshToken') && allowRefresh) {
      await this.refreshSession();
      accessToken = this.readToken('accessToken');
    }
    if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다.', 0, {}, 'SESSION_CHANGED');
    const headers = new Headers(options.headers);
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    const response = await this.send(path, { ...options, headers });
    if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다.', 0, {}, 'SESSION_CHANGED');
    if (response.status === 401) {
      if (allowRefresh && this.readToken('refreshToken')) {
        // 동시에 만료 응답이 와도 이미 갱신된 토큰을 다시 회전시키지 않는다.
        if (accessToken === this.readToken('accessToken')) await this.refreshSession();
        return this.authenticatedRequest(path, options, false);
      }
      this.clearSession();
      throw new AuthApiError('로그인이 만료되었습니다. 다시 로그인해 주세요.', 401);
    }
    const body = await responseBody(response);
    if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다.', 0, {}, 'SESSION_CHANGED');
    if (!response.ok) throw authError(response.status, body);
    return body;
  }

  private refreshSession(): Promise<void> {
    if (this.refreshPromise) return this.refreshPromise;
    const revision = this.revision;
    const refreshToken = this.readToken('refreshToken');
    const pending = (async () => {
      const response = await this.send('/api/auth/refresh', {
        method: 'POST', body: JSON.stringify({ refreshToken }),
      });
      const body = await responseBody(response);
      if (revision !== this.revision) throw new AuthApiError('로그인 상태가 변경되었습니다.', 0, {}, 'SESSION_CHANGED');
      if (!response.ok) {
        if (response.status === 401 || response.status === 400) this.clearSession();
        throw new AuthApiError(response.status === 401 || response.status === 400
          ? '로그인이 만료되었습니다. 다시 로그인해 주세요.'
          : '인증 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.', response.status);
      }
      try { this.storeTokens(parseTokens(body)); } catch (error) {
        this.clearSession();
        throw error;
      }
    })();
    this.refreshPromise = pending;
    void pending.finally(() => {
      if (this.refreshPromise === pending) this.refreshPromise = null;
    }).catch(() => { /* 호출자가 동일 오류를 처리한다. */ });
    return pending;
  }

  private readToken(key: 'accessToken' | 'refreshToken'): string | null {
    try { return this.storage.getItem(AUTH_STORAGE_KEYS[key]); } catch { return null; }
  }

  private storeTokens(tokens: AuthTokens): void {
    try {
      this.storage.setItem(AUTH_STORAGE_KEYS.accessToken, tokens.accessToken);
      this.storage.setItem(AUTH_STORAGE_KEYS.refreshToken, tokens.refreshToken);
    } catch {
      this.clearSession();
      throw new AuthApiError('로그인 정보를 저장할 수 없어요. 브라우저의 사이트 저장 설정을 확인해 주세요.');
    }
  }

  private async send(path: string, options: RequestInit): Promise<Response> {
    const headers = new Headers(options.headers);
    headers.set('Content-Type', 'application/json');
    try {
      return await this.transport(path, { ...options, headers, credentials: 'same-origin', redirect: 'error' });
    } catch {
      throw new AuthApiError('연결이 원활하지 않아요. 네트워크 상태를 확인하고 다시 시도해 주세요.');
    }
  }
}

// 저장소는 사용 시점에 읽어 브라우저에서 저장소 접근을 제한해도 화면은 표시한다.
export const authClient = new AuthClient({
  getItem: (key) => window.localStorage.getItem(key),
  setItem: (key, value) => window.localStorage.setItem(key, value),
  removeItem: (key) => window.localStorage.removeItem(key),
});
