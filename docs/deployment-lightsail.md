# Campus Planner — Lightsail 배포 설정 준비

## 범위와 현재 상태

독립된 Ubuntu 24.04 / RAM 2GB Lightsail에서 Docker Compose로 실행하기 위한 설정이다.
기존 Horse 서버·컨테이너·네트워크·인증서는 재사용하지 않는다.
이 문서의 명령은 운영 서버에서 아직 실행하지 않았다. AWS 생성, DuckDNS 변경, 인증서 발급,
Google Cloud 설정은 후속 단계에서 별도로 진행한다. 전시 서버는 신규 H2 DB를 사용하며,
신규 DB 생성·백업·복원·Bind Mount 영속성 검증은 2단계에서 진행한다.
기존 MacBook H2 DB는 변경·삭제하거나 서버로 이전하지 않고 그대로 보존한다.

## 구성

- `web`: React 운영 빌드 + Nginx. HTTPS 활성화 후 외부 포트는 80/443뿐이다.
- `app`: Java 21 JRE + Spring Boot `prod` 프로파일. 8080은 Compose 내부에서만 접근한다.
- H2: 호스트 `/srv/campus-planner/data` → 컨테이너 `/var/lib/campus-planner/data`.
  파일은 `timetable-scheduler.mv.db`이며 이미지·컨테이너 쓰기 계층에 넣지 않는다.
- app은 단일 인스턴스다. `container_name`으로 다중 scale을 방지하고 기존 H2 파일 잠금도 유지한다.
- 로컬 IDE/Vite 실행은 기존 설정 그대로다. 로컬 `application.properties`는 이미지에서 제외한다.

## 사전 준비

Docker Engine + Compose V2, Java 21, 프론트 빌드에는 Node 22.12 이상이 필요하다.
Dockerfile의 Node 22 계열은 `package-lock.json`을 `npm ci`로 설치한다.
빌드는 메모리가 소모되므로 2GB 서버에서 app 실행과 동시에 병렬 빌드하지 않는 것을 권장한다.
이미지는 로컬/CI에서 대상 서버의 아키텍처에 맞게 빌드해 전달해도 된다.

독립된 신규 서버에서 다음 경로를 **직접 확인하고 생성**한다. 전시용 데이터 디렉터리는
신규 DB 생성용 빈 디렉터리로 준비하며, 기존 MacBook DB를 여기에 복사하지 않는다.

```sh
sudo install -d -m 0700 -o 10001 -g 10001 /srv/campus-planner/data
sudo install -d -m 0700 /srv/campus-planner/config /srv/campus-planner/certs
sudo install -d -m 0755 /srv/campus-planner/acme
sudo install -m 0600 .env.example /srv/campus-planner/config/runtime.env
```

환경파일은 관리자가 직접 편집한다. `--env-file` 경로는 아래 모든 명령에서 동일하게 사용한다.
파일이 root 소유라면 읽기 권한이 있는 관리자 계정으로 명령을 실행한다.
DB 파일·디렉터리는 컨테이너의 UID/GID `10001:10001`이 읽고 쓸 수 있어야 한다.
실행 중인 로컬/운영 DB 파일을 단순 복사하지 않는다.

### 환경변수

| 변수 | 값/역할 |
|---|---|
| `APP_JWT_SECRET` | 기존 키를 유지하거나 `openssl rand -hex 32`로 한 번 생성한 키. 변경하면 기존 JWT가 무효화됨 |
| `OPENAI_API_KEY` | 실제 OpenAI 키. 환경파일에만 저장 |
| `GOOGLE_CALENDAR_CLIENT_ID` | 기존 코드에서 사용하는 Google OAuth Client ID 변수명 |
| `GOOGLE_CLIENT_SECRET` | 같은 OAuth Client의 Secret |
| `GOOGLE_CALENDAR_REDIRECT_URI` | `https://campusplanner.duckdns.org/api/calendar/oauth/callback` |
| `H2_DATA_DIR` | 미리 존재해야 하는 **호스트 절대경로**. 기본 `/srv/campus-planner/data` |
| `H2_DB_USERNAME`, `H2_DB_PASSWORD` | 신규 전시 DB 생성과 이후 기동·복원에 동일하게 사용할 계정. 기본값은 `sa` / 빈 비밀번호 |
| `ACME_WEBROOT` | 미리 생성한 인증서 검증 파일용 호스트 절대경로 |
| `TLS_CERT_DIR` | HTTPS 활성화 시 `fullchain.pem`, `privkey.pem`을 제공하는 호스트 경로 |
| `COMPOSE_PROJECT_NAME` | 기본 `campus-planner`. Horse의 프로젝트명·컨테이너명을 사용하지 않음 |
| `IMAGE_TAG` | 배포 이미지 구분. 기본 `local`; 실제 배포에서는 커밋 해시 등 고정 태그 권장 |
| `OPENAI_MODEL`, `OPENAI_BASE_URL` | 기존 `gpt-4o-mini`, `https://api.openai.com/v1` 유지 |
| `APP_MEMORY_LIMIT`, `WEB_MEMORY_LIMIT` | 기본 `1280m`, `128m`. 실서버 메모리 측정 후 조정 |
| `JAVA_TOOL_OPTIONS` | 기본 heap 128~768MB, metaspace 최대 256MB. 비밀값을 넣지 않음 |
| `WEB_BIND_ADDRESS`, `WEB_HTTP_PORT`, `WEB_HTTPS_PORT` | 운영 `0.0.0.0`, `80`, `443`. 격리 검증만 loopback/임시 포트 사용 |

배포 설정이 새로 도입하는 `H2_DATA_DIR`·메모리·포트 변수는 Compose 전용이다.
실제 애플리케이션에는 기존 JWT/OpenAI/Google 변수명과 Spring datasource 설정으로 전달한다.
`VITE_` 환경변수, Docker build ARG, Dockerfile, 프론트 번들에는 비밀값을 넣지 않는다.

Compose Secrets는 파일을 `/run/secrets/`에 제공하지만 현재 Java 설정이 `_FILE` 변수를 자동으로
읽지는 않는다. 이번에는 인증 구조를 바꾸지 않고 기존 환경변수 주입을 유지한다.
Docker 관리자 권한으로 환경변수를 볼 수 있으므로 서버·Docker 접근 권한도 제한한다.
실제 환경파일로 `docker compose config` 전체를 출력하거나 로그·채팅에 붙이지 않는다.
`config --quiet`로만 검증한다. `.env*`, `*.env`, 개인 키·인증서는 Git·빌드 컨텍스트에서 제외한다.

## 빌드와 설정 검사

프로젝트 루트에서 실행한다. 정상 빌드는 외부 OpenAI/Google API를 호출하지 않는다.
`clean`과 실제 LLM 평가 태스크는 사용하지 않는다.

```sh
./gradlew test build --no-daemon
npm --prefix frontend test
npm --prefix frontend run build
docker compose --env-file /srv/campus-planner/config/runtime.env config --quiet
docker compose --env-file /srv/campus-planner/config/runtime.env build
```

두 Dockerfile 모두 저장소 루트를 빌드 컨텍스트로 사용한다. 백엔드는 Docker 내부에서
Gradle Wrapper로 새 JAR을 만들며 로컬 `build/`의 JAR을 복사하지 않는다.
최종 app 이미지는 JRE, app JAR, 최초 생성용 기존 H2 드라이버·스키마, 실행 스크립트만 포함한다.
프론트 최종 이미지에는 Nginx와 운영 정적 파일·프록시 설정만 포함한다.
Nginx는 `resolve`를 지원하는 1.27.3 이상이 필요하며, 이번 빌드에서는 1.30.6을 확인했다.
기본 이미지 태그는 계열 태그이므로 Docker 이미지 자체의 완전한 재현성이 필요하면 검증한
digest로 고정한다. `IMAGE_TAG`는 생성한 서비스 이미지를 구분하며 기본 이미지 digest와는 별개다.

## H2 기동 보호와 최초 생성

정상 운영 기동은 절대경로 URL에 `IFEXISTS=TRUE`를 사용한다. 디렉터리가 없으면
`create_host_path:false`가 막고, 빈 디렉터리·파일 부재·권한 오류는 entrypoint와 H2가 막는다.
잘못된 경로에서 빈 DB를 조용히 만들지 않는다. 기본 데이터 파일 이름도 고정한다.
`ddl-auto=validate`, 기존 H2 dialect·식별자 설정·비파괴 SQL 초기화는 유지한다.

전시 서버는 기존 MacBook DB를 이전하지 않고, **2단계에서 `init-empty-db`로 신규 DB를 생성한다.**
아래 명령은 기존 데이터가 없는 전시용 디렉터리에서 최초 생성할 때만 사용한다.
app을 실행하지 않은 상태여야 하며, 디렉터리에 파일이 하나라도 있으면 명령은 거절한다.
신규 DB 생성 이후에는 이 명령을 다시 사용하지 않고, 백업·복원과 영속성을 검증한다.

```sh
docker compose --env-file /srv/campus-planner/config/runtime.env run --rm --no-deps app init-empty-db
```

이 명령은 앱과 같은 버전의 H2 드라이버로 기존 `schema-h2.sql`만 실행하고 종료한다.
앱 서버나 외부 API를 실행하지 않는다. 생성 실패 시 남은 파일을 자동 삭제하지 않으므로 상태를
확인해야 한다. 기존 DB가 있으면 정상 시작에서 기존 비파괴 SQL과 JPA 검증을 수행한다.

## 인증서 없는 HTTP 준비 단계

```sh
docker compose --env-file /srv/campus-planner/config/runtime.env up -d
docker compose --env-file /srv/campus-planner/config/runtime.env ps
```

기본 HTTP 설정은 `/healthz`, `/.well-known/acme-challenge/`만 제공하며 다른 요청은 503이다.
이 단계에서 서비스 로그인·OAuth·React 이용을 검증 완료했다고 판단하지 않는다.
app healthcheck는 기존 `/demo.html` 응답을 사용하며, web의 `/healthz`는 Nginx 자체 상태다.
실제 DB가 아직 준비되지 않았다면 app은 시작하지 않으며 web도 app의 정상 기동을 기다린다.

## HTTPS 활성화 — 후속 단계

1. DuckDNS를 독립 Lightsail 공인 IP에 연결하고 AWS 방화벽은 80/443만 공개한다.
   SSH는 관리자 접근으로 제한한다. 8080과 H2 콘솔은 공개하지 않는다.
2. 별도 단계에서 인증서를 발급하고 `TLS_CERT_DIR`에 `fullchain.pem`, `privkey.pem`을 준비한다.
   호스트에 인증서의 실제 내용을 배치한다. 디렉터리 밖을 가리키는 심볼릭 링크는 피한다.
3. Google Cloud에 위 HTTPS callback을 정확히 등록하고 Testing 상태의 테스트 사용자도 확인한다.
4. 인증서를 확인한 후 아래 명령을 실행한다. 이 문서/Compose는 인증서 발급·갱신을 자동화하지 않는다.

```sh
docker compose --env-file /srv/campus-planner/config/runtime.env -f compose.yaml -f compose.https.yaml config --quiet
docker compose --env-file /srv/campus-planner/config/runtime.env -f compose.yaml -f compose.https.yaml up -d
docker compose --env-file /srv/campus-planner/config/runtime.env -f compose.yaml -f compose.https.yaml exec web nginx -t
```

HTTPS 설정은 HTTP를 정해진 HTTPS 도메인으로 리다이렉트한다. Nginx가 전달 헤더를 덮어쓰고
Spring Boot가 이를 인식한다. 세션 쿠키는 Secure + HttpOnly + SameSite=Lax를 유지한다.
OAuth URL 발급과 callback은 같은 HTTPS 호스트로 접근해야 한다. callback은 기존 서버의
완료 문구를 반환하며 React는 `/api/calendar/status`를 다시 조회한다. 서비스 로그인과 Calendar
연결은 별개다. 인증서 갱신 파일 교체·`nginx -t`·reload 및 갱신 자동화는 후속 운영 작업이다.

## 상태 확인과 운영 주의

```sh
docker compose --env-file /srv/campus-planner/config/runtime.env ps
docker compose --env-file /srv/campus-planner/config/runtime.env logs --tail=100 app web
docker compose --env-file /srv/campus-planner/config/runtime.env restart app
```

HTTPS 운영 이후 명령에는 항상 `-f compose.yaml -f compose.https.yaml`을 동일하게 추가한다.
이미지 교체는 같은 환경파일·호스트 DB 경로로 `up -d --build`한다. `restart`만으로는 새 이미지를
적용하지 않는다. `docker compose down`은 컨테이너를 제거해도 bind 경로 파일을 삭제하지 않지만,
운영 DB를 보호하기 위해 불필요한 down/삭제/볼륨 정리 명령은 사용하지 않는다.
DB 디렉터리 삭제, `ddl-auto=create/update`, 파일 잠금 해제, app 다중 실행은 금지한다.
DB 백업을 포함한 디렉터리는 호스트 장애 대비 서버 밖에도 보관하고 복원을 연습해야 한다.

Nginx는 query string·Authorization·요청 본문을 access log에 남기지 않으며 OAuth callback의
프록시 오류 로그도 차단한다. TLS 요청의 API 오류는 SPA HTML로 바꾸지 않는다.
기존 API 경로 `/api`, `/tasks`, `/timetable-slots`, `/preferences`, `/recommendations`와
`/demo.html`을 유지하고, `/h2-console`·`/actuator`·숨김 파일 접근은 차단한다.

## 현재 단계의 격리 검증

- 기존 Java·React 회귀 테스트와 운영 빌드, Docker 이미지 빌드, Compose 설정 검사를 수행한다.
- 실제 H2 파일 대신 임시 디렉터리·신규 DB·검증용 비밀값을 사용한다.
- 기본 bootstrap은 HTTP 인증을 차단하므로 로컬 HTTP 기능 검증에만 별도 임시 server 설정으로
  `routes.conf`를 include하고, `127.0.0.1`의 임시 포트에 바인딩한다. 운영 파일을 HTTP 서비스용으로
  변경하지 않는다. 테스트가 끝나면 해당 검증 프로젝트의 컨테이너·네트워크만 종료한다.
- 실제 API 키 없이 확인한 회원가입·JWT·조회·프록시 결과는 OpenAI 분석/Google 연동 성공이 아니다.
- 실제 인증서·도메인·Google 동의/토큰 교환, 2GB Lightsail의 부하·메모리는 아직 미검증이다.

2단계에서는 별도 전시용 경로에서 `init-empty-db`로 신규 H2를 생성하고, 해당 신규 DB의
백업·복원·Bind Mount 영속성을 검증한다. 검증용 계정·시간표·색상·Task·추천·이벤트 ID가
백업 복원과 컨테이너 재생성 후에도 유지되는지 확인한다. 실제 외부 이벤트 등록은 별도 승인 없이
수행하지 않는다. 기존 MacBook DB의 프로세스·파일·데이터는 이 절차의 대상으로 삼지 않는다.
아래 1단계 격리 검증 기록은 2단계 전시 DB 검증을 완료했다는 의미가 아니다.

## 1단계 실행 결과 (2026-10-27)

| 검증 | 실제 결과 |
|---|---|
| Java 테스트·백엔드 빌드 | `./gradlew test build --no-daemon` 성공. 50개 클래스 / 605개 테스트, 실패·오류·skip 0 |
| React 테스트 | 19개 파일 / 259개 테스트 통과 |
| 프론트 운영 빌드 | TypeScript 검사·Vite 운영 빌드 성공 |
| Docker 이미지 | app/web multi-stage 빌드 성공. 실제 패키징 JAR에서 로컬 `application.properties` 부재 확인 |
| Compose | bootstrap 및 HTTPS overlay 설정 검사. HTTPS 병합 시 80/443과 인증서 mount 교체 확인 |
| Nginx | bootstrap·격리 HTTP 라우팅 설정의 `nginx -t` 통과. HTTPS 인증서 로드는 미검증 |
| 임시 H2 | 잘못된 호스트 경로의 자동 생성 없음, 빈 DB 기동 거절, 신규 생성 성공, 반복 초기화 거절 |
| 격리 API | 실제 회원가입·JWT·선호/Task/시간표 조회·색상 저장, SPA 경로·API 오류·콘솔 차단 확인 |
| OAuth 관련 | URL 생성·Secure/HttpOnly/Lax 쿠키·잘못된 state 차단·callback 로그 제외만 확인. Google 호출 없음 |
| 재생성 | 같은 Bind Mount로 app 강제 재생성 후 사용자·JWT·수업 색상 유지 및 Nginx 재연결 확인 |

초기 이미지 검사에서 로컬 설정 파일의 포함을 발견해, 리소스 명시 복사와 최종 제외 규칙으로
수정한 뒤 다시 빌드·검증했다. 최종 이미지의 H2 버전은 기존과 같은 2.4.240이다.
검증은 임시 디렉터리와 고유 Compose 프로젝트의 loopback 임시 포트에서만 수행했고,
검증 컨테이너·네트워크는 종료했다. 기존 로컬 DB를 열거나 복사하지 않았다.
격리 app의 유휴 순간 메모리는 약 323MiB였으며 실제 Lightsail 2GB의 부하 검증 결과가 아니다.
호스트·이미지 아키텍처도 실서버와 맞춰야 한다(`uname -m`: x86_64 → linux/amd64,
aarch64 → linux/arm64). 현재 로컬 결과만으로 다른 아키텍처의 실행을 보장하지 않는다.

설정 계약 참고: [Compose Bind Mount](https://docs.docker.com/reference/compose-file/services/#volumes),
[Spring Boot 프록시 전달 헤더](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server),
[H2 IFEXISTS](https://h2database.com/html/features.html#database_only_if_exists).
