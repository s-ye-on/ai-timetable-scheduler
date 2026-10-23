# Campus Planner React 인증 화면

Open Design의 `login.html`·`signup.html` 디자인과 Pretendard 폰트를 재사용한 Vite + React + TypeScript 화면입니다. 예시 계정·예시 가입 안내·가짜 인증 로직은 포함하지 않습니다. 회원가입 화면에서만 소개 패널 하단 문구를 제거했습니다. React 온보딩·홈 화면은 아직 구현하지 않았습니다.

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
- 회원가입 화면: `http://127.0.0.1:5173/signup`
- 개발 서버와 미리보기는 `/api` 및 기존 화면 경로를 기본 `http://127.0.0.1:8080`으로 전달합니다.
- 다른 백엔드 주소: `BACKEND_PROXY_TARGET=http://127.0.0.1:8081 npm run dev`
- 실제 로그인에는 기존 Spring Boot 서버가 필요합니다. DB·JWT 설정은 기존 서버 설정을 그대로 사용합니다. 프론트 실행만으로 서버나 DB를 기동하지 않습니다.
- 프론트에 JWT Secret·Google Secret·OpenAI API 키를 설정하지 않습니다. `BACKEND_PROXY_TARGET`은 프록시 대상 주소이며 비밀값이 아닙니다.

## 인증과 후속 연결

- `POST /api/auth/login` 성공 후 `/api/auth/me`로 실제 사용자 정보를 확인합니다.
- `POST /api/auth/register`의 HTTP 201 응답에서 받은 JWT로 자동 로그인하고 `/api/auth/me`를 조회합니다. 로그인 API를 다시 호출하지 않으며 토큰 검증·저장·갱신 처리는 기존 `AuthClient`를 공유합니다.
- 회원가입은 이름·이메일 필수/최대 30자, 비밀번호 필수/8~100자를 검증합니다. `CONFLICT_EMAIL`/HTTP 409와 서버 필드 오류를 입력에 연결합니다. 비밀번호는 공백을 임의 제거하거나 저장하지 않습니다.
- 계정 생성 성공 후 사용자 조회·토큰 저장이 실패하면 재가입 대신 로그인 상태 재확인/로그인 경로를 제공합니다. 응답 유실은 성공으로 단정하거나 자동 재전송하지 않고 가입 여부 확인을 안내합니다.
- 기존 `demo.html`과 같은 토큰 저장 키를 사용합니다. 비밀번호는 저장하지 않습니다. 기존 정책과 같이 토큰은 localStorage에 저장되므로 XSS에 대한 보호가 필요합니다.
- 인증 요청의 401은 갱신 후 한 번만 재요청합니다. 동시 갱신은 하나로 합치며 갱신 거절 시 인증과 이전 작업 식별자를 정리합니다. 통신 장애는 인증 거절과 구분합니다.
- 성공 목적지는 `src/features/auth/auth.constants.ts`의 `AUTHENTICATED_HOME_PATH`입니다. 현재 존재하는 `/demo.html`로 이동하며, React 홈 이식 시 경로를 교체합니다. `/tasks`, `/timetable-slots`, `/preferences`, `/recommendations`도 프록시해 기존 화면이 같은 출처에서 이어집니다.
- `/login`과 `/signup`을 링크로 연결합니다. 로그인 화면의 기존 인증·이동 정책은 유지합니다.
- 회원가입 성공 후 자동 로그인 완료 화면에서 **플래너로 이동**을 선택하면 현재 존재하는 `/demo.html`로 이동합니다. 아직 없는 온보딩으로 자동 이동하지 않습니다. `REGISTRATION_DESTINATION`의 `onboarding`은 후속 React 온보딩 경로(`/onboarding`), `current`는 현재 임시 목적지입니다. 온보딩 구현 후 `current`를 연결하고 성공 안내·버튼 문구를 갱신합니다.
- 기존 로그인 세션으로 회원가입에 접속하면 서버 확인 후 기존 플래너 이동을 제공하며 계정을 다시 생성하지 않습니다. 새로고침 시에도 기존 JWT 확인·갱신 구조로 인증을 복원합니다.
- `/`와 `/login`은 현재 같은 로그인 화면을 제공합니다. 인증 만료 안내는 `/login?reason=expired`로 표시합니다.

## 배포 경계

`npm run build`는 `frontend/dist`만 생성합니다. Spring Boot 정적 자원 복사나 기존 Security 설정 변경은 수행하지 않습니다. 운영 배포 시 정적 파일·SPA 경로와 백엔드 `/api` 등을 같은 출처로 제공하는 프록시 설정이 필요합니다. `vite preview`는 운영 서버가 아닙니다.

## 검증 범위

Vitest·React Testing Library에서 격리된 HTTP 응답으로 입력 검증, 로그인·회원가입·자동 로그인, 이메일 중복, 가입 후 인증 확인 복구, 실패·통신 장애, 저장·복원, 만료, 토큰 회전, 중복 제출을 검증합니다. 일반 테스트는 실제 계정·OpenAI·Google API를 호출하지 않습니다.
