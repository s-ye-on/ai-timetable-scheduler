package me.timetablescheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class T09SchemaMigrationIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void 기존_파일_DB에_T09_열을_비파괴적으로_추가하고_재시작_후에도_기존_행을_보존한다() throws Exception {
		Path databasePath = temporaryDirectory.resolve("legacy-t09");
		createLegacyDatabase(databasePath);

		try (Connection connection = connect(databasePath)) {
			applyMigration(connection);
			applyMigration(connection);

			try (Statement statement = connection.createStatement();
				 ResultSet task = statement.executeQuery("""
					 SELECT id, user_id, preferred_date, preferred_time_range, status,
					        date_required, time_required, preferred_start_time
					 FROM task WHERE id = 11
					 """)) {
				assertThat(task.next()).isTrue();
				assertThat(task.getLong("id")).isEqualTo(11L);
				assertThat(task.getLong("user_id")).isEqualTo(7L);
				assertThat(task.getDate("preferred_date")).isEqualTo(Date.valueOf("2026-10-22"));
				assertThat(task.getString("preferred_time_range")).isEqualTo("EVENING");
				assertThat(task.getString("status")).isEqualTo("SCHEDULED");
				assertThat(task.getObject("date_required")).isNull();
				assertThat(task.getObject("time_required")).isNull();
				assertThat(task.getObject("preferred_start_time")).isNull();
			}

			try (Statement statement = connection.createStatement();
				 ResultSet recommendation = statement.executeQuery("""
					 SELECT id, task_id, google_event_id, status, match_type
					 FROM recommendation WHERE id = 19
					 """)) {
				assertThat(recommendation.next()).isTrue();
				assertThat(recommendation.getLong("id")).isEqualTo(19L);
				assertThat(recommendation.getLong("task_id")).isEqualTo(11L);
				assertThat(recommendation.getString("google_event_id")).isEqualTo("existing-event-id");
				assertThat(recommendation.getString("status")).isEqualTo("SYNCED");
				assertThat(recommendation.getObject("match_type")).isNull();
			}

			assertThatThrownBy(() -> statementExecute(connection,
				"UPDATE recommendation SET match_type = 'UNSUPPORTED' WHERE id = 19"))
				.isInstanceOf(SQLException.class);
		}

		try (Connection restartedConnection = connect(databasePath);
			 Statement statement = restartedConnection.createStatement();
			 ResultSet resultSet = statement.executeQuery("""
				 SELECT t.id, r.id, r.google_event_id, r.match_type
				 FROM task t JOIN recommendation r ON r.task_id = t.id
				 WHERE t.id = 11 AND r.id = 19
				 """)) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getLong(1)).isEqualTo(11L);
			assertThat(resultSet.getLong(2)).isEqualTo(19L);
			assertThat(resultSet.getString(3)).isEqualTo("existing-event-id");
			assertThat(resultSet.getObject(4)).isNull();
		}
	}

	private void createLegacyDatabase(Path databasePath) throws SQLException {
		try (Connection connection = connect(databasePath); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
			statement.execute("""
				CREATE TABLE task (
				    id BIGINT PRIMARY KEY,
				    user_id BIGINT NOT NULL,
				    preferred_date DATE,
				    preferred_time_range VARCHAR(20),
				    status VARCHAR(20) NOT NULL,
				    CONSTRAINT fk_legacy_task_user FOREIGN KEY (user_id) REFERENCES users (id)
				)
				""");
			statement.execute("""
				CREATE TABLE recommendation (
				    id BIGINT PRIMARY KEY,
				    task_id BIGINT NOT NULL,
				    google_event_id VARCHAR(255),
				    status VARCHAR(20) NOT NULL,
				    CONSTRAINT fk_legacy_recommendation_task FOREIGN KEY (task_id) REFERENCES task (id)
				)
				""");
			statement.execute("INSERT INTO users (id) VALUES (7)");
			statement.execute("""
				INSERT INTO task (id, user_id, preferred_date, preferred_time_range, status)
				VALUES (11, 7, DATE '2026-10-22', 'EVENING', 'SCHEDULED')
				""");
			statement.execute("""
				INSERT INTO recommendation (id, task_id, google_event_id, status)
				VALUES (19, 11, 'existing-event-id', 'SYNCED')
				""");
		}
	}

	private void applyMigration(Connection connection) {
		ScriptUtils.executeSqlScript(connection,
			new ClassPathResource("db/migration/t09-add-recommendation-quality-columns.sql"));
	}

	private void statementExecute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Connection connect(Path databasePath) throws SQLException {
		return DriverManager.getConnection(
			"jdbc:h2:file:" + databasePath.toAbsolutePath()
				+ ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
	}
}
