package me.timetablescheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import me.timetablescheduler.domain.timetable.TimetableColor;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;

class TimetableColorSchemaIntegrationTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void 기존_슬롯을_BLUE로_확장하고_반복_마이그레이션과_validate_재시작에_선택색을_보존한다() throws Exception {
		Path path = temporaryDirectory.resolve("legacy-slot-color");
		try (Connection connection = 연결(path)) {
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema-h2.sql"));
			// 이 테스트가 방금 생성한 격리 DB만 예전 스키마로 구성한다. 실제 DB에 접근하지 않는다.
			실행(connection, "ALTER TABLE timetable_slot DROP CONSTRAINT ck_timetable_slot_color");
			실행(connection, "ALTER TABLE timetable_slot DROP COLUMN color");
			기존_행_생성(connection);
			마이그레이션(connection);
			마이그레이션(connection);
			try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT color FROM timetable_slot ORDER BY id")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getString(1)).isEqualTo("BLUE");
				assertThat(rows.next()).isTrue();
				assertThat(rows.getString(1)).isEqualTo("BLUE");
				assertThat(rows.next()).isFalse();
			}
			실행(connection, "UPDATE timetable_slot SET color='APRICOT' WHERE id=11");
			assertThatThrownBy(() -> 실행(connection, "UPDATE timetable_slot SET color='PURPLE' WHERE id=11"))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> 실행(connection, "UPDATE timetable_slot SET color=NULL WHERE id=11"))
				.isInstanceOf(SQLException.class);
		}
		for (int restart = 0; restart < 2; restart++) {
			try (ConfigurableApplicationContext context = 시작(path)) {
				assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
				var slots = context.getBean(TimetableSlotRepository.class).findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(7L);
				assertThat(slots).extracting(slot -> slot.getId()).containsExactly(11L, 12L);
				assertThat(slots).extracting(slot -> slot.getColor()).containsExactly(TimetableColor.APRICOT, TimetableColor.BLUE);
				assertThat(slots).allSatisfy(slot -> {
					assertThat(slot.getSubjectName()).isEqualTo("기존 과목");
					assertThat(slot.getLocation()).isEqualTo("기존 강의실");
					assertThat(slot.getCreatedAt().getYear()).isEqualTo(2026);
				});
			}
		}
	}

	@Test
	void 신규_파일_DB의_모든_색상을_저장하고_재시작해도_보존한다() throws Exception {
		Path path = temporaryDirectory.resolve("new-slot-color");
		try (ConfigurableApplicationContext context = 시작(path)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			jdbc.update("INSERT INTO users (id,name,email,password) VALUES (7,'색상 검증','fixture@example.test','fixture-hash')");
			int id = 1;
			for (TimetableColor color : TimetableColor.values()) {
				jdbc.update("""
					INSERT INTO timetable_slot (id,user_id,subject_name,day_of_week,location,start_time,end_time,created_at,updated_at,color)
					VALUES (?,7,'같은 과목','MONDAY','강의실',TIME '09:00:00',TIME '10:00:00',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)
					""", id++, color.name());
			}
		}
		try (ConfigurableApplicationContext context = 시작(path)) {
			List<String> saved = context.getBean(JdbcTemplate.class).queryForList("SELECT color FROM timetable_slot ORDER BY id", String.class);
			assertThat(saved).containsExactly(java.util.Arrays.stream(TimetableColor.values()).map(Enum::name).toArray(String[]::new));
		}
	}

	private void 기존_행_생성(Connection connection) throws SQLException {
		실행(connection, "INSERT INTO users (id,name,email,password) VALUES (7,'기존 사용자','legacy@example.test','fixture-hash')");
		for (int id : new int[]{11, 12}) {
			실행(connection, """
				INSERT INTO timetable_slot (id,user_id,subject_name,day_of_week,location,start_time,end_time,created_at,updated_at)
				VALUES (%d,7,'기존 과목','MONDAY','기존 강의실',TIME '09:00:00',TIME '10:00:00',
				TIMESTAMP WITH TIME ZONE '2026-10-01 09:00:00+09:00',TIMESTAMP WITH TIME ZONE '2026-10-01 09:00:00+09:00')
				""".formatted(id));
		}
	}

	private ConfigurableApplicationContext 시작(Path path) {
		return new SpringApplicationBuilder(DatabasePersistenceIntegrationTest.PersistenceOnlyApplication.class)
			.web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).run(
				"--spring.config.location=classpath:/application.yaml", "--spring.datasource.url=" + URL(path),
				"--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.h2.console.enabled=false");
	}

	private Connection 연결(Path path) throws SQLException {
		return DriverManager.getConnection(URL(path), "sa", "");
	}

	private String URL(Path path) {
		return "jdbc:h2:file:" + path.toAbsolutePath() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE";
	}

	private void 마이그레이션(Connection connection) {
		ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/add-timetable-slot-color.sql"));
	}

	private void 실행(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) { statement.execute(sql); }
	}
}
