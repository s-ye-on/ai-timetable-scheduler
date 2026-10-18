package me.timetablescheduler.domain.recommendation.service;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import me.timetablescheduler.auth.jwt.JwtTokenService;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import me.timetablescheduler.domain.calendar.CalendarService;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.llm.dto.DateRange;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.dto.RecommendationResponse;
import me.timetablescheduler.domain.recommendation.type.RecommendationMatchType;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.task.service.TaskService;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t09-intent;MODE=MySQL;DB_CLOSE_DELAY=-1")
class RecommendationIntentIntegrationTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 22);

	@Autowired private TaskService taskService;
	@Autowired private MockMvc mvc;
	@Autowired private JwtTokenService jwt;
	@Autowired private RecommendationRepository recommendations;
	@Autowired private RecommendationService recommendationService;
	@Autowired private TaskRepository tasks;
	@Autowired private UserRepository users;
	@Autowired private PreferenceRepository preferences;
	@MockitoBean private LlmParsingService llmParsingService;
	@MockitoBean private CalendarService calendarService;
	@MockitoBean private Clock clock;

	private User user;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(Instant.parse("2026-10-19T00:00:00Z"));
		when(clock.getZone()).thenReturn(SEOUL);
		user = users.save(new User("테스터", "t09-" + UUID.randomUUID().toString().substring(0, 8)
			+ "@example.com", "test-only-password-hash"));
		preferences.save(Preference.create(user, PreferredTimeRange.EVENING,
			LocalTime.of(9, 0), LocalTime.of(22, 0), 15,
			ScheduleDensity.BALANCED, DeadlineTiming.BALANCED));
		when(calendarService.getCalendarBusyBlocksOrEmpty(any(), any(), any())).thenReturn(List.<BusyBlock>of());
	}

	@Test
	void naturalLanguageExactOffGridTimeIsPersistedAndRecommendedWithoutAutoScheduling() {
		when(llmParsingService.parseTask(any())).thenReturn(new ParsedTaskResponse(
			"알고리즘 공부", TaskCategory.STUDY, 120, null, DayOfWeek.THURSDAY, null,
			PreferredTimeRange.EVENING, null, TaskPriority.NORMAL,
			"목요일 저녁 7시 10분에 알고리즘 공부 2시간", true, true, LocalTime.of(19, 10), null
		));

		TaskResponse.Create created = taskService.createFromNaturalLanguage(user.getId(),
			new TaskRequest.NaturalLanguage("목요일 저녁 7시 10분에 알고리즘 공부 2시간 잡아줘"));
		RecommendationResponse.Generate generated = recommendationService.recommend(user.getId(), created.id());
		List<RecommendationResponse.Read> history = recommendationService.readByTask(user.getId(), created.id(), true);

		assertThat(created.dateRequired()).isTrue();
		assertThat(created.timeRequired()).isTrue();
		assertThat(created.preferredStartTime()).isEqualTo(LocalTime.of(19, 10));
		assertThat(generated.recommendations()).hasSize(2);
		RecommendationResponse.Read recommended = generated.recommendations().getFirst();
		assertThat(recommended.recommendedStartAt()).isEqualTo(THURSDAY.atTime(19, 10));
		assertThat(recommended.recommendedEndAt()).isEqualTo(THURSDAY.atTime(21, 10));
		assertThat(recommended.matchType()).isEqualTo(RecommendationMatchType.EXACT);
		assertThat(recommended.score()).isEqualTo(89);
		assertThat(generated.recommendations().getLast().recommendedStartAt()).isEqualTo(THURSDAY.plusDays(7).atTime(19, 10));
		assertThat(recommended.reason()).contains("지정한 조건에 일치합니다");
		assertThat(history).containsExactlyElementsOf(generated.recommendations().reversed());
		assertThat(tasks.findById(created.id()).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		verify(calendarService).getCalendarBusyBlocksOrEmpty(any(), any(), any());
	}

	@Test
	void changingIntentExpiresExistingCandidatesWhileKeepingRangeAndNewFlags() {
		when(llmParsingService.parseTask(any())).thenReturn(new ParsedTaskResponse(
			"과제", TaskCategory.ASSIGNMENT, 60, null, DayOfWeek.TUESDAY,
			new DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)),
			PreferredTimeRange.LUNCH, null, TaskPriority.NORMAL, "이번 주 화요일 점심이면 좋겠어",
			false, false, null, null
		));
		TaskResponse.Create created = taskService.createFromNaturalLanguage(user.getId(),
			new TaskRequest.NaturalLanguage("이번 주 안에 화요일 점심이면 좋겠어"));
		RecommendationResponse.Generate generated = recommendationService.recommend(user.getId(), created.id());

		TaskResponse.Read updated = taskService.update(created.id(), new TaskRequest.Update(
			"과제", TaskCategory.ASSIGNMENT, 60, null, DayOfWeek.TUESDAY,
			LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), PreferredTimeRange.LUNCH,
			true, true, LocalTime.of(12, 0), false, null, TaskPriority.NORMAL, "화요일 12시에만 가능"
		), user.getId());

		assertThat(generated.recommendations()).isNotEmpty();
		assertThat(updated.preferredStartDate()).isEqualTo(LocalDate.of(2026, 10, 19));
		assertThat(updated.preferredEndDate()).isEqualTo(LocalDate.of(2026, 10, 25));
		assertThat(updated.dateRequired()).isTrue();
		assertThat(updated.timeRequired()).isTrue();
		assertThat(updated.preferredStartTime()).isEqualTo(LocalTime.NOON);
		assertThat(recommendationService.readProposedByTask(user.getId(), created.id())).isEmpty();
		assertThat(recommendationService.readByTask(user.getId(), created.id(), true))
			.allSatisfy(recommendation -> assertThat(recommendation.status().name()).isEqualTo("EXPIRED"));
	}
	@Test
	void exactOffGridCandidateCanBeSelectedThroughApiAndRetainsTypeAndSingleEvent() throws Exception {
		Task task=tasks.save(Task.create(user,"정확시각",TaskCategory.STUDY,120,THURSDAY,null,null,null,
			PreferredTimeRange.EVENING,null,TaskPriority.NORMAL,"19시10분",true,true,LocalTime.of(19,10)));
		var candidate=recommendationService.recommend(user.getId(),task.getId()).recommendations().getFirst();
		String bearer="Bearer "+jwt.generateAccessToken(user.getEmail());
		mvc.perform(get("/tasks/{id}",task.getId()).header("Authorization",bearer))
			.andExpect(status().isOk()).andExpect(jsonPath("$.preferredStartTime").value("19:10:00"))
			.andExpect(jsonPath("$.dateRequired").value(true)).andExpect(jsonPath("$.timeRequired").value(true));
		mvc.perform(get("/tasks/{id}/recommendations",task.getId()).param("includeHistory","true").header("Authorization",bearer))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].matchType").value("EXACT"))
			.andExpect(jsonPath("$[0].score").value(89));
		when(calendarService.isConnected(user.getId())).thenReturn(true);
		when(calendarService.getCalendarBusyBlocks(eq(user.getId()),any(),any())).thenReturn(List.of());
		when(calendarService.createEvent(eq(user.getId()),any(),any(),any(),any(),any()))
			.thenAnswer(call->call.getArgument(1));
		for(int n=0;n<2;n++) {
			mvc.perform(post("/recommendations/{id}/select",candidate.id()).header("Authorization",bearer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SYNCED"))
				.andExpect(jsonPath("$.matchType").value("EXACT"));
		}
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SCHEDULED);
		var saved=recommendations.findById(candidate.id()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(saved.getGoogleEventId()).isNotBlank();
		verify(calendarService,times(1)).createEvent(eq(user.getId()),eq(saved.getGoogleEventId()),any(),any(),
			eq(THURSDAY.atTime(19,10)),eq(THURSDAY.atTime(21,10)));
	}

	@Test
	void alternativesQueryIncludesEveryDayAndNeighborGapAndConnectedConflictBlocksOldCandidate() throws Exception {
		Task task=tasks.save(Task.create(user,"대안",TaskCategory.STUDY,120,THURSDAY,null,null,null,
			PreferredTimeRange.EVENING,null,TaskPriority.NORMAL,"목요일저녁",false,false,LocalTime.of(19,10)));
		var generated=recommendationService.recommend(user.getId(),task.getId());
		verify(calendarService).getCalendarBusyBlocksOrEmpty(eq(user.getId()),
			eq(THURSDAY.atStartOfDay().minusMinutes(15)),eq(THURSDAY.plusDays(14).atStartOfDay().plusMinutes(15)));
		assertThat(generated.recommendations()).anySatisfy(c->assertThat(c.matchType()).isEqualTo(RecommendationMatchType.EXACT));
		var candidate=generated.recommendations().stream().filter(c->c.matchType()==RecommendationMatchType.EXACT).findFirst().orElseThrow();
		when(calendarService.isConnected(user.getId())).thenReturn(true);
		when(calendarService.getCalendarBusyBlocks(eq(user.getId()),any(),any())).thenReturn(
			List.of(new BusyBlock(candidate.recommendedStartAt(),candidate.recommendedEndAt())));
		mvc.perform(post("/recommendations/{id}/select",candidate.id())
			.header("Authorization","Bearer "+jwt.generateAccessToken(user.getEmail())))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMENDATION_NO_LONGER_AVAILABLE"));
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		verify(calendarService,never()).createEvent(any(),any(),any(),any(),any(),any());
	}

}
