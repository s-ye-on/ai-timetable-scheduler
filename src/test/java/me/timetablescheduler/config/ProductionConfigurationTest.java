package me.timetablescheduler.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionConfigurationTest {

	@Test
	void 운영_프로파일은_기존_DB만_열고_스키마를_검증하며_HTTPS_전달을_인식한다() {
		// DataSource와 외부 Client는 생성하지 않고 실제 ConfigData 계약만 확인한다.
		new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues(
				"spring.profiles.active=prod",
				"H2_DATABASE_PATH=/var/lib/campus-planner/data/isolated",
				"APP_JWT_SECRET=production-config-test-secret-not-for-real-use",
				"OPENAI_API_KEY=not-a-real-api-key",
				"GOOGLE_CALENDAR_CLIENT_ID=isolated-client",
				"GOOGLE_CLIENT_SECRET=not-a-real-client-secret"
			)
			.run(context -> {
				var env = context.getEnvironment();
				assertThat(env.getProperty("spring.datasource.url")).isEqualTo(
					"jdbc:h2:file:/var/lib/campus-planner/data/isolated;MODE=MySQL;DATABASE_TO_UPPER=false;IFEXISTS=TRUE");
				assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
				assertThat(env.getProperty("spring.jpa.database-platform"))
					.isEqualTo("me.timetablescheduler.config.H2CaseSensitiveDialect");
				assertThat(env.getProperty("spring.sql.init.schema-locations"))
					.contains("schema-h2.sql", "t09-add-recommendation-quality-columns.sql", "add-timetable-slot-color.sql");
				assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("always");
				assertThat(env.getProperty("spring.h2.console.enabled")).isEqualTo("false");
				assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
				assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
				assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
				assertThat(env.getProperty("google.calendar.redirect-uri"))
					.isEqualTo("https://campusplanner.duckdns.org/api/calendar/oauth/callback");
				assertThat(env.getProperty("google.calendar.scope"))
					.isEqualTo("https://www.googleapis.com/auth/calendar");
				assertThat(env.getProperty("openai.model")).isEqualTo("gpt-4o-mini");
			});
	}
}
