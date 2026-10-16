package me.timetablescheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalTime;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

class DatabasePersistenceIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void 빈_파일_DB를_초기화하고_검증_재시작_후에도_데이터와_식별자가_유지된다() {
		Path databasePath = temporaryDirectory.resolve("persistence-check");
		long persistedUserId;

		try (ConfigurableApplicationContext context = startFileDatabase(
			databasePath,
			"spring.jpa.hibernate.ddl-auto=create"
		)) {
			assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

			UserRepository userRepository = context.getBean(UserRepository.class);
			TimetableSlotRepository timetableSlotRepository = context.getBean(TimetableSlotRepository.class);
			User user = userRepository.saveAndFlush(new User("Persisted", "persisted@example.com", "encodedPassword"));
			timetableSlotRepository.saveAndFlush(TimetableSlot.create(
				user,
				"자료구조",
				DayOfWeek.MONDAY,
				"공학관 101",
				LocalTime.of(9, 0),
				LocalTime.of(10, 30)
			));
			persistedUserId = user.getId();
		}

		try (ConfigurableApplicationContext context = startFileDatabase(databasePath)) {
			UserRepository userRepository = context.getBean(UserRepository.class);
			TimetableSlotRepository timetableSlotRepository = context.getBean(TimetableSlotRepository.class);
			User restoredUser = userRepository.findByEmail("persisted@example.com").orElseThrow();

			assertThat(restoredUser.getId()).isEqualTo(persistedUserId);
			assertThat(timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(persistedUserId))
				.singleElement()
				.extracting(TimetableSlot::getSubjectName)
				.isEqualTo("자료구조");

			User nextUser = userRepository.saveAndFlush(new User("Next", "next@example.com", "encodedPassword"));
			assertThat(nextUser.getId()).isGreaterThan(persistedUserId);
		}
	}

	@Test
	void 기존_Hibernate로_생성한_파일_DB도_새_설정에서_보존된다() {
		Path databasePath = temporaryDirectory.resolve("legacy-hibernate");
		long userId;

		// Reproduce the previous schema only in a fresh, temporary database.
		assertThat(databasePath.resolveSibling(databasePath.getFileName() + ".mv.db")).doesNotExist();
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PersistenceOnlyApplication.class)
			.web(WebApplicationType.NONE)
			.bannerMode(Banner.Mode.OFF)
			.run(
				"--spring.config.location=classpath:/application.yaml",
				"--spring.datasource.url=" + fileUrl(databasePath),
				"--spring.datasource.username=sa",
				"--spring.datasource.password=",
				"--spring.sql.init.mode=never",
				"--spring.jpa.hibernate.ddl-auto=create",
				"--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
			)) {
			User user = context.getBean(UserRepository.class)
				.saveAndFlush(new User("Legacy", "legacy@example.com", "encodedPassword"));
			userId = user.getId();
		}

		try (ConfigurableApplicationContext context = startFileDatabase(databasePath)) {
			User restored = context.getBean(UserRepository.class).findByEmail("legacy@example.com").orElseThrow();
			assertThat(restored.getId()).isEqualTo(userId);
			assertThat(restored.getName()).isEqualTo("Legacy");
		}
	}

	@Test
	void 스키마가_달라지면_validate가_실패하고_기존_행은_삭제하지_않는다() throws Exception {
		Path databasePath = temporaryDirectory.resolve("schema-drift-check");

		try (ConfigurableApplicationContext context = startFileDatabase(databasePath)) {
			context.getBean(UserRepository.class)
				.saveAndFlush(new User("Persisted", "schema-drift@example.com", "encodedPassword"));
		}

		try (Connection connection = DriverManager.getConnection(fileUrl(databasePath), "sa", "");
			 Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE users DROP COLUMN password");
		}

		assertThatThrownBy(() -> {
			try (ConfigurableApplicationContext ignored = startFileDatabase(databasePath)) {
				// The application context must not start with a stale schema.
			}
		})
			.hasRootCauseInstanceOf(SchemaManagementException.class)
			.hasStackTraceContaining("missing column [password]");

		try (Connection connection = DriverManager.getConnection(fileUrl(databasePath), "sa", "");
			 Statement statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery(
				"SELECT COUNT(*) FROM users WHERE email = 'schema-drift@example.com'"
			)) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getInt(1)).isEqualTo(1);
		}
	}

	@Test
	void 테스트_기본_설정은_메모리_DB와_create_drop을_계속_사용한다() {
		StandardEnvironment environment = newIsolatedEnvironment();

		ConfigDataEnvironmentPostProcessor.applyTo(environment);

		assertThat(environment.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:h2:mem:timetable-scheduler;MODE=MySQL;DB_CLOSE_DELAY=-1");
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
		assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
	}

	@Test
	void 기존_설정과_공유_DB_정책을_함께_읽어도_validate가_적용된다() throws IOException {
		Path legacyProperties = temporaryDirectory.resolve("legacy-application.properties");
		java.nio.file.Files.writeString(legacyProperties, """
			spring.jpa.hibernate.ddl-auto=create
			spring.config.import=classpath:database.properties
			""");
		StandardEnvironment environment = newIsolatedEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("testConfig", java.util.Map.of(
			"spring.config.location", "classpath:/application.yaml," + legacyProperties.toUri()
		)));

		ConfigDataEnvironmentPostProcessor.applyTo(environment);

		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	private ConfigurableApplicationContext startFileDatabase(Path databasePath, String... defaultProperties) {
		SpringApplicationBuilder applicationBuilder = new SpringApplicationBuilder(PersistenceOnlyApplication.class)
			.web(WebApplicationType.NONE)
			.bannerMode(Banner.Mode.OFF)
			.listeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
				assertThat(event.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(fileUrl(databasePath));
				assertThat(event.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("always");
				assertThat(event.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
			})
			.properties(defaultProperties);

		return applicationBuilder.run(
			"--spring.config.location=classpath:/application.yaml",
			"--spring.datasource.url=" + fileUrl(databasePath),
			"--spring.datasource.username=sa",
			"--spring.datasource.password=",
			"--spring.h2.console.enabled=false"
		);
	}

	private String fileUrl(Path databasePath) {
		return "jdbc:h2:file:" + databasePath.toAbsolutePath() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE";
	}

	private StandardEnvironment newIsolatedEnvironment() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		return environment;
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackages = "me.timetablescheduler")
	@EnableJpaRepositories(basePackages = "me.timetablescheduler")
	static class PersistenceOnlyApplication {
	}
}
