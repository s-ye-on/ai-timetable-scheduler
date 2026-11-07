import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '@/app/components/app-routes';
import { AuthClient } from '@/features/auth/api/auth-client';

describe('비로그인_공개_페이지', () => {
  beforeEach(() => localStorage.clear());

  it.each([
    ['/about', /^복잡한 일정 고민,\s*Campus Planner\s*와 함께$/],
    ['/privacy#policy-4', '개인정보처리방침'],
  ] as const)('%s_직접_접속과_재마운트에서_로그인이나_API를_요구하지_않는다', async (path, title) => {
    window.history.replaceState(null, '', path);
    const transport = vi.fn<typeof fetch>();
    const client = new AuthClient(localStorage, transport);
    const { unmount } = render(<AppRoutes client={client} />);
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeVisible();
    expect(window.location.pathname).toBe(path.split('#')[0]);
    unmount();
    render(<AppRoutes client={client} />);
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeVisible();
    expect(transport).not.toHaveBeenCalled();
  });
});
