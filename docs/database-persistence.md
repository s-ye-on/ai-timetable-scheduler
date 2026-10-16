# T01: H2 데이터 보존

## 실행 정책

H2 파일 DB와 기존 Entity·Repository 구조를 유지한다. DB 기본 경로도 `jdbc:h2:file:./data/timetable-scheduler;MODE=MySQL;DATABASE_TO_UPPER=false`로 유지한다.

일반 실행은 다음 순서로 처리한다.

1. `db/schema-h2.sql`의 `CREATE TABLE IF NOT EXISTS`로 없는 테이블만 생성한다.
2. Hibernate의 `ddl-auto=validate`로 현재 Entity와 스키마의 호환성을 검사한다.
3. 호환되지 않으면 시작을 중단한다. 기존 테이블을 삭제하거나 자동 수정하지 않는다.

빈 DB는 첫 실행에 초기 스키마가 만들어지고, 같은 DB로 재시작하면 기존 테이블과 데이터가 유지된다. 초기 스키마에는 DROP·ALTER·DELETE·TRUNCATE·초기 데이터 삽입이 없다. 기존 DB에 없는 테이블은 추가될 수 있다. 이 SQL은 이후 모델 변경을 자동으로 적용하는 마이그레이션 도구가 아니다.

`create`/`create-drop`은 파일 DB에서 사용하지 않는다. `update`도 자동 스키마 변경을 허용하므로 일반 실행 정책으로 사용하지 않는다. `none` 대신 `validate`를 사용해 누락·타입 불일치를 시작 단계에서 확인한다. 다만 `validate`가 모든 제약·길이·인덱스를 검사하는 것은 아니다.

기존 URL의 `DATABASE_TO_UPPER=false`도 유지한다. 이 경우 H2의 대소문자 저장 플래그가 모두 false여서 Hibernate의 기본 대문자 메타데이터 조회가 실제 소문자 이름을 찾지 못할 수 있다. `H2CaseSensitiveDialect`는 해당 경우에만 원래 철자로 조회하도록 보정하고, 나머지는 기본 H2 Dialect에 위임한다. DB 이름·테이블명·컬럼명이나 대소문자 구분 규칙을 바꾸지 않는다. ORM/H2 버전 변경 시 이 호환성 테스트를 함께 실행한다.

## 설정 파일과 기존 로컬 환경

| 파일 | 역할 |
|---|---|
| `src/main/resources/application.yaml` | 공유 DB 설정을 불러오는 저장소 기본 진입점 |
| `src/main/resources/database.properties` | 비밀정보 없는 H2 경로·SQL 초기화·스키마 검증 정책 |
| `src/main/resources/db/schema-h2.sql` | 현재 7개 Entity에 맞춘 비파괴 초기 스키마 |
| `src/main/java/me/timetablescheduler/config/H2CaseSensitiveDialect.java` | 기존 H2 식별자 설정에 맞춘 제한적인 메타데이터 조회 보정 |
| 로컬 `src/main/resources/application.properties` | Git 제외 상태인 기존 인증·외부 서비스 설정 |
| `src/test/resources/application.properties` | 메모리 H2, `create-drop`, SQL 초기화 비활성화 |

별도 개발 프로파일을 새로 도입하지 않는다. 일반 실행과 테스트는 서로 다른 resource 설정을 사용한다.

기존 로컬 `application.properties`가 있다면 다음 설정을 유지한다. `.properties`가 같은 위치의 YAML보다 우선하므로 기존 파일의 `create`를 그대로 두고 YAML만 추가해서는 안 된다. 공유 설정 import는 해당 로컬 파일에 선언된 DB 정책보다 우선 적용된다.

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.config.import=classpath:database.properties
```

이번 작업에서 이 작업 공간의 로컬 파일에도 위 설정을 적용한다. 해당 파일에는 외부 서비스 비밀정보가 포함될 수 있으므로 강제 Git 추가하지 않는다. 다른 기존 체크아웃에서도 같은 로컬 설정 정리가 필요하다. 로컬 파일이 없는 체크아웃은 추적되는 `application.yaml`에서 DB 설정을 불러온다. JWT·Google·OpenAI 설정은 별도로 준비해야 하며 T01에서 변경하지 않는다.

환경변수·명령행·별도 외부 설정은 공유 파일보다 우선할 수 있다. 실행 환경에 `SPRING_JPA_HIBERNATE_DDL_AUTO=create` 또는 `create-drop`, 다른 스키마 생성 설정이 남아 있지 않은지 확인한다. T01은 모든 외부 설정을 강제로 차단하는 장치를 추가하지 않는다.

## 데이터 경로와 운영 주의

- 프로젝트 루트에서 실행한다. 상대 경로 DB이므로 실행 디렉터리를 바꾸면 다른 파일을 열 수 있다. 필요하면 `SPRING_DATASOURCE_URL`에 기존 DB의 절대 경로를 명시한다.
- 기존 `data/` 파일을 삭제하거나 새 DB로 덮어쓰지 않는다. 스키마 불일치가 발생해도 `create`로 바꾸어 우회하지 않는다.
- 향후 Entity 변경 시 백업과 명시적인 스키마 변경 절차를 별도로 준비한다. 초기 SQL의 기존 CREATE 정의를 고쳐도 이미 존재하는 테이블은 변경되지 않는다.
- 실제 데이터에 설정을 적용하기 전 백업한다. 파일 복사는 애플리케이션이 완전히 종료된 상태에서 수행하거나 H2의 정식 백업 절차를 사용한다. 실행 중인 `.mv.db`를 단순 복사한 파일을 정상 백업이라고 가정하지 않는다.

## 검증 범위

`DatabasePersistenceIntegrationTest`는 임시 디렉터리의 파일 H2만 사용한다. 저장소의 실제 `data/` DB와 외부 Google/OpenAI 서비스는 사용하지 않는다.

검증 기준은 새 스키마 초기화, Repository 저장 후 컨텍스트 종료·재기동에 따른 값·관계·ID 유지, 스키마 불일치 시 시작 실패와 기존 행 보존, 공유 설정 import 우선순위, 개발·테스트 설정 격리다. 기존 Repository/JPA 테스트도 메모리 DB에서 실행한다.

기존 설치 호환성은 별도 빈 임시 DB에 이전 Hibernate `create` 방식으로 스키마와 데이터를 만든 뒤, 새 SQL 초기화·`validate` 정책으로 재시작하는 테스트로 검증한다. 이 테스트에서만 명시적으로 이전 설정을 재현하며 실제 데이터 경로는 사용하지 않는다.

```sh
# T01 전용 검증
./gradlew test --tests '*DatabasePersistenceIntegrationTest'

# 기존 Repository/JPA 테스트를 포함한 전체 회귀 검증
./gradlew test
```

격리 DB에서 통과하더라도 실제 기존 DB의 스키마 호환성까지 확인한 것은 아니다. 실제 DB는 이 작업에서 연결하거나 수정하지 않는다.

참고: [Spring Boot 설정 우선순위](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html), [Hibernate 스키마 동작](https://docs.hibernate.org/stable/orm/javadocs/org/hibernate/tool/schema/Action.html).
