import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { LoginPage } from '@/features/auth/components/login-page';
import '@/styles/campus-theme.css';
import '@/styles/auth-layout.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><LoginPage /></StrictMode>,
);
