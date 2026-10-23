import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppRoutes } from '@/app/components/app-routes';
import '@/styles/campus-theme.css';
import '@/styles/auth-layout.css';
import '@/styles/app-layout.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><AppRoutes /></StrictMode>,
);
