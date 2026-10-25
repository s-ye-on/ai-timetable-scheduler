package me.timetablescheduler.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import me.timetablescheduler.domain.calendar.CalendarService;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.calendar.external.GoogleOAuthClient;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 인증·Controller·Service·JPA를 연결하되 외부 API와 실제 DB는 사용하지 않는다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:m7-react-flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReactMigrationFlowIntegrationTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final String SOURCE = "내일 저녁 7시에 알고리즘 공부 1시간";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private RecommendationRepository recommendations;
    @Autowired private CalendarService calendar;
    @MockitoBean private OpenAiClient openAi;
    @MockitoBean private GoogleCalendarClient google;
    @MockitoBean private GoogleOAuthClient oauth;
    @MockitoBean private Clock clock;

    @BeforeEach
    void 고정_시각과_외부_API_응답을_준비한다() {
        when(clock.getZone()).thenReturn(SEOUL);
        when(clock.instant()).thenReturn(TODAY.atTime(10, 0).atZone(SEOUL).toInstant());
        when(openAi.parseTask(eq(SOURCE), eq(TODAY))).thenReturn(new ParsedTaskResponse(
            "알고리즘 공부", TaskCategory.STUDY, 60, TODAY.plusDays(1), null, null,
            PreferredTimeRange.EVENING, null, null, "LLM 요약", false, false, LocalTime.of(19, 0), null));
        when(oauth.exchangeCode("m7-fixture-code")).thenReturn(new GoogleTokenResponse()
            .setAccessToken("m7-test-only-access").setRefreshToken("m7-test-only-refresh").setExpiresInSeconds(3600L));
        when(google.getBusyBlocks(anyString(), anyString(), any(), any())).thenReturn(List.of());
        when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(false);
        when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(call -> call.getArgument(2));
    }

    @Test
    void 신규_사용자는_빈_데이터에서_설정하고_미연동_추천_후_명시적으로_연결_등록한다() throws Exception {
        Session session = 회원가입();
        요청(get("/api/auth/me"), session, 200);
        assertThat(요청(get("/timetable-slots"), session, 200).size()).isZero();
        assertThat(요청(get("/tasks"), session, 200).size()).isZero();
        assertThat(요청(get("/preferences"), session, 200).path("customized").asBoolean()).isFalse();
        assertThat(요청(get("/api/calendar/status"), session, 200).path("connected").asBoolean()).isFalse();

        long slotId = 요청(json(post("/timetable-slots"), 수업("MINT")), session, 201).path("id").asLong();
        assertThat(요청(json(put("/timetable-slots/" + slotId), 수업("ROSE")), session, 200).path("color").asText()).isEqualTo("ROSE");
        assertThat(요청(get("/timetable-slots/" + slotId), session, 200).path("color").asText()).isEqualTo("ROSE");
        요청(json(put("/preferences"), Map.of("preferredTimeRange", "EVENING", "scheduleStartTime", "09:00",
            "scheduleEndTime", "22:00", "minimumGapMinutes", 15, "scheduleDensity", "BALANCED", "deadlineTiming", "BALANCED")), session, 200);
        assertThat(요청(get("/preferences"), session, 200).path("minimumGapMinutes").asInt()).isEqualTo(15);

        long taskId = 할_일_저장(session);
        JsonNode generated = 요청(post("/tasks/" + taskId + "/recommendations"), session, 200);
        assertThat(generated.path("recommendations").size()).isBetween(1, 3);
        long candidateId = generated.path("recommendations").get(0).path("id").asLong();
        assertThat(요청(get("/tasks/" + taskId), session, 200).path("status").asText()).isEqualTo("UNSCHEDULED");
        verifyNoInteractions(google);
        assertThat(요청(post("/recommendations/" + candidateId + "/select"), session, 400).path("code").asText())
            .isEqualTo("GOOGLE_CALENDAR_NOT_CONNECTED");
        verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());

        Calendar_연결_경계를_Stub으로_완료한다(session);
        assertThat(요청(get("/api/calendar/status"), session, 200).path("connected").asBoolean()).isTrue();
        JsonNode selected = 요청(post("/recommendations/" + candidateId + "/select"), session, 200);
        assertThat(selected.path("status").asText()).isEqualTo("SYNCED");
        assertThat(selected.path("syncRetryable").asBoolean()).isFalse();
        JsonNode restored = 요청(get("/tasks/" + taskId), session, 200);
        assertThat(restored.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(restored.path("scheduledStartAt").asText()).isEqualTo(selected.path("recommendedStartAt").asText());
        assertThat(restored.path("description").asText()).isEqualTo(SOURCE);
        assertThat(요청(get("/tasks/" + taskId + "/recommendations?includeHistory=true"), session, 200))
            .anySatisfy(item -> assertThat(item.path("status").asText()).isEqualTo("SYNCED"));
        요청(post("/recommendations/" + candidateId + "/select"), session, 200);
        verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
        요청(delete("/timetable-slots/" + slotId), session, 204);
        assertThat(요청(get("/timetable-slots"), session, 200).size()).isZero();
    }

    @Test
    void 기존_Task의_실패_동기화를_같은_ID로_복구하고_사용자_격리와_로그아웃을_유지한다() throws Exception {
        Session session = 회원가입();
        Calendar_연결_경계를_Stub으로_완료한다(session);
        long taskId = 할_일_저장(session);
        long candidateId = 요청(post("/tasks/" + taskId + "/recommendations"), session, 200)
            .path("recommendations").get(0).path("id").asLong();
        when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
            .thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED))
            .thenAnswer(call -> call.getArgument(2));
        JsonNode failed = 요청(post("/recommendations/" + candidateId + "/select"), session, 200);
        assertThat(failed.path("status").asText()).isEqualTo("SYNC_FAILED");
        assertThat(failed.path("syncRetryable").asBoolean()).isTrue();
        String eventId = recommendations.findById(candidateId).orElseThrow().getGoogleEventId();
        assertThat(eventId).matches("[0-9a-f]{32}");
        assertThat(요청(get("/tasks/" + taskId), session, 200).path("status").asText()).isEqualTo("SCHEDULED");
        JsonNode history = 요청(get("/tasks/" + taskId + "/recommendations?includeHistory=true"), session, 200);
        assertThat(history).anySatisfy(item -> {
            assertThat(item.path("id").asLong()).isEqualTo(candidateId);
            assertThat(item.path("syncRetryable").asBoolean()).isTrue();
        });

        Session other = 회원가입();
        assertThat(요청(get("/tasks"), other, 200).size()).isZero();
        assertThat(요청(get("/tasks/" + taskId), other, 404).path("code").asText()).isEqualTo("NOT_FOUND_TASK");
        assertThat(요청(get("/tasks/" + taskId + "/recommendations?includeHistory=true"), other, 404)
            .path("code").asText()).isEqualTo("NOT_FOUND_TASK");
        assertThat(요청(post("/recommendations/" + candidateId + "/select"), other, 404).path("code").asText())
            .isEqualTo("NOT_FOUND_RECOMMENDATION");
        assertThat(요청(post("/recommendations/" + candidateId + "/select"), session, 200).path("status").asText()).isEqualTo("SYNCED");
        assertThat(recommendations.findById(candidateId).orElseThrow().getGoogleEventId()).isEqualTo(eventId);
        verify(google, times(2)).createEvent(anyString(), anyString(), eq(eventId), anyString(), any(), any(), any());
        요청(post("/api/auth/logout"), session, 204);
        요청(json(post("/api/auth/refresh"), Map.of("refreshToken", session.refreshToken)), null, 401);
        JsonNode login = 요청(json(post("/api/auth/login"), Map.of("email", session.email, "password", "m7-password123")), null, 200);
        Session loggedIn = new Session(session.email, login.path("accessToken").asText(), login.path("refreshToken").asText());
        assertThat(요청(get("/tasks/" + taskId), loggedIn, 200).path("status").asText()).isEqualTo("SCHEDULED");
    }

    private long 할_일_저장(Session session) throws Exception {
        JsonNode task = 요청(json(post("/tasks/natural-language"), Map.of("message", SOURCE)), session, 201);
        assertThat(task.path("status").asText()).isEqualTo("UNSCHEDULED");
        assertThat(task.path("preferredDate").asText()).isEqualTo("2026-10-26");
        assertThat(task.path("preferredStartTime").asText()).isEqualTo("19:00:00");
        assertThat(task.path("dateRequired").asBoolean()).isFalse();
        assertThat(task.path("timeRequired").asBoolean()).isFalse();
        return task.path("id").asLong();
    }

    private void Calendar_연결_경계를_Stub으로_완료한다(Session session) {
        // Google 동의/HTTP callback의 E2E가 아닌, 토큰 교환 외부 경계를 Stub한 실제 연결 Service다.
        calendar.connect("m7-fixture-code", users.findByEmail(session.email).orElseThrow().getId());
    }

    private Session 회원가입() throws Exception {
        String email = "m7-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        JsonNode token = 요청(json(post("/api/auth/register"), Map.of("name", "통합 학생", "email", email,
            "password", "m7-password123")), null, 201);
        assertThat(token.path("accessToken").asText()).isNotBlank();
        assertThat(token.path("refreshToken").asText()).isNotBlank();
        return new Session(email, token.path("accessToken").asText(), token.path("refreshToken").asText());
    }

    private Map<String, String> 수업(String color) {
        return Map.of("subjectName", "알고리즘", "dayOfWeek", "MONDAY", "location", "공학관",
            "startTime", "10:00", "endTime", "11:00", "color", color);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode 요청(MockHttpServletRequestBuilder request, Session session, int statusCode) throws Exception {
        if (session != null) request.header("Authorization", "Bearer " + session.accessToken);
        String body = mvc.perform(request).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? mapper.nullNode() : mapper.readTree(body);
    }

    private record Session(String email, String accessToken, String refreshToken) {}
}
