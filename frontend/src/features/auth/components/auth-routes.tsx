import { useEffect } from 'react';
import { AUTH_ROUTES } from '@/features/auth/auth.constants';
import { LoginPage } from '@/features/auth/components/login-page';
import { SignupPage } from '@/features/auth/components/signup-page';
import { authClient } from '@/features/auth/api/auth-client';
import type { AuthClient } from '@/features/auth/api/auth-client';

export function AuthRoutes({ client = authClient, navigate }: { client?: AuthClient; navigate?: (path: string) => void }) {
  const signup = window.location.pathname.replace(/\/$/, '') === AUTH_ROUTES.signup;
  useEffect(() => { document.title = `${signup ? '회원가입' : '로그인'} - Campus Planner`; }, [signup]);
  return signup ? <SignupPage client={client} navigate={navigate} /> : <LoginPage client={client} navigate={navigate} />;
}
