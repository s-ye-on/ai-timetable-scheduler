package me.timetablescheduler.domain.recommendation.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.*;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.*;
import me.timetablescheduler.domain.recommendation.type.*;
import me.timetablescheduler.domain.timetable.TimetableSlot;

class RecommendationRequestIntentTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    private static final LocalDateTime NOW = DATE.atStartOfDay();
    private final RecommendationPolicy policy = new RecommendationPolicy(Clock.fixed(NOW.toInstant(ZoneOffset.ofHours(9)), ZoneId.of("Asia/Seoul")));
    private final User user = new User("Tester", "intent@example.com", "encodedPassword");

    @Test
    void 생성도_자정전_수업에_닿는_최소여유시간을_반영한다() {
        Task task = Task.create(user, "경계 확인", TaskCategory.STUDY, 60,
            DATE, null, null, null, null, null, TaskPriority.NORMAL, "자정 여유시간");
        Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
            LocalTime.of(0,30), LocalTime.of(3,0), 60, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);
        TimetableSlot previousClass = TimetableSlot.create(user, "야간수업", DayOfWeek.SUNDAY,
            "강의실", LocalTime.of(23,0), LocalTime.of(23,59));
        var candidates = policy.generateCandidates(task, preference, List.of(previousClass), List.of(), List.of(), NOW);
        assertFalse(candidates.stream().anyMatch(c -> c.startAt().equals(DATE.atTime(0,30))));
        assertTrue(candidates.stream().anyMatch(c -> c.startAt().equals(DATE.atTime(1,0))));
    }
    private Task request(LocalDate date, DayOfWeek day, LocalDate first, LocalDate last,
        PreferredTimeRange range, Boolean dateHard, Boolean timeHard, LocalTime exact) {
        return Task.create(user, "의도 보존", TaskCategory.STUDY, 120, date, day, first, last,
            range, null, TaskPriority.NORMAL, "테스트 요청", dateHard, timeHard, exact);
    }
    private Preference dayPreference() {
        return Preference.create(user, PreferredTimeRange.ANYTIME, LocalTime.of(9,0), LocalTime.of(22,0),
            0, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);
    }
    @Test
    void 명시시각은_간격밖이어도_별도후보이며_같은_충돌검사를_거친다() {
        var task = request(DATE.plusDays(3), null, null, null, PreferredTimeRange.EVENING,
            false, false, LocalTime.of(19,10));
        var free = policy.generateCandidates(task, dayPreference(), List.of(), List.of(), List.of(), NOW);
        var exact = free.stream().filter(c -> c.startAt().equals(DATE.plusDays(3).atTime(19,10))).findFirst().orElseThrow();
        assertEquals(RecommendationMatchType.EXACT, exact.matchType());
        assertEquals(89, exact.score()); // 50 + 30 + (15 - 3*2), 기존 가중치 그대로
        assertEquals(DATE.plusDays(3).atTime(21,10), exact.endAt());
        var blocked = policy.generateCandidates(task, dayPreference(), List.of(), List.of(),
            List.of(new BusyBlock(DATE.plusDays(3).atTime(20,0), DATE.plusDays(3).atTime(20,30))), NOW);
        assertFalse(blocked.stream().anyMatch(c -> c.startAt().equals(exact.startAt())));
        assertTrue(free.stream().anyMatch(c -> c.startAt().toLocalTime().equals(LocalTime.of(18,30))));
    }
    @Test
    void 필수시각은_정확시각을_강제하고_선호시간대전체포함을_추가로_강제하지않는다() {
        var task = request(DATE.plusDays(3), null, null, null, PreferredTimeRange.LUNCH,
            true, true, LocalTime.of(13,0));
        var candidates = policy.generateCandidates(task, dayPreference(), List.of(), List.of(), List.of(), NOW);
        assertEquals(1, candidates.size());
        assertEquals(DATE.plusDays(3).atTime(13,0), candidates.getFirst().startAt());
        assertEquals(59, candidates.getFirst().score()); // 점심 전체포함 가점은 기존대로 0
        assertFalse(policy.isSelectable(task, dayPreference(), List.of(), List.of(), List.of(),
            DATE.plusDays(3).atTime(13,30), DATE.plusDays(3).atTime(15,30), NOW));
    }
    @Test
    void 선호날짜는_요청일부터14일이고_기존null은_해당날짜제한을유지한다() {
        LocalDate d = DATE.plusDays(3);
        var soft = request(d, null, null, null, null, false, false, null);
        assertEquals(14, policy.resolveCandidateDates(soft, DATE).size());
        assertEquals(d, policy.resolveCandidateDates(soft, DATE).getFirst());
        assertEquals(d.plusDays(13), policy.resolveCandidateDates(soft, DATE).getLast());
        var legacy = request(d, null, null, null, PreferredTimeRange.LUNCH, null, null, null);
        assertEquals(List.of(d), policy.resolveCandidateDates(legacy, DATE));
        assertTrue(policy.generateCandidates(legacy, dayPreference(), List.of(), List.of(), List.of(), NOW)
            .stream().anyMatch(c -> c.startAt().toLocalTime().equals(LocalTime.of(9,0))));
    }
    @Test
    void 날짜미지정_기간요일공존_기간마감_과거날짜경계를지킨다() {
        var noDate = request(null,null,null,null,null,false,false,null);
        assertEquals(14, policy.resolveCandidateDates(noDate, DATE).size());
        var weekly = request(null,DayOfWeek.TUESDAY,DATE,DATE.plusDays(6),null,false,false,null);
        assertEquals(7, policy.resolveCandidateDates(weekly,DATE).size());
        assertTrue(policy.generateCandidates(weekly,dayPreference(),List.of(),List.of(),List.of(),NOW)
            .stream().anyMatch(c -> c.matchType()==RecommendationMatchType.DATE_CHANGED));
        var hard = request(null,DayOfWeek.TUESDAY,DATE,DATE.plusDays(6),null,true,false,null);
        assertEquals(List.of(DATE.plusDays(1)), policy.resolveCandidateDates(hard,DATE));
        var past = request(DATE.minusDays(1),null,null,null,null,false,false,null);
        assertEquals(DATE, policy.resolveCandidateDates(past,DATE).getFirst());
        assertEquals(DATE.plusDays(12),policy.resolveCandidateDates(past,DATE).getLast());
        Task deadline = Task.create(user,"마감",TaskCategory.STUDY,60,null,null,null,null,null,
            DATE.plusDays(2),TaskPriority.NORMAL,"마감",false,false,null);
        assertEquals(3, policy.resolveCandidateDates(deadline,DATE).size());
    }
    @Test
    void 정확시각과격자후보중복없고_시간대만필수이면구간전체를지킨다() {
        var task = request(DATE.plusDays(3),null,null,null,PreferredTimeRange.EVENING,true,false,LocalTime.of(19,0));
        var candidates = policy.generateCandidates(task,dayPreference(),List.of(),List.of(),List.of(),NOW);
        assertEquals(1, candidates.stream().filter(c -> c.startAt().toLocalTime().equals(LocalTime.of(19,0))).count());
        var hardBand = request(DATE.plusDays(3),null,null,null,PreferredTimeRange.EVENING,true,true,null);
        assertTrue(policy.generateCandidates(hardBand,dayPreference(),List.of(),List.of(),List.of(),NOW)
            .stream().allMatch(c -> !c.startAt().toLocalTime().isBefore(LocalTime.of(18,0))
                && !c.endAt().toLocalTime().isAfter(LocalTime.of(22,0))));
    }

    @Test
    void 요일선호의_대안과_특정일선호의_대안은_요청일이지나도_미래이면선택가능하다() {
        var weekly = request(null,DayOfWeek.THURSDAY,null,null,PreferredTimeRange.EVENING,false,false,LocalTime.of(19,0));
        LocalDate thursday=DATE.plusDays(3);
        LocalDateTime start=thursday.plusDays(1).atTime(19,0);
        assertTrue(policy.generateCandidates(weekly,dayPreference(),List.of(),List.of(),List.of(),thursday.atTime(8,0))
            .stream().anyMatch(c->c.startAt().equals(start)));
        assertTrue(policy.isSelectable(weekly,dayPreference(),List.of(),List.of(),List.of(),start,start.plusHours(2),thursday.plusDays(1).atTime(8,0)));
        var dated=request(thursday,null,null,null,PreferredTimeRange.EVENING,false,false,LocalTime.of(19,0));
        assertTrue(policy.isSelectable(dated,dayPreference(),List.of(),List.of(),List.of(),start,start.plusHours(2),thursday.plusDays(1).atTime(8,0)));
        assertFalse(policy.isSelectable(dated,dayPreference(),List.of(),List.of(),List.of(),
            thursday.plusDays(14).atTime(19,0),thursday.plusDays(14).atTime(21,0),thursday.plusDays(1).atTime(8,0)));
    }

}
