import { describe, expect, it } from 'vitest';
import { resolveAppRoute } from '@/app/app-routing';

describe('앱_경로_계약', () => {
  it('기본_홈과_할일_ID를_복원한다', () => {
    expect(resolveAppRoute('').screen).toBe('home');
    expect(resolveAppRoute('#planner?taskId=123')).toMatchObject({ screen: 'planner', taskId: 123, href: '/#planner?taskId=123' });
    for (const id of ['0', '-1', '1.5', 'invalid', '99999999999999999']) expect(resolveAppRoute(`#planner?taskId=${id}`).taskId).toBeUndefined();
  });
  it('일반_선호_관리_문맥을_새로고침_후에도_유지한다', () => {
    expect(resolveAppRoute('#onboarding/2?mode=manage&returnTo=planner')).toMatchObject({ screen: 'onboarding', step: 2, mode: 'manage', returnTo: 'planner' });
    expect(resolveAppRoute('#onboarding/1?mode=manage').mode).toBeUndefined();
  });
  it('외부_복귀_주소와_알_수_없는_경로를_실행하지_않는다', () => {
    expect(resolveAppRoute('#onboarding/2?mode=manage&returnTo=https://invalid.example').returnTo).toBe('home');
    expect(resolveAppRoute('#https://invalid.example').screen).toBe('not-found');
    expect(resolveAppRoute('#onboarding/4').screen).toBe('not-found');
  });
});
