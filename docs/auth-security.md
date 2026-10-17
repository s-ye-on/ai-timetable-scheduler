# JWT 및 Google OAuth 실행 안내 (T04)

## JWT Secret

- 서버 실행 환경에 `APP_JWT_SECRET`을 반드시 설정한다. 기본 Secret은 제공하지 않는다.
- 충분히 무작위인 전용 Secret을 비밀 관리 도구에서 생성·주입한다. HS256에 사용하는 원문 UTF-8 기준 최소 32바이트가 필요하다. 길이 검사만으로 키의 무작위성을 보장하지는 않는다.
- Secret 누락, 공백, 32바이트 미만, 알려진 `change-me` 예시 값은 서버 시작 시 거부한다. 오류 메시지에 Secret을 포함하지 않는다.
- 기존 HS256, issuer, access/refresh 구분과 만료 정책은 유지한다. Secret을 변경하면 이전 키로 발급한 토큰은 무효가 되므로 다시 로그인해야 한다.
- Secret이나 토큰을 소스, 실행 명령의 인수, 로그, 커밋에 넣지 않는다. 테스트의 전용 키는 실제 환경에서 사용하지 않는다.

기존 로컬 `src/main/resources/application.properties`를 사용하는 경우 `app.jwt.secret=${APP_JWT_SECRET}`로 설정한다. Google client secret도 `google.calendar.client-secret=${GOOGLE_CLIENT_SECRET}`처럼 비밀 주입을 사용하고 예시 기본값을 제거한다. 이 로컬 설정 파일은 Git 추적 대상이 아니다. 노출된 실제 인증 정보가 있었다면 배포 담당자가 외부 제공자에서 교체해야 한다.

## Google Calendar 연결

1. 로그인한 사용자가 기존 `/api/calendar/oauth/url`을 호출한다.
2. 서버는 세션 ID를 회전하고 256비트 무작위 state, 요청 사용자 ID, 5분 만료 시각을 서버 세션에 보관한다. 응답의 `authUrl` 구조는 유지한다.
3. 공개 callback은 같은 브라우저 세션의 state를 검증한 뒤 한 번만 소비한다. 사용자 ID는 callback 입력으로 받지 않는다.
4. 정상 state와 authorization code가 있을 때만 Google 토큰을 교환하고 해당 사용자의 연결 정보를 저장한다.

- 세션은 OAuth 요청 결합에만 사용하며 JWT 인증은 계속 STATELESS이다. 세션 쿠키만으로 보호 API를 호출할 수 없다.
- 세션은 COOKIE 추적, HttpOnly, SameSite=Lax를 사용한다. HTTPS 배포에서는 반드시 `SESSION_COOKIE_SECURE=true`를 설정한다. 로컬 HTTP 시연은 기본값 `false`를 사용한다. 프록시 배포도 실제 HTTPS와 쿠키 전달을 확인한다.
- 기존 same-origin 요청과 새 창 callback 흐름을 유지한다. 다른 브라우저, 쿠키 차단, 세션 유실, 만료 시에는 연결을 처음부터 다시 시작한다.
- 브라우저 세션당 가장 최근에 시작한 연결 요청 하나만 유효하다. 새 요청은 이전 요청을 대체한다.
- Google 인증 거절, code 누락, 토큰 교환 실패도 유효한 state를 소비한다. 같은 callback을 재전송하지 말고 새 연결 요청으로 재시도한다.
- `GOOGLE_CLIENT_SECRET`에는 현재 Client ID와 한 쌍인 Google OAuth Client Secret을 설정한다. 로컬 IntelliJ 실행 구성과 배포 서버의 실행 환경에 각각 등록하고, 변경 후 서버를 다시 실행한다. 실제 값은 채팅이나 로그에 공유하지 않는다.
- Client ID, Client Secret, Redirect URI가 비어 있거나 `${...}` 참조가 해결되지 않으면 Google 연결 요청을 `GOOGLE_OAUTH_CONFIGURATION_INVALID`(HTTP 503)로 거부한다. Calendar를 연결하지 않는 사용자의 서버 시작까지 차단하지는 않는다.
- 토큰 교환 실패의 HTTP 502는 애플리케이션 응답 상태다. 서버 로그의 `httpStatus`는 확인 가능한 Google 응답 상태이며, `error`에는 허용된 OAuth 오류 코드만 남긴다. `invalid_client`는 Client ID/Secret 짝과 설정을, `invalid_grant`는 만료·재사용된 code와 Redirect URI 등을 확인한다. `transport_error`는 통신 또는 응답 처리 중 HTTP 상태를 확인하지 못한 오류이며, HTTP 응답이 전혀 없었다는 뜻으로 단정하지 않는다. `unknown`은 분류할 수 없는 OAuth 오류다. 과거 오류 처리에서 버린 Google 응답은 사후 복구할 수 없다.
- 오류 설명 원문, 응답 본문, 예외 메시지·stack trace, Secret, code, token, state는 토큰 교환 진단 로그에 기록하지 않는다. Google SDK의 토큰 교환 HTTP 요청·응답 및 curl 로깅도 비활성화한다.
- 단일 서버의 메모리 세션을 전제로 한다. 서버 재시작으로 진행 중인 요청은 무효화된다. 여러 서버 운영 시 세션 공유와 분산 단일 소비 보장이 별도로 필요하다.
- 로그아웃만으로 이미 시작한 OAuth 요청을 취소하지는 않는다. 해당 요청은 최대 5분 동안 시작한 사용자에 결합되고 다른 사용자로 전환되지 않는다. 공용 브라우저에서는 연결 절차를 완료하거나 새 연결 요청을 시작한다.

## 검증 범위

인메모리 H2, 외부 OAuth 클라이언트 대역, 고정 Clock으로 계정별 저장 결과와 보안 우회·만료·재사용·동시 소비를 검증한다. 실제 Google 인증, 브라우저 쿠키의 리다이렉트 전달, 운영 HTTPS 및 제공자 설정은 실제 배포 환경에서 별도로 확인해야 한다.

근거: [Google의 서버 웹 OAuth 안내](https://developers.google.com/identity/protocols/oauth2/web-server), [OAuth 보안 권고 RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html), [Spring Boot 세션 쿠키 설정](https://docs.spring.io/spring-boot/4.0/reference/web/servlet.html).
