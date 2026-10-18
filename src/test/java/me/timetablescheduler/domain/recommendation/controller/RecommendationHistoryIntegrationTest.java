package me.timetablescheduler.domain.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t10-history;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RecommendationHistoryIntegrationTest {
	private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
	private static final LocalDateTime START = DATE.atTime(10, 0);
	@Autowired private MockMvc mvc;
	@Autowired private UserRepository users;
	@Autowired private TaskRepository tasks;
	@Autowired private RecommendationRepository recommendations;
	@MockitoBean private GoogleCalendarClient google;
	private User user;
	private Task task;
	private String accessToken;

	@BeforeEach
	void 테스트_준비() throws Exception {
		String email = UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		accessToken = 회원가입(email);
		user = users.findByEmail(email).orElseThrow();
		task = tasks.save(Task.create(user, "기존 일정", TaskCategory.STUDY, 60, DATE, null,
			null, null, null, null, TaskPriority.NORMAL, "이전 분석 내용"));
	}

	@Test
	void 기본_조회는_제안_상태의_후보만_반환한다() throws Exception {
		var old = 후보_생성(1);
		old.expire();
		recommendations.saveAndFlush(old);
		var proposed = 후보_생성(1);
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).header(HttpHeaders.AUTHORIZATION, 인증_헤더_생성()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(proposed.getId()))
			.andExpect(jsonPath("$[0].status").value("PROPOSED"))
			.andExpect(jsonPath("$[0].syncRetryable").value(false));
		verifyNoInteractions(google);
	}

	@Test
	void 이력_조회는_실패하거나_거절된_후보를_재선택하지_않고_복원한다() throws Exception {
		var rejected = 후보_생성(2);
		rejected.reject();
		recommendations.saveAndFlush(rejected);
		var failed = 예약_생성(true);
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).param("includeHistory", "true")
				.header(HttpHeaders.AUTHORIZATION, 인증_헤더_생성()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(failed.getId()))
			.andExpect(jsonPath("$[0].status").value("SYNC_FAILED"))
			.andExpect(jsonPath("$[0].syncRetryable").value(true))
			.andExpect(jsonPath("$[0].googleEventId").doesNotExist())
			.andExpect(jsonPath("$[1].status").value("REJECTED"))
			.andExpect(jsonPath("$[1].syncRetryable").value(false));
		assertThat(recommendations.findById(failed.getId()).orElseThrow().getGoogleEventId()).isEqualTo("abcde");
		verifyNoInteractions(google);
	}

	@Test
	void 선택된_예약은_재시도할_수_있고_동기화된_이력은_재시도할_수_없다() throws Exception {
		var selected = 예약_생성(false);
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).param("includeHistory", "true")
				.header(HttpHeaders.AUTHORIZATION, 인증_헤더_생성()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SELECTED"))
			.andExpect(jsonPath("$[0].syncRetryable").value(true));
		selected.sync("abcde");
		recommendations.saveAndFlush(selected);
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).param("includeHistory", "true")
				.header(HttpHeaders.AUTHORIZATION, 인증_헤더_생성()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SYNCED"))
			.andExpect(jsonPath("$[0].syncRetryable").value(false));
		verifyNoInteractions(google);
	}

	@Test
	void 식별자가_없는_기존_실패와_불일치한_Task는_자동_재시도를_제공하지_않는다() throws Exception {
		var legacy = 후보_생성(1);
		legacy.select();
		legacy.failSync();
		recommendations.saveAndFlush(legacy);
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).param("includeHistory", "true")
				.header(HttpHeaders.AUTHORIZATION, 인증_헤더_생성()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SYNC_FAILED"))
			.andExpect(jsonPath("$[0].syncRetryable").value(false));
		var failed = 예약_생성(true);
		task.unschedule();
		tasks.saveAndFlush(task);
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).param("includeHistory", "true")
				.header(HttpHeaders.AUTHORIZATION, 인증_헤더_생성()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(failed.getId()))
			.andExpect(jsonPath("$[0].syncRetryable").value(false));
		verifyNoInteractions(google);
	}

	@Test
	void 이력_조회로_다른_사용자의_Task를_읽을_수_없다() throws Exception {
		예약_생성(true);
		String other = 회원가입(UUID.randomUUID().toString().substring(0, 8) + "@example.com");
		mvc.perform(get("/tasks/{id}/recommendations", task.getId()).param("includeHistory", "true")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND_TASK"));
		verifyNoInteractions(google);
	}

	private Recommendation 예약_생성(boolean failed) {
		var candidate = 후보_생성(1);
		candidate.reserveCalendarSync("abcde");
		task.schedule(START, START.plusHours(1));
		tasks.saveAndFlush(task);
		if (failed) candidate.failSync();
		return recommendations.saveAndFlush(candidate);
	}

	private Recommendation 후보_생성(int rank) {
		return recommendations.save(Recommendation.create(user, task, START, START.plusHours(1), rank, 80, "기존 추천"));
	}

	private String 인증_헤더_생성() { return "Bearer " + accessToken; }

	private String 회원가입(String email) throws Exception {
		var result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"테스터\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		var matcher = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(result);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
