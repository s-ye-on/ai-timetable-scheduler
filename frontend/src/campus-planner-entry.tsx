import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthRoutes } from '@/features/auth/components/auth-routes';
import '@/styles/campus-theme.css';
import '@/styles/auth-layout.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><AuthRoutes /></StrictMode>,
);
