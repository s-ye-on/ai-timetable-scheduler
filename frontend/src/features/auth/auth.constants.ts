// React 홈을 이식할 때 이 경로만 변경한다. 현재는 기존 화면을 사용한다.
export const AUTHENTICATED_HOME_PATH = '/demo.html';

// 기존 demo.html과 같은 출처에서 인증을 이어가기 위한 저장 키다.
export const AUTH_STORAGE_KEYS = {
  accessToken: 'demoAccessToken',
  refreshToken: 'demoRefreshToken',
  userName: 'demoUserName',
  taskId: 'demoCurrentTaskId',
} as const;
