export const AUTHENTICATED_HOME_PATH = '/#home';

export const AUTH_ROUTES = { login: '/login', signup: '/signup' } as const;

export const REGISTRATION_DESTINATION = { onboarding: '/#onboarding/1', current: AUTHENTICATED_HOME_PATH } as const;

// 기존 demo.html과 같은 출처에서 인증을 이어가기 위한 저장 키다.
export const AUTH_STORAGE_KEYS = {
  accessToken: 'demoAccessToken',
  refreshToken: 'demoRefreshToken',
  userName: 'demoUserName',
  taskId: 'demoCurrentTaskId',
} as const;
