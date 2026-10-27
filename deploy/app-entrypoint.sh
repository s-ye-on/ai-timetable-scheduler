#!/bin/sh
set -eu

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case "${H2_HOST_DATA_DIR:-}" in
  /*) ;;
  *) fail 'H2_DATA_DIR은 미리 생성한 호스트 절대경로여야 합니다.' ;;
esac

db_path="${H2_DATABASE_PATH:-/var/lib/campus-planner/data/timetable-scheduler}"
db_dir=/var/lib/campus-planner/data
[ "$(dirname "$db_path")" = "$db_dir" ] || fail 'H2 경로는 데이터 Bind Mount 내부여야 합니다.'
case "$(basename "$db_path")" in
  ''|*[!a-zA-Z0-9_-]*) fail 'H2 파일 이름에는 영문·숫자·밑줄·하이픈만 사용할 수 있습니다.' ;;
esac
[ -d "$db_dir" ] && [ -w "$db_dir" ] || fail 'H2 데이터 디렉터리 존재 여부와 UID 10001 쓰기 권한을 확인하세요.'
[ -z "${SPRING_DATASOURCE_URL:-}" ] || fail '운영 JDBC URL을 우회하지 말고 H2_DATABASE_PATH를 사용하세요.'

if [ "${1:-}" = 'init-empty-db' ]; then
  # 정상 운영 부팅에서 호출하지 않는다. 기존 파일이 하나라도 있으면 거절한다.
  for entry in "$db_dir"/* "$db_dir"/.[!.]* "$db_dir"/..?*; do
    if [ -e "$entry" ] || [ -L "$entry" ]; then
      fail '빈 디렉터리에서만 최초 생성할 수 있습니다. 기존 DB·백업을 덮어쓰지 않습니다.'
    fi
  done
  java -cp /opt/app/h2.jar org.h2.tools.RunScript \
    -url "jdbc:h2:file:$db_path;MODE=MySQL;DATABASE_TO_UPPER=false" \
    -user "${SPRING_DATASOURCE_USERNAME:-sa}" \
    -password "${SPRING_DATASOURCE_PASSWORD:-}" \
    -script /opt/app/schema-h2.sql
  printf '%s\n' '빈 디렉터리에 신규 H2 스키마를 생성했습니다. 기존 데이터 이관 절차를 대신하지 않습니다.'
  exit 0
fi

[ "$#" -eq 0 ] || fail '일반 실행 인수는 허용하지 않습니다. 최초 생성은 init-empty-db만 사용하세요.'
[ -s "$db_path.mv.db" ] && [ -r "$db_path.mv.db" ] && [ -w "$db_path.mv.db" ] \
  || fail '기존 H2 파일이 없거나 접근할 수 없어 서버 시작을 거부합니다. 경로·복원·권한을 확인하세요.'

exec java -jar /opt/app/app.jar --spring.profiles.active=prod
