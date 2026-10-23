# Campus Planner React 로그인

Open Design의 `login.html` 디자인·Pretendard 폰트를 재사용한 Vite + React + TypeScript 화면입니다. 원본의 예시 계정 안내·가짜 인증 로직은 포함하지 않습니다. 회원가입·React 홈 화면은 아직 구현하지 않았습니다.

## 실행과 검증

Node.js 22.12 이상이 필요합니다.

```sh
cd frontend
npm ci
npm run dev
npm test
npm run build
```

- 로그인 화면: `http://127.0.0.1:5173/`
- 개발 서버와 미리보기는 `/api` 및 기존 화면 경로를 기본 `http://127.0.0.1:8080`으로 전달합니다.
- 다른 백엔드 주소: `BACKEND_PROXY_TARGET=http://127.0.0.1:8081 npm run dev`
- 실제 로그인에는 기존 Spring Boot 서버가 필요합니다. DB·JWT 설정은 기존 서버 설정을 그대로 사용합니다. 프론트 실행만으로 서버나 DB를 기동하지 않습니다.
- 프론트에 JWT Secret·Google Secret·OpenAI API 키를 설정하지 않습니다. `BACKEND_PROXY_TARGET`은 프록시 대상 주소이며 비밀값이 아닙니다.

## 인증과 후속 연결

- `POST /api/auth/login` 성공 후 `/api/auth/me`로 실제 사용자 정보를 확인합니다.
- 기존 `demo.html`과 같은 토큰 저장 키를 사용합니다. 비밀번호는 저장하지 않습니다. 기존 정책과 같이 토큰은 localStorage에 저장되므로 XSS에 대한 보호가 필요합니다.
- 인증 요청의 401은 갱신 후 한 번만 재요청합니다. 동시 갱신은 하나로 합치며 갱신 거절 시 인증과 이전 작업 식별자를 정리합니다. 통신 장애는 인증 거절과 구분합니다.
- 성공 목적지는 `src/features/auth/auth.constants.ts`의 `AUTHENTICATED_HOME_PATH`입니다. 현재 존재하는 `/demo.html`로 이동하며, React 홈 이식 시 경로를 교체합니다. `/tasks`, `/timetable-slots`, `/preferences`, `/recommendations`도 프록시해 기존 화면이 같은 출처에서 이어집니다.
- 회원가입은 준비 중인 비활성 버튼으로 표시합니다. 다음 단계에서 실제 회원가입 화면을 연결합니다.
- `/`와 `/login`은 현재 같은 로그인 화면을 제공합니다. 인증 만료 안내는 `/login?reason=expired`로 표시합니다.

## 배포 경계

`npm run build`는 `frontend/dist`만 생성합니다. Spring Boot 정적 자원 복사나 기존 Security 설정 변경은 수행하지 않습니다. 운영 배포 시 정적 파일·SPA 경로와 백엔드 `/api` 등을 같은 출처로 제공하는 프록시 설정이 필요합니다. `vite preview`는 운영 서버가 아닙니다.

## 검증 범위

Vitest·React Testing Library에서 격리된 HTTP 응답으로 입력 검증, 로그인, 실패·통신 장애, 저장·복원, 만료, 토큰 회전, 중복 제출을 검증합니다. 일반 테스트는 실제 계정·OpenAI·Google API를 호출하지 않습니다.
