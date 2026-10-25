import type { SettingsClient } from '@/features/preferences/preference.types';

export class CalendarApi {
  constructor(private readonly client: SettingsClient) {}
  async status(): Promise<boolean> {
    const body = await this.client.authenticatedRequest('/api/calendar/status') as { connected?: unknown } | null;
    if (!body || typeof body.connected !== 'boolean') throw new Error('Invalid Calendar status');
    return body.connected;
  }
  async authorizationUrl(): Promise<string> {
    const body = await this.client.authenticatedRequest('/api/calendar/oauth/url') as { authUrl?: unknown } | null;
    if (!body || typeof body.authUrl !== 'string') throw new Error('Invalid OAuth response');
    return body.authUrl;
  }
}

export class OAuthAddressError extends Error {}

/** state와 인증 코드는 읽거나 저장하지 않고 서버 URL의 목적지와 쿠키 호스트만 확인한다. */
export function checkedAuthorizationUrl(raw: string, current: Pick<Location, 'hostname' | 'protocol'> & { port?: string }): string {
  let url: URL;
  let callback: URL;
  try { url = new URL(raw); callback = new URL(url.searchParams.get('redirect_uri') || ''); }
  catch { throw new OAuthAddressError('Google 인증 주소를 확인하지 못했어요. 서버 OAuth 설정을 확인해 주세요.'); }
  if (url.protocol !== 'https:' || url.hostname !== 'accounts.google.com' || url.username || url.password
    || callback.pathname !== '/api/calendar/oauth/callback' || !['http:', 'https:'].includes(callback.protocol)
    || callback.username || callback.password) {
    throw new OAuthAddressError('Google 인증 주소를 확인하지 못했어요. 서버 OAuth 설정을 확인해 주세요.');
  }
  if (callback.hostname !== current.hostname || callback.protocol !== current.protocol) {
    throw new OAuthAddressError(`인증 쿠키를 유지하려면 프론트도 ${callback.protocol}//${callback.hostname}${current.port ? `:${current.port}` : ''} 호스트로 접속해야 해요. localhost와 127.0.0.1을 섞어 사용하지 말고 다시 로그인한 뒤 연결해 주세요.`);
  }
  return url.href;
}

export function calendarFailure(error: unknown): string {
  const code = (error as { code?: string } | null)?.code;
  const status = (error as { status?: number } | null)?.status;
  return code === 'GOOGLE_OAUTH_CONFIGURATION_INVALID' ? 'Google Calendar 연결 설정을 확인하지 못했어요. 서버 관리자에게 OAuth 설정 확인을 요청해 주세요.'
    : code === 'GOOGLE_CALENDAR_RECONNECT_BLOCKED' ? '완료되지 않은 일정 동기화를 먼저 복구한 후 다시 연결해 주세요. 홈의 확정된 일정에서 해당 일정을 열어 동기화 상태를 확인해 주세요.'
    : status === 403 ? 'Calendar 연결 상태에 접근할 권한이 없어요.'
    : status === 401 ? '로그인이 만료되었어요. 다시 로그인해 주세요.'
    : '연결 상태를 확인하지 못했어요. 미연결 상태로 단정할 수 없으니 다시 확인해 주세요.';
}
