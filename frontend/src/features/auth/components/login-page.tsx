import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AuthApiError, authClient } from '@/features/auth/api/auth-client';
import type { AuthClient } from '@/features/auth/api/auth-client';
import { AUTHENTICATED_HOME_PATH, AUTH_ROUTES } from '@/features/auth/auth.constants';
import { AuthBrand, AuthIntroPanel } from '@/features/auth/components/auth-intro-panel';
import { AuthIcon } from '@/features/auth/components/auth-icon';
import type { LoginField, LoginFieldErrors, UserProfile } from '@/features/auth/types/auth.types';

interface LoginPageProps {
  client?: AuthClient;
  navigate?: (path: string) => void;
}

const navigateTo = (path: string) => window.location.assign(path);

export function LoginPage({ client = authClient, navigate = navigateTo }: LoginPageProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<LoginFieldErrors>({});
  const [errorMessage, setErrorMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [restoring, setRestoring] = useState(() => client.hasSession());
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const submitted = useRef(false);
  const mounted = useRef(true);
  const summary = useRef<HTMLDivElement>(null);
  const successTitle = useRef<HTMLHeadingElement>(null);
  const emailInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const expired = new URLSearchParams(window.location.search).get('reason') === 'expired';
  const logoutUnconfirmed = new URLSearchParams(window.location.search).get('reason') === 'logout-unconfirmed';

  useEffect(() => {
    mounted.current = true;
    let active = true;
    if (client.hasSession()) {
      void client.getProfile().then((user) => { if (active) setProfile(user); })
        .catch((error: unknown) => {
          if (active) setErrorMessage(error instanceof AuthApiError ? error.message : '로그인 상태를 확인하지 못했어요.');
        }).finally(() => { if (active) setRestoring(false); });
    }
    return () => { active = false; mounted.current = false; };
  }, [client]);

  useEffect(() => {
    if (!profile) return;
    successTitle.current?.focus({ preventScroll: true });
    const timer = window.setTimeout(() => navigate(AUTHENTICATED_HOME_PATH), 1800);
    return () => window.clearTimeout(timer);
  }, [profile, navigate]);

  function validate(field: LoginField): string {
    if (field === 'email') {
      const value = email.trim();
      if (!value) return '이메일을 입력해 주세요.';
      if (value.length > 30) return '이메일은 30자 이하로 입력해 주세요.';
      if (emailInput.current?.validity.typeMismatch) return '올바른 이메일 형식으로 입력해 주세요.';
    } else if (!password.trim()) return '비밀번호를 입력해 주세요.';
    return '';
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitted.current || restoring) return;
    const errors = { email: validate('email'), password: validate('password') };
    setFieldErrors(errors);
    setErrorMessage('');
    if (errors.email || errors.password) {
      setErrorMessage('입력 내용을 확인해 주세요.');
      (errors.email ? emailInput : passwordInput).current?.focus();
      return;
    }
    submitted.current = true;
    setPending(true);
    try {
      const user = await client.login({ email, password });
      if (!mounted.current) return;
      setPassword('');
      setShowPassword(false);
      setProfile(user);
    } catch (error: unknown) {
      if (!mounted.current) return;
      setErrorMessage(error instanceof AuthApiError ? error.message : '로그인하지 못했어요. 다시 시도해 주세요.');
      setFieldErrors(error instanceof AuthApiError ? error.fieldErrors : {});
      summary.current?.focus();
    } finally {
      submitted.current = false;
      if (mounted.current) setPending(false);
    }
  }

  const busy = pending || restoring;
  return <>
    <a className="skip-link" href="#auth-content">입력 폼으로 바로가기</a>
    <div className="auth-shell">
      <AuthIntroPanel />
      <main className="auth-main">
        <header className="auth-top"><AuthBrand mobile /></header>
        <div className="auth-content" id="auth-content">
          {profile ? <section className="auth-success" aria-live="polite">
            <span className="success-mark"><AuthIcon name="check" /></span>
            <div className="section-kicker">READY FOR YOUR DAY</div>
            <h1 ref={successTitle} tabIndex={-1}>로그인되었습니다.</h1>
            <p>{profile.name}님, 오늘의 계획을 이어가세요.</p>
            <a className="btn btn-primary" href={AUTHENTICATED_HOME_PATH}>내 홈으로 이동 <AuthIcon name="arrow" /></a>
            <p className="field-help">잠시 후 기존 플래너로 이동해요.</p>
          </section> : <section>
            <div className="auth-welcome">
              <div className="section-kicker">WELCOME BACK TO YOUR CAMPUS</div>
              <h1>다시 만나 반가워요.</h1>
              <p>오늘의 수업과 하고 싶은 일을<br />내 플래너에서 이어가세요.</p>
            </div>
            {expired && <div className="auth-banner" role="alert"><strong>다시 로그인해 주세요.</strong><p>로그인 유효 시간이 지났어요. 다시 로그인한 뒤 이어서 이용해 주세요.</p></div>}
            {logoutUnconfirmed && <div className="auth-banner" role="alert"><strong>이 기기에서 로그아웃했어요.</strong><p>서버의 로그아웃 처리 여부는 확인하지 못했어요. 네트워크 상태를 확인해 주세요.</p></div>}
            <div ref={summary} className="auth-error-summary" role="alert" tabIndex={-1} hidden={!errorMessage}>
              <strong>{errorMessage}</strong>
              {Object.values(fieldErrors).some(Boolean) && <ul>
                {(Object.entries(fieldErrors) as [LoginField, string][]).filter(([, value]) => value).map(([field, value]) =>
                  <li key={field}><a href={`#${field}`} onClick={(event) => { event.preventDefault(); (field === 'email' ? emailInput : passwordInput).current?.focus(); }}>{value}</a></li>)}
              </ul>}
            </div>
            <form className="auth-form" noValidate aria-busy={busy} onSubmit={submit}>
              <div className="auth-field">
                <label htmlFor="email">이메일</label>
                <input ref={emailInput} id="email" name="email" type="email" inputMode="email" autoComplete="email" autoCapitalize="none" spellCheck={false}
                  maxLength={30} required placeholder="이메일을 입력해 주세요" value={email} readOnly={busy}
                  onChange={(event) => setEmail(event.target.value)} onBlur={() => { if (!busy) setFieldErrors((previous) => ({ ...previous, email: validate('email') })); }}
                  aria-invalid={Boolean(fieldErrors.email)} aria-describedby={fieldErrors.email ? 'email-error' : undefined} />
                {fieldErrors.email && <p className="field-error" id="email-error">{fieldErrors.email}</p>}
              </div>
              <div className="auth-field">
                <label htmlFor="password">비밀번호</label>
                <div className="auth-password">
                  <input ref={passwordInput} id="password" name="password" type={showPassword ? 'text' : 'password'} required autoComplete="current-password"
                    placeholder="비밀번호를 입력해 주세요" value={password} readOnly={busy} onChange={(event) => setPassword(event.target.value)}
                    onBlur={() => { if (!busy) setFieldErrors((previous) => ({ ...previous, password: validate('password') })); }}
                    aria-invalid={Boolean(fieldErrors.password)} aria-describedby={fieldErrors.password ? 'password-error' : undefined} />
                  <button className="auth-toggle" type="button" disabled={busy} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'}
                    aria-pressed={showPassword} aria-controls="password" onClick={() => setShowPassword((value) => !value)}>{showPassword ? '숨기기' : '표시'}</button>
                </div>
                {fieldErrors.password && <p className="field-error" id="password-error">{fieldErrors.password}</p>}
              </div>
              <button className="btn btn-primary auth-submit" type="submit" disabled={busy}>
                <span>{restoring ? '로그인 상태 확인 중…' : pending ? '로그인 중…' : '로그인'}</span><AuthIcon name="arrow" />
              </button>
              {busy && <div className="auth-status" role="status" aria-live="polite"><span className="progress-track" aria-hidden="true" /><span>{restoring ? '저장된 로그인 정보를 확인하고 있어요.' : '로그인 정보를 확인하고 있어요. 잠시만 기다려 주세요.'}</span></div>}
            </form>
            <p className="auth-switch"><span>Campus Planner가 처음인가요?</span><a href={AUTH_ROUTES.signup}>회원가입</a></p>
          </section>}
        </div>
        <footer className="auth-foot">Campus Planner · 나의 하루를, 나의 속도로.</footer>
      </main>
    </div>
  </>;
}
