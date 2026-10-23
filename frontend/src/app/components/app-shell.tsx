import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { UserProfile } from '@/features/auth/types/auth.types';
import type { AppRoute } from '@/app/app-routing';
import { AppIcon } from '@/app/components/app-icon';

interface AppShellProps {
  profile: UserProfile;
  route: AppRoute;
  location: string;
  onLogout: () => Promise<void>;
  children: ReactNode;
}

const navigation = [
  { label: 'MY PLANNER', name: '내 플래너', items: [
    { screen: 'home', label: '홈', href: '/#home', icon: 'home' },
    { screen: 'planner', label: '일정 추천', href: '/#planner', icon: 'planner' },
  ] },
  { label: 'MY SEMESTER', name: '내 학기 관리', items: [
    { screen: 'timetable', label: '내 시간표', href: '/#timetable', icon: 'timetable' },
    { screen: 'onboarding', label: '선호 · 연동', href: '/#onboarding/2?mode=manage&returnTo=home', icon: 'settings' },
  ] },
] as const;

export function AppShell({ profile, route, location, onLogout, children }: AppShellProps) {
  const [menu, setMenu] = useState<{ location: string; anchor: 'sidebar' | 'top' } | null>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const main = useRef<HTMLElement>(null);
  const open = menu?.location === location;
  useEffect(() => {
    document.title = `${route.title} - Campus Planner`;
    main.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, [location, route.title]);

  function closeMenu() {
    setMenu(null);
    trigger.current?.focus();
  }

  const avatar = <span className="avatar" aria-hidden="true">{Array.from(profile.name.trim())[0] || '나'}</span>;
  const brand = <><span className="brand-mark"><AppIcon name="brand" /></span><span className="brand-name">Campus Planner</span></>;
  function profileButton(anchor: 'sidebar' | 'top') {
    return <button type="button" className={`profile-trigger ${anchor === 'sidebar' ? 'profile' : 'top-profile-trigger'}`}
      aria-haspopup="dialog" aria-expanded={open && menu?.anchor === anchor} aria-controls="profile-menu" aria-label={`${profile.name} 프로필 메뉴`}
      onClick={(event) => {
        if (open && menu?.anchor === anchor) closeMenu();
        else { trigger.current = event.currentTarget; setMenu({ location, anchor }); }
      }}>{avatar}{anchor === 'sidebar' && <><span className="od-field od-fill"><strong>{profile.name}</strong><small>{profile.email}</small></span><AppIcon name="arrow" className="profile-chevron" /></>}</button>;
  }

  return <div className="campus-app">
    <a className="skip-link" href="#main" onClick={(event) => { event.preventDefault(); main.current?.focus(); }}>본문 바로가기</a>
    <div className="app-shell">
      <aside className="sidebar" aria-label="주요 탐색">
        <a className="brand" href="/#home" aria-label="Campus Planner 홈">{brand}</a>
        {navigation.map((group) => <nav className="nav-group" aria-label={group.name} key={group.name}>
          <p className="nav-label">{group.label}</p><div className="nav-list">{group.items.map((item) => <a key={item.screen}
            className={`nav-item${route.screen === item.screen ? ' active' : ''}`} href={item.href} aria-label={item.label}
            aria-current={route.screen === item.screen ? 'page' : undefined}><AppIcon name={item.icon} /><span>{item.label}</span></a>)}</div>
        </nav>)}
        <div className="sidebar-bottom">{profileButton('sidebar')}</div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb"><AppIcon name="home" /><span>/</span><span>{route.title}</span></div>
          <a className="brand mobile-brand" href="/#home">{brand}</a>
          <div className="top-right">{profileButton('top')}</div>
        </header>
        <main className="main-content" id="main" tabIndex={-1} ref={main}>{children}</main>
      </div>
    </div>
    {open && <ProfileMenu key={`${location}:${menu.anchor}`} profile={profile} anchor={menu.anchor} trigger={trigger} onClose={closeMenu} onLogout={onLogout} />}
  </div>;
}

function ProfileMenu({ profile, anchor, trigger, onClose, onLogout }: {
  profile: UserProfile; anchor: 'sidebar' | 'top'; trigger: React.RefObject<HTMLButtonElement | null>;
  onClose: () => void; onLogout: () => Promise<void>;
}) {
  const section = useRef<HTMLElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    close.current?.focus();
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); onClose(); } };
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !section.current?.contains(event.target) && !trigger.current?.contains(event.target)) onClose();
    };
    document.addEventListener('keydown', escape);
    document.addEventListener('pointerdown', outside);
    return () => { document.removeEventListener('keydown', escape); document.removeEventListener('pointerdown', outside); };
  }, [onClose, trigger]);

  return <section className={`profile-menu profile-menu-${anchor}`} ref={section} id="profile-menu" role="dialog" aria-labelledby="profile-menu-title">
    <div className="profile-menu-head"><div className="od-field"><h2 id="profile-menu-title">프로필</h2><span>{profile.name}</span><span>{profile.email}</span></div>
      <button type="button" ref={close} className="icon-btn" onClick={onClose} aria-label="프로필 메뉴 닫기"><AppIcon name="close" /></button></div>
    <button className="profile-logout" type="button" onClick={() => { void onLogout(); }}><AppIcon name="logout" /><span>로그아웃</span><AppIcon name="arrow" /></button>
  </section>;
}
