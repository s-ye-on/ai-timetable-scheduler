import { AuthIcon } from '@/features/auth/components/auth-icon';

export function AuthBrand({ mobile = false }: { mobile?: boolean }) {
  return <a className={`auth-brand ${mobile ? 'auth-mobile-brand' : ''}`} href="/" aria-label="Campus Planner 로그인">
    <span className="brand-mark"><AuthIcon name="calendar" /></span><span>Campus Planner</span>
  </a>;
}

interface AuthIntroPanelProps { hideFooter?: boolean }

export function AuthIntroPanel({ hideFooter = false }: AuthIntroPanelProps) {
  return <aside className={`auth-intro${hideFooter ? ' auth-intro-without-footer' : ''}`} aria-label="Campus Planner 소개">
    <AuthBrand />
    <div className="auth-intro-copy">
      <p className="auth-eyebrow">개인 맞춤형 일정 추천 서비스</p>
      <h2 className="auth-hero-title">시간표에 맞는 시간,<br /><em>추천받고 골라보세요!</em></h2>
      <p>등록한 시간표와 기존 일정, 선호 조건을 고려해<br />적절한 시간을 추천해 드려요.</p>
      <div className="auth-art" aria-hidden="true">
        <div className="auth-mini-week">
          <div className="auth-art-heading"><strong>나를 위한 한 주</strong><span>수업도, 하고 싶은 일도.</span></div>
          <div className="auth-art-grid">
            <span className="auth-art-day">월</span><span className="auth-art-day">화</span><span className="auth-art-day">수</span>
            <div className="auth-art-block color-blue"><strong>자료구조</strong><span>09:00–11:00</span></div>
            <div className="auth-art-block color-mint"><strong>운영체제</strong><span>10:00–12:00</span></div>
            <div className="auth-art-block color-lavender"><strong>데이터베이스</strong><span>13:00–15:00</span></div>
          </div>
        </div>
        <div className="auth-art-note"><AuthIcon name="check" /><span>하고 싶은 일도, 딱 맞는 시간에.</span></div>
      </div>
    </div>
    {!hideFooter && <p className="auth-intro-foot">작은 계획이 모여, 더 나다운 한 학기.<span>수업 시간표와 자연어 일정 추천을 한곳에서.</span></p>}
  </aside>;
}
