import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AuthApiError, authClient, RegistrationCompletedError } from '@/features/auth/api/auth-client';
import type { AuthClient } from '@/features/auth/api/auth-client';
import { AUTH_ROUTES, REGISTRATION_DESTINATION } from '@/features/auth/auth.constants';
import { AuthBrand, AuthIntroPanel } from '@/features/auth/components/auth-intro-panel';
import { AuthIcon } from '@/features/auth/components/auth-icon';
import type { AuthFieldErrors, RegistrationCredentials, RegistrationField, UserProfile } from '@/features/auth/types/auth.types';

interface SignupPageProps { client?: AuthClient }
const FIELD_ORDER: RegistrationField[] = ['name', 'email', 'password'];

export function SignupPage({ client = authClient }: SignupPageProps) {
  const [values, setValues] = useState<RegistrationCredentials>({ name: '', email: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<AuthFieldErrors>({});
  const [errorMessage, setErrorMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [restoring, setRestoring] = useState(() => client.hasSession());
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [registrationCompleted, setRegistrationCompleted] = useState(false);
  const [sessionCheckFailed, setSessionCheckFailed] = useState(false);
  const [focusRequest, setFocusRequest] = useState<{ field?: RegistrationField } | null>(null);
  const submitted = useRef(false);
  const mounted = useRef(true);
  const inputs = useRef<Record<RegistrationField, HTMLInputElement | null>>({ name: null, email: null, password: null });
  const summary = useRef<HTMLDivElement>(null);
  const successTitle = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    mounted.current = true;
    let active = true;
    if (client.hasSession()) {
      void client.getProfile().then((user) => { if (active) setProfile(user); })
        .catch((error: unknown) => {
          if (active) {
            setErrorMessage(error instanceof AuthApiError ? error.message : '로그인 상태를 확인하지 못했어요.');
            setFocusRequest({});
            setSessionCheckFailed(client.hasSession());
          }
        }).finally(() => { if (active) setRestoring(false); });
    }
    return () => { active = false; mounted.current = false; };
  }, [client]);

  useEffect(() => {
    if (profile) successTitle.current?.focus({ preventScroll: true });
    else if (focusRequest) {
      if (focusRequest.field) inputs.current[focusRequest.field]?.focus();
      else summary.current?.focus();
    }
  }, [profile, focusRequest]);

  function validate(field: RegistrationField, value = values[field]): string {
    if (field === 'name') {
      if (!value.trim()) return '이름을 입력해 주세요.';
      if (value.length > 30) return '이름은 30자 이하로 입력해 주세요.';
    } else if (field === 'email') {
      if (!value.trim()) return '이메일을 입력해 주세요.';
      if (value.length > 30) return '이메일은 30자 이하로 입력해 주세요.';
      if (inputs.current.email?.validity.typeMismatch) return '올바른 이메일 형식으로 입력해 주세요.';
    } else {
      if (!value.trim()) return '비밀번호를 입력해 주세요.';
      if (value.length < 8 || value.length > 100) return '비밀번호는 8~100자로 입력해 주세요.';
    }
    return '';
  }

  function updateValue(field: RegistrationField, value: string) {
    setValues((previous) => ({ ...previous, [field]: value }));
    setErrorMessage('');
    if (fieldErrors[field]) setFieldErrors((previous) => ({ ...previous, [field]: validate(field, value) }));
  }

  function validateOnBlur(field: RegistrationField) {
    // 서버 오류는 해당 값을 바꾸기 전까지 유지한다. 다른 입력으로 이동한 것만으로 지우지 않는다.
    if (!busy && !fieldErrors[field]) setFieldErrors((previous) => ({ ...previous, [field]: validate(field) }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitted.current || restoring || registrationCompleted || sessionCheckFailed || profile) return;
    const errors = Object.fromEntries(FIELD_ORDER.map((field) => [field, validate(field)]));
    setFieldErrors(errors);
    setErrorMessage('');
    if (Object.values(errors).some(Boolean)) {
      setErrorMessage('입력 내용을 확인해 주세요.');
      setFocusRequest({ field: FIELD_ORDER.find((field) => errors[field]) });
      return;
    }
    submitted.current = true;
    setPending(true);
    try {
      const user = await client.register(values);
      if (!mounted.current) return;
      setRegistrationCompleted(true);
      setProfile(user);
      setValues({ name: '', email: '', password: '' });
      setShowPassword(false);
    } catch (error: unknown) {
      if (!mounted.current) return;
      if (error instanceof RegistrationCompletedError) {
        setRegistrationCompleted(true);
        setValues({ name: '', email: '', password: '' });
        setShowPassword(false);
      }
      setErrorMessage(error instanceof AuthApiError ? error.message : '회원가입을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.');
      const errors = error instanceof AuthApiError ? error.fieldErrors : {};
      setFieldErrors(errors);
      setFocusRequest({ field: FIELD_ORDER.find((field) => errors[field]) });
    } finally {
      submitted.current = false;
      if (mounted.current) setPending(false);
    }
  }

  async function retrySessionCheck() {
    if (submitted.current) return;
    submitted.current = true;
    setPending(true);
    setErrorMessage('');
    try {
      const user = await client.getProfile();
      if (mounted.current) setProfile(user);
    } catch (error: unknown) {
      if (mounted.current) {
        setErrorMessage(error instanceof AuthApiError ? error.message : '로그인 상태를 확인하지 못했어요.');
        setFocusRequest({});
      }
    } finally {
      submitted.current = false;
      if (mounted.current) setPending(false);
    }
  }

  const busy = pending || restoring;
  const recovering = registrationCompleted || sessionCheckFailed;
  return <>
    <a className="skip-link" href="#auth-content">입력 폼으로 바로가기</a>
    <div className="auth-shell">
      <AuthIntroPanel hideFooter />
      <main className="auth-main">
        <header className="auth-top"><AuthBrand mobile /></header>
        <div className="auth-content" id="auth-content">
          {profile ? <section className="auth-success" aria-live="polite">
            <span className="success-mark"><AuthIcon name="check" /></span>
            <div className="section-kicker">READY FOR YOUR NEW SEMESTER</div>
            <h1 ref={successTitle} tabIndex={-1}>{registrationCompleted ? '가입을 환영해요!' : '이미 로그인되어 있어요.'}</h1>
            <p>{profile.name}님, 내 플래너에서 계획을 시작해 보세요.</p>
            <p className="auth-success-note">{registrationCompleted ? '자동 로그인이 완료됐어요. 지금은 기존 플래너에서 시간표를 등록할 수 있어요.' : '기존 플래너에서 계획을 이어갈 수 있어요.'}</p>
            <a className="btn btn-primary" href={REGISTRATION_DESTINATION.current}>플래너로 이동 <AuthIcon name="arrow" /></a>
          </section> : <section>
            <div className="auth-welcome">
              <div className="section-kicker">YOUR SEMESTER STARTS HERE</div>
              <h1>{recovering ? (registrationCompleted ? '회원가입은 완료됐어요.' : '로그인 상태 확인이 필요해요.') : <>나만의 하루를<br />시작해 볼까요?</>}</h1>
              <p>{recovering ? '다시 가입하지 않고 로그인 상태를 확인해 주세요.' : '세 가지 정보만 입력하면 준비 끝이에요.'}</p>
            </div>
            <div ref={summary} className="auth-error-summary" role="alert" tabIndex={-1} hidden={!errorMessage}>
              <strong>{errorMessage}</strong>
              {Object.values(fieldErrors).some(Boolean) && <ul>{FIELD_ORDER.filter((field) => fieldErrors[field]).map((field) =>
                <li key={field}><a href={`#${field}`} onClick={(event) => { event.preventDefault(); inputs.current[field]?.focus(); }}>{fieldErrors[field]}</a></li>)}</ul>}
            </div>
            {recovering ? <div className="auth-form" aria-busy={pending}>
              {client.hasSession() && <button className="btn btn-primary auth-submit" type="button" disabled={pending} onClick={() => { void retrySessionCheck(); }}>{pending ? '확인 중…' : '로그인 상태 다시 확인'}</button>}
              <p className="auth-switch"><a href={AUTH_ROUTES.login}>로그인 화면으로 이동</a></p>
            </div> : <>
              <form className="auth-form" noValidate aria-busy={busy} onSubmit={submit}>
                {FIELD_ORDER.map((field) => <div className="auth-field" key={field}>
                  <label htmlFor={field}>{field === 'name' ? '이름' : field === 'email' ? '이메일' : '비밀번호'}</label>
                  {field === 'password' ? <div className="auth-password">
                    <input ref={(element) => { inputs.current.password = element; }} id="password" name="password" type={showPassword ? 'text' : 'password'} required minLength={8} maxLength={100} autoComplete="new-password"
                      placeholder="8~100자로 입력해 주세요" value={values.password} readOnly={busy}
                      onChange={(event) => updateValue('password', event.target.value)}
                      onBlur={() => validateOnBlur('password')}
                      aria-invalid={Boolean(fieldErrors.password)} aria-describedby={`password-help${fieldErrors.password ? ' password-error' : ''}`} />
                    <button className="auth-toggle" type="button" disabled={busy} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'} aria-pressed={showPassword} aria-controls="password" onClick={() => setShowPassword((value) => !value)}>{showPassword ? '숨기기' : '표시'}</button>
                  </div> : <input ref={(element) => { inputs.current[field] = element; }} id={field} name={field} type={field === 'email' ? 'email' : 'text'} required maxLength={30} autoComplete={field}
                    inputMode={field === 'email' ? 'email' : undefined} autoCapitalize={field === 'email' ? 'none' : undefined} spellCheck={field === 'email' ? false : undefined}
                    placeholder={field === 'name' ? '이름을 입력해 주세요' : '이메일을 입력해 주세요'} value={values[field]} readOnly={busy}
                    onChange={(event) => updateValue(field, event.target.value)}
                    onBlur={() => validateOnBlur(field)}
                    aria-invalid={Boolean(fieldErrors[field])} aria-describedby={fieldErrors[field] ? `${field}-error` : undefined} />}
                  {field === 'password' && <p className="field-help" id="password-help">8~100자로 입력해 주세요.</p>}
                  {fieldErrors[field] && <p className="field-error" id={`${field}-error`}>{fieldErrors[field]}</p>}
                </div>)}
                <button className="btn btn-primary auth-submit" type="submit" disabled={busy}><span>{restoring ? '로그인 상태 확인 중…' : pending ? '가입 처리 중…' : '회원가입'}</span><AuthIcon name="arrow" /></button>
                {busy && <div className="auth-status" role="status" aria-live="polite"><span className="progress-track" aria-hidden="true" /><span>{restoring ? '저장된 로그인 정보를 확인하고 있어요.' : '가입 정보를 확인하고 있어요. 잠시만 기다려 주세요.'}</span></div>}
              </form>
              <p className="auth-switch"><span>이미 계정이 있나요?</span><a href={AUTH_ROUTES.login}>로그인</a></p>
            </>}
          </section>}
        </div>
        <footer className="auth-foot">Campus Planner · 나의 하루를, 나의 속도로.</footer>
      </main>
    </div>
  </>;
}
