import { useEffect } from 'react';
import { AUTH_ROUTES } from '@/features/auth/auth.constants';
import { LoginPage } from '@/features/auth/components/login-page';
import { SignupPage } from '@/features/auth/components/signup-page';

export function AuthRoutes() {
  const signup = window.location.pathname.replace(/\/$/, '') === AUTH_ROUTES.signup;
  useEffect(() => { document.title = `${signup ? '회원가입' : '로그인'} - Campus Planner`; }, [signup]);
  return signup ? <SignupPage /> : <LoginPage />;
}
