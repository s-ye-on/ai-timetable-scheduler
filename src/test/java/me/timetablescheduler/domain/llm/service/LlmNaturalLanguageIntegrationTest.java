package me.timetablescheduler.domain.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.List;
import java.util.UUID;
import me.timetablescheduler.auth.jwt.JwtTokenService;
import me.timetablescheduler.domain.calendar.CalendarService;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.service.RecommendationService;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.recommendation.type.RecommendationMatchType;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:llm-policy;MODE=MySQL;DB_CLOSE_DELAY=-1")
class LlmNaturalLanguageIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenService jwt;
    @Autowired private UserRepository users;
    @Autowired private TaskRepository tasks;
    @Autowired private PreferenceRepository preferences;
    @Autowired private RecommendationService recommendations;
    @MockitoBean private OpenAiClient client;
    @MockitoBean private CalendarService calendar;
    @MockitoBean private Clock clock;
    private User user;

    @BeforeEach
    void 격리된_사용자와_기준_시각을_준비한다() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-19T01:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        user = users.save(new User("정책", "policy-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-hash"));
        preferences.save(Preference.create(user, PreferredTimeRange.ANYTIME, LocalTime.of(9, 0), LocalTime.of(22, 0),
            0, ScheduleDensity.BALANCED, DeadlineTiming.BALANCED));
        when(calendar.getCalendarBusyBlocksOrEmpty(any(), any(), any())).thenReturn(List.of());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "목요일 저녁 7시에 알고리즘 공부 2시간|THURSDAY|19:00|120|2026-10-22|EVENING",
        "목요일 19시 10분에 공부하고 싶어|THURSDAY|19:10|60|2026-10-22|EVENING",
        "오늘 밤 한 시에 공부 1시간|NULL|01:00|60|2026-10-20|NULL",
        "다음 주 월요일 점심에 공부 1시간|MONDAY|NULL|60|2026-10-26|LUNCH"
    }, nullValues = "NULL")
    void G01_G04_G37_G39_실제_파서부터_저장과_추천까지_연결한다(String source, DayOfWeek day,
        LocalTime time, int duration, LocalDate expectedDate, PreferredTimeRange band) throws Exception {
        var raw = new ParsedTaskResponse("공부", TaskCategory.STUDY, duration, null, day, null,
            time == null ? band : null, null, null, null, false, false, time, null);
        when(client.parseTask(eq(source), eq(LocalDate.of(2026, 10, 19)))).thenReturn(raw);
        var response = mvc.perform(post("/tasks/natural-language").header("Authorization", 토큰())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("message", source))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.preferredDate").value(expectedDate.toString()))
            .andExpect(jsonPath("$.dateRequired").value(false)).andExpect(jsonPath("$.timeRequired").value(false))
            .andExpect(jsonPath("$.status").value("UNSCHEDULED")).andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        var saved = tasks.findById(id).orElseThrow();
        assertThat(saved.getPreferredDate()).isEqualTo(expectedDate);
        assertThat(saved.getPreferredDayOfWeek()).isNull();
        assertThat(saved.getPreferredStartTime()).isEqualTo(time);
        assertThat(saved.getPreferredTimeRange()).isEqualTo(band);
        assertThat(saved.getPreferredStartDate()).isNull();
        assertThat(saved.getPreferredEndDate()).isNull();
        assertThat(saved.getDescription()).isEqualTo(source);
        var generated = recommendations.recommend(user.getId(), id);
        assertThat(generated.recommendations()).isNotEmpty();
        if (time != null && time.getHour() >= 9) {
            assertThat(generated.recommendations()).anySatisfy(candidate -> {
                assertThat(candidate.recommendedStartAt()).isEqualTo(expectedDate.atTime(time));
                assertThat(candidate.matchType()).isEqualTo(RecommendationMatchType.EXACT);
                assertThat(candidate.score()).isEqualTo(89);
            });
        }
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
        assertThat(raw.preferredDate()).isNull();
        verify(calendar, never()).createEvent(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"반드시 목요일 저녁 7시에만 가능해", "이번 주 안에 화요일 점심이면 좋겠어"})
    void G02_G05_활동명_누락은_정확한_400_응답이며_Task와_추천을_생성하지_않는다(String source) throws Exception {
        when(client.parseTask(anyString(), any(LocalDate.class))).thenReturn(new ParsedTaskResponse(
            null, null, null, null, DayOfWeek.THURSDAY, null, null, null, null, null,
            true, true, LocalTime.of(19, 0), "활동명 확인 필요"));
        mvc.perform(post("/tasks/natural-language").header("Authorization", 토큰())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("message", source))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_LLM_TITLE"))
            .andExpect(jsonPath("$.message").value("어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요."));
        assertThat(tasks.findAllByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        verify(calendar, never()).createEvent(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(calendar);
    }

    private String 토큰() { return "Bearer " + jwt.generateAccessToken(user.getEmail()); }
}
