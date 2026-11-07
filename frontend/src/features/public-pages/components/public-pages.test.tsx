import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AboutPage } from '@/features/public-pages/components/about-page';
import { PrivacyPage } from '@/features/public-pages/components/privacy-page';
import { PRIVACY_POLICY_MARKDOWN } from '@/features/public-pages/privacy-policy.content';

const policy = readFileSync(resolve(process.cwd(), '../docs/privacy-policy.md'), 'utf8');

describe('소개와_개인정보처리방침', () => {
  beforeEach(() => { window.history.replaceState(null, '', '/about'); });

  it('운영_빌드용_본문은_최신_Markdown과_글자_단위로_일치한다', () => {
    expect(PRIVACY_POLICY_MARKDOWN).toBe(policy);
  });

  it('정책의_여섯_섹션과_문장_표_링크_미확정_표시를_빠짐없이_렌더링한다', () => {
    render(<PrivacyPage />);
    const sourceSections = policy.trim().split(/\n(?=## )/).slice(1);
    const article = screen.getByRole('article', { name: '개인정보처리방침 본문' });
    expect(within(article).getAllByRole('heading', { level: 2 })).toHaveLength(6);
    const normalize = (text: string) => text.replace(/\s+/g, '');
    const plain = (text: string) => text
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').replace(/\*\*|`/g, '')
      .replace(/^\|[\s|:-]+\|$/gm, '').replace(/^[-] /gm, '').replace(/\|/g, '');
    sourceSections.forEach((section, index) => {
      const [heading, ...body] = section.split('\n');
      const title = heading.replace(/^## \d+\. /, '');
      const region = within(article).getByRole('region', { name: title });
      expect(normalize(region.textContent || '')).toBe(normalize(String(index + 1).padStart(2, '0') + title + plain(body.join('\n'))));
      expect(region.id).toBe('policy-' + (index + 1));
    });
    const expectedPending = (sourceSections.join('\n').match(/\[확정 필요\]/g) || []).length;
    expect(article.querySelectorAll('.pending')).toHaveLength(expectedPending);
    expect(screen.getByRole('link', { name: 'csy03178@naver.com' })).toHaveAttribute('href', 'mailto:csy03178@naver.com');
    expect(screen.getByRole('link', { name: 'Google API Services User Data Policy' })).toHaveAttribute('href', 'https://developers.google.com/terms/api-services-user-data-policy');
    expect(article).toHaveTextContent('2026년 11월 16일');
    expect(article).toHaveTextContent('11월 17일까지');
    expect(article).toHaveTextContent('시행일: 2026년 11월 7일');
    expect(document.querySelector('.effective-date time')).toHaveAttribute('datetime', '2026-11-07');
    expect(document.querySelector('.effective-date')).toHaveTextContent('시행일 2026년 11월 7일');
  });

  it('데스크톱과_모바일_목차는_실제_여섯_섹션으로_이동하고_모바일_목차는_접힌다', async () => {
    render(<PrivacyPage />);
    const desktop = screen.getByRole('navigation', { name: '문서 목차' });
    expect(within(desktop).getAllByRole('link')).toHaveLength(6);
    await userEvent.click(screen.getByText('목차 · 필요한 내용 바로 찾기'));
    const mobile = screen.getByRole('navigation', { name: '모바일 문서 목차' });
    const link = within(mobile).getByRole('link', { name: '04 개인정보 보관 및 삭제' });
    expect(link).toHaveAttribute('href', '#policy-4');
    await userEvent.click(link);
    expect(mobile.closest('details')).not.toHaveAttribute('open');
    expect(document.getElementById('policy-4')).toBeInTheDocument();
  });

  it('직접_해시_진입과_뒤로_이동은_인증_전환없이_목차_위치를_복원한다', () => {
    const scroll = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll });
    window.history.replaceState(null, '', '/privacy#policy-4');
    render(<PrivacyPage />);
    expect(scroll).toHaveBeenCalledOnce();
    window.history.replaceState(null, '', '/privacy#policy-2');
    fireEvent.popState(window);
    expect(scroll).toHaveBeenCalledTimes(2);
    delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
  });

  it('소개_문구와_실제_인증_정책_링크를_유지하고_이미지는_일반_에셋을_사용한다', () => {
    render(<AboutPage />);
    expect(screen.getByRole('heading', { name: 'AI 조건 분석' })).toBeVisible();
    expect(screen.getByText('졸업 프로젝트')).toBeVisible();
    expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy');
    for (const label of ['로그인', '시작하기', '일정 추천 시작하기', '서비스 시작하기']) {
      expect(screen.getByRole('link', { name: label })).toHaveAttribute('href', '/login');
    }
    const images = screen.getAllByRole('img');
    expect(images).toHaveLength(3);
    images.forEach((image) => { expect(image.getAttribute('src')).toMatch(/service-(input|recommendations|calendar)\.png/); expect(image.getAttribute('src')).not.toMatch(/data:|presentation/); });
  });

  it('세_스크린샷이_올바른_이미지로_확대되고_닫기와_Escape가_초점을_복원한다', async () => {
    render(<AboutPage />);
    for (const label of ['자연어 입력 화면 확대', '추천 후보 3개 화면 확대', 'Google Calendar 등록 결과 확대']) {
      const trigger = screen.getByRole('button', { name: label });
      const original = within(trigger).getByRole('img').getAttribute('src');
      await userEvent.click(trigger);
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByRole('img')).toHaveAttribute('src', original);
      if (label === '자연어 입력 화면 확대') fireEvent(dialog, new Event('cancel', { cancelable: true }));
      else await userEvent.click(within(dialog).getByRole('button', { name: '화면 확대 닫기' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(trigger).toHaveFocus();
    }
    await userEvent.click(screen.getByRole('button', { name: '추천 후보 화면 확대' }));
    expect(screen.getByRole('dialog', { name: '실제 맞춤형 추천 후보 화면' })).toBeVisible();
    fireEvent.click(screen.getByRole('dialog'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
