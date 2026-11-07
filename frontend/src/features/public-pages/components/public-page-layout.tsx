import { useEffect } from 'react';
import type { ReactNode } from 'react';
import '@/styles/public-pages.css';

const icons = {
  'calendar-mark': 'M7 3v4m10-4v4M4 10h16M6 5h12a2 2 0 0 1 2 2v13H4V7a2 2 0 0 1 2-2Z M8 15l3 3 5-5',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  check: 'm5 12 4 4L19 6',
  expand: 'M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5',
};

export function PublicPageIcon({ name }: { name: keyof typeof icons }) {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={icons[name]} /></svg>;
}

function Brand() {
  return <a className="brand" href="/about" aria-label="Campus Planner 서비스 소개"><span className="brand-mark"><PublicPageIcon name="calendar-mark" /></span><span>Campus Planner</span></a>;
}

export function PublicPageLayout({ privacy = false, children }: { privacy?: boolean; children: ReactNode }) {
  useEffect(() => {
    const title = document.title;
    const description = document.querySelector('meta[name="description"]');
    const previousDescription = description?.getAttribute('content');
    document.title = `${privacy ? '개인정보처리방침' : '서비스 소개'} - Campus Planner`;
    description?.setAttribute('content', privacy ? 'Campus Planner 개인정보 수집·이용, 외부 서비스 연동 및 삭제 안내' : '자연어 기반 개인 맞춤형 일정 추천 및 Google Calendar 연동 시스템');
    return () => {
      document.title = title;
      if (previousDescription !== null && previousDescription !== undefined) description?.setAttribute('content', previousDescription);
    };
  }, [privacy]);

  return <div className={`public-pages${privacy ? ' privacy-page' : ''}`}>
    <a className="skip" href="#main">본문 바로가기</a>
    <header className="site-header"><div className="container header-inner">
      <Brand />
      <nav aria-label="상단 메뉴"><a className="intro-link" href={privacy ? '/about' : '#features'}>서비스 소개</a>
        <a className="login-link" href="/login">로그인</a><a className="button small" href="/login">시작하기<PublicPageIcon name="arrow" /></a></nav>
    </div></header>
    {children}
    <footer className="site-footer">
      <div className="container footer-top"><div><Brand /><p>졸업 프로젝트</p></div>
        <div className="footer-links"><a href={privacy ? '/about' : '/privacy'}>{privacy ? '서비스 소개' : '개인정보처리방침'}</a>
          <a href="/login">서비스 시작하기<PublicPageIcon name="arrow" /></a></div></div>
      <div className="container footer-bottom"><span>© 2026 Campus Planner</span><span>{privacy ? '개인정보처리방침' : '자연어에서 시작하는, 나에게 맞는 일정.'}</span></div>
    </footer>
  </div>;
}
