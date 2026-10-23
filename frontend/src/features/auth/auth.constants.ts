// React 홈을 이식할 때 이 경로만 변경한다. 현재는 기존 화면을 사용한다.
export const AUTHENTICATED_HOME_PATH = '/demo.html';

export const AUTH_ROUTES = { login: '/login', signup: '/signup' } as const;

// TODO(oma-deferred): React 온보딩 구현 후 current를 onboarding으로 연결한다.
// 현재는 존재하는 플래너로만 이동하며 가짜 온보딩 화면을 제공하지 않는다.
export const REGISTRATION_DESTINATION = { onboarding: '/onboarding', current: AUTHENTICATED_HOME_PATH } as const;

// 기존 demo.html과 같은 출처에서 인증을 이어가기 위한 저장 키다.
export const AUTH_STORAGE_KEYS = {
  accessToken: 'demoAccessToken',
  refreshToken: 'demoRefreshToken',
  userName: 'demoUserName',
  taskId: 'demoCurrentTaskId',
} as const;
