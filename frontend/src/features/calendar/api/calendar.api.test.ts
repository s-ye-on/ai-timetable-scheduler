import { describe, expect, it, vi } from 'vitest';
import { CalendarApi, checkedAuthorizationUrl } from '@/features/calendar/api/calendar.api';

const authUrl = (callback: string) => `https://accounts.google.com/o/oauth2/auth?redirect_uri=${encodeURIComponent(callback)}&state=fixture-state`;
describe('Calendar_API와_쿠키_호스트', () => {
  it.each([false, true])('연결_상태_%s를_그대로_반환한다', async (connected) => {
    expect(await new CalendarApi({ authenticatedRequest: vi.fn().mockResolvedValue({ connected }) }).status()).toBe(connected);
  });
  it.each([null, {}, { connected: 'false' }])('조회_계약_오류를_미연결로_변환하지_않는다_%j', async (body) => {
    await expect(new CalendarApi({ authenticatedRequest: vi.fn().mockResolvedValue(body) }).status()).rejects.toThrow();
  });
  it('포트만_다른_동일_호스트_Callback은_URL을_변경하지_않는다', () => {
    const url = authUrl('http://localhost:8080/api/calendar/oauth/callback');
    expect(checkedAuthorizationUrl(url, { hostname: 'localhost', protocol: 'http:' })).toBe(url);
  });
  it('localhost와_127_호스트_혼용은_state_노출_없이_안내한다', () => {
    expect(() => checkedAuthorizationUrl(authUrl('http://localhost:8080/api/calendar/oauth/callback'), { hostname: '127.0.0.1', protocol: 'http:' })).toThrow(/localhost와 127/);
    try { checkedAuthorizationUrl(authUrl('http://localhost:8080/api/calendar/oauth/callback'), { hostname: '127.0.0.1', protocol: 'http:' }); }
    catch (error) { expect(String(error)).not.toContain('fixture-state'); }
  });
  it.each(['javascript:alert(1)', 'https://untrusted.example/?state=fixture', authUrl('https://localhost/api/calendar/oauth/callback'), authUrl('http://localhost:8080/other')])('잘못된_인증_목적지는_이동하지_않는다_%s', (url) => {
    expect(() => checkedAuthorizationUrl(url, { hostname: 'localhost', protocol: 'http:' })).toThrow();
  });
});
