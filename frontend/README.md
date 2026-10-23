# Campus Planner React 프론트엔드

Open Design의 `login.html`·`signup.html`·`index.html` 공통 레이아웃과 Pretendard 폰트를 재사용한 Vite + React + TypeScript 화면입니다. 예시 계정·예시 가입 안내·가짜 인증 로직은 포함하지 않습니다. 회원가입 화면에서만 소개 패널 하단 문구를 제거했습니다. M1 공통 인증, M2 시간표 관리, M3 선호·Calendar·온보딩을 완료했고 홈의 실제 기능은 후속 단계에서 연결합니다.

## 실행과 검증

Node.js 22.12 이상이 필요합니다.

```sh
cd frontend
npm ci
npm run dev
npm test
npm run build
```

- 로그인 화면: `http://127.0.0.1:5173/login`
- 회원가입 화면: `http://127.0.0.1:5173/signup`
- 보호된 공통 앱: `http://127.0.0.1:5173/#home`. 서버 인증 확인 후 공통 레이아웃과 준비 중 안내를 표시합니다.
- 시간표 관리: `http://127.0.0.1:5173/#timetable`. 인증된 사용자의 실제 수업 API를 사용합니다.
- 선택 온보딩: `http://localhost:5173/#onboarding/1`. 일반 설정: `http://localhost:5173/#onboarding/2?mode=manage&returnTo=home`.
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
- `/login`, `/signup`은 공개 인증 화면이며 `/`의 hash 앱 경로는 보호됩니다. 인증이 없으면 로그인 화면으로 이동합니다. 인증 만료 안내는 `/login?reason=expired`로 표시합니다.

## M1 공통 앱 경계

- `/`의 `#home`, `#planner?taskId=123`, `#timetable`, `#onboarding/1~3`을 해석합니다. 일반 선호 관리의 `mode=manage&returnTo=home` 문맥도 보존합니다. 외부 복귀 URL은 허용하지 않습니다.
- `AppRoutes`가 URL과 인증 세대를 구독하고 `AppShell`은 PC 사이드바·모바일 상단바/하단 탐색·프로필 메뉴를 제공합니다. 라우팅·상태 라이브러리는 추가하지 않았습니다.
- 보호 경로는 `/api/auth/me` 확인 전 앱 데이터를 표시하지 않습니다. `401`은 기존 갱신 흐름을 재사용하며, `403`·통신 장애·서버 장애는 세션 거절과 구분해 다시 확인할 수 있습니다.
- `AuthClient.authenticatedRequest`의 `AuthApiError`는 안전한 안내와 `status/code/fieldErrors`를 전달합니다. 서버 오류 원문·비밀번호·토큰을 로그에 출력하지 않습니다.
- 로그아웃은 `POST /api/auth/logout`을 사용하고 로컬 인증·이전 작업 정보를 정리합니다. 서버 처리 확인에 실패하면 로그인 화면에서 그 사실을 안내합니다. 기존 서버 정책은 refresh token 폐기이며 access token 즉시 폐기를 추가한 것은 아닙니다.
- 다른 탭의 인증 변경·BFCache 복귀는 앱 상태를 초기화하고 서버를 다시 확인합니다. 같은 사용자 재확인은 기존 작업 ID를 유지하며 실제 사용자 변경·로그아웃은 이전 보조 정보를 정리합니다. 늦은 응답이 새 사용자를 덮지 않도록 세대를 확인합니다.
- 인증 화면 스타일과 `.campus-app` 스타일을 분리합니다. `M4~M6`의 본문 기능은 준비 중으로 표시하고 기존 `/demo.html`로 안내합니다. 로그인·회원가입 성공 목적지는 M7 전까지 변경하지 않습니다.
- 단계별 상태와 검증 근거: [React 이식 계획](../docs/react-migration-plan.md).

## M2 시간표·수업 색상

- `/#timetable`의 `TimetablePage`가 기존 `AuthClient`를 주입한 `TimetableApi`를 사용합니다. `GET/POST /timetable-slots`, `GET/PUT/DELETE /timetable-slots/{id}`를 연결했으며 새 API·DB 변경은 없습니다.
- PC 월~금 주간표와 전체 7요일 수업 목록, 모바일 7요일별 목록, 등록·수정 폼·상세 조회·삭제 확인을 제공합니다. 토·일 수업은 숨기지 않으며 모바일 요일 선택 줄만 가로로 스크롤할 수 있습니다.
- 색상은 BLUE/MINT/PEACH/LAVENDER/ROSE/LEMON/LIME/AQUA/APRICOT/SLATE를 서버에 전달합니다. 같은 과목도 슬롯 ID별로 독립 관리하고 범례는 실제 과목명·색상 조합입니다. 수업이 없으면 범례를 표시하지 않습니다.
- 이름 필수/최대100자, 장소 선택/최대100자, 시작<종료를 검증합니다. 수업에 추천의 30분 간격·가용 시간 제약은 적용하지 않으며 서버 충돌 오류도 처리합니다. 기존 초 포함 시각은 편집값에서 임의로 절삭하지 않습니다.
- 초기 조회 실패는 빈 시간표로 표시하지 않습니다. 재조회 실패는 마지막 확인 목록과 오류를 함께 표시하고 변경을 차단합니다. 저장·삭제 결과가 불확실하면 자동 재전송하지 않고 현재 수업을 다시 조회하도록 안내합니다.
- 폼·주간표·워크스페이스는 M3 온보딩에서 재사용합니다. 추천 화면은 아직 연결하지 않았으며 로그인·회원가입 성공 목적지는 `/demo.html`로 유지합니다.
- 검증: 프론트 114개, Java 572개 통과 및 프론트 빌드 성공. 격리된 H2 메모리 DB의 실제 Spring Boot로 10색 CRUD·접근 제어·충돌과 브라우저 등록/수정/삭제/새로고침을 확인했습니다. 기존 H2 파일과 OpenAI/Google API는 사용하지 않았습니다.

## M3 선호·Calendar·온보딩

- `/#onboarding/1~3`에서 수업 등록, 선호 설정, Calendar 연결을 각각 건너뛸 수 있습니다. 수업 단계는 M2 컴포넌트를 재사용하며 가짜 초기 수업을 추가하지 않습니다. 홈은 아직 M4 준비 중 안내입니다.
- 선호는 기존 `GET/PUT/DELETE /preferences`를 사용합니다. 기본값은 서버 응답으로만 복원하며 최소 여유 시간은 0 이상 5분 단위입니다. 기존 선호가 있으면 건너뛰어도 변경하지 않습니다.
- `mode=manage&returnTo=home|planner|timetable`에서는 저장/취소 후 원래 화면으로 돌아갑니다. 새로고침 후에도 URL 문맥을 유지하고 Calendar 온보딩으로 강제 이동하지 않습니다.
- Calendar는 `/api/calendar/oauth/url`로 새 인증 창을 열고 창 닫힘·복귀·수동 확인 시 `/api/calendar/status`를 다시 조회합니다. 팝업 차단, 미연결, 조회 실패는 구분합니다. callback은 기존 서버의 텍스트 응답을 유지합니다.
- OAuth 세션 쿠키를 위해 **프론트와 callback의 hostname·프로토콜을 일치**시켜야 합니다. 기본 callback이 `http://localhost:8080`이면 프론트도 `http://localhost:5173`을 사용하세요. 포트 차이는 허용하지만 `127.0.0.1`과 `localhost`는 혼용하지 않습니다. 다른 호스트로 바꾸면 그 호스트에서 다시 로그인해야 합니다.
- status의 연결됨은 저장된 연결 정보의 존재이며, 외부 Calendar 조회 성공을 보장하지 않습니다. 미연결도 추천까지 가능하고 확정은 연결이 필요합니다. 연결 전 후보는 최신 검증이 필요하다는 정책을 안내합니다.
- 검증: 프론트 157개·Java 572개 통과, 프론트 빌드 성공. 격리 메모리 DB에서 실제 선호 API·OAuth 프록시 쿠키·거절 callback을 확인하고 PC 1440px·모바일 390px 화면을 확인했습니다. 연결 성공·실패 상태와 창 동작은 Stub 테스트이며 실제 Google 계정 동의·토큰 교환은 실행하지 않았습니다.

## 배포 경계

`npm run build`는 `frontend/dist`만 생성합니다. Spring Boot 정적 자원 복사나 기존 Security 설정 변경은 수행하지 않습니다. 운영 배포 시 정적 파일·SPA 경로와 백엔드 `/api` 등을 같은 출처로 제공하는 프록시 설정이 필요합니다. `vite preview`는 운영 서버가 아닙니다.

## 검증 범위

Vitest·React Testing Library에서 격리된 HTTP 응답으로 입력 검증, 로그인·회원가입·자동 로그인, 이메일 중복, 가입 후 인증 확인 복구, 실패·통신 장애, 저장·복원, 만료, 토큰 회전, 중복 제출을 검증합니다. 일반 테스트는 실제 계정·OpenAI·Google API를 호출하지 않습니다.
