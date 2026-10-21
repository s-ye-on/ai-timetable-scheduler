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
    private Task 요청_Task_생성(LocalDate date, DayOfWeek day, LocalDate first, LocalDate last,
        PreferredTimeRange range, Boolean dateHard, Boolean timeHard, LocalTime exact) {
        return Task.create(user, "의도 보존", TaskCategory.STUDY, 120, date, day, first, last,
            range, null, TaskPriority.NORMAL, "테스트 요청", dateHard, timeHard, exact);
    }
    private Preference 하루_선호_설정_생성() {
        return Preference.create(user, PreferredTimeRange.ANYTIME, LocalTime.of(9,0), LocalTime.of(22,0),
            0, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);
    }
    @Test
    void 명시시각은_간격밖이어도_별도후보이며_같은_충돌검사를_거친다() {
        var task = 요청_Task_생성(DATE.plusDays(3), null, null, null, PreferredTimeRange.EVENING,
            false, false, LocalTime.of(19,10));
        var free = policy.generateCandidates(task, 하루_선호_설정_생성(), List.of(), List.of(), List.of(), NOW);
        var exact = free.stream().filter(c -> c.startAt().equals(DATE.plusDays(3).atTime(19,10))).findFirst().orElseThrow();
        assertEquals(RecommendationMatchType.EXACT, exact.matchType());
        assertEquals(89, exact.score()); // 50 + 30 + (15 - 3*2), 기존 가중치 그대로
        assertEquals(DATE.plusDays(3).atTime(21,10), exact.endAt());
        var blocked = policy.generateCandidates(task, 하루_선호_설정_생성(), List.of(), List.of(),
            List.of(new BusyBlock(DATE.plusDays(3).atTime(20,0), DATE.plusDays(3).atTime(20,30))), NOW);
        assertFalse(blocked.stream().anyMatch(c -> c.startAt().equals(exact.startAt())));
        assertTrue(free.stream().anyMatch(c -> c.startAt().toLocalTime().equals(LocalTime.of(18,30))));
    }
    @Test
    void 필수시각은_정확시각을_강제하고_선호시간대전체포함을_추가로_강제하지않는다() {
        var task = 요청_Task_생성(DATE.plusDays(3), null, null, null, PreferredTimeRange.LUNCH,
            true, true, LocalTime.of(13,0));
        var candidates = policy.generateCandidates(task, 하루_선호_설정_생성(), List.of(), List.of(), List.of(), NOW);
        assertEquals(1, candidates.size());
        assertEquals(DATE.plusDays(3).atTime(13,0), candidates.getFirst().startAt());
        assertEquals(59, candidates.getFirst().score()); // 점심 전체포함 가점은 기존대로 0
        assertFalse(policy.isSelectable(task, 하루_선호_설정_생성(), List.of(), List.of(), List.of(),
            DATE.plusDays(3).atTime(13,30), DATE.plusDays(3).atTime(15,30), NOW));
    }
    @Test
    void 선호날짜는_요청일부터14일이고_기존null은_해당날짜제한을유지한다() {
        LocalDate d = DATE.plusDays(3);
        var soft = 요청_Task_생성(d, null, null, null, null, false, false, null);
        assertEquals(14, policy.resolveCandidateDates(soft, DATE).size());
        assertEquals(d, policy.resolveCandidateDates(soft, DATE).getFirst());
        assertEquals(d.plusDays(13), policy.resolveCandidateDates(soft, DATE).getLast());
        var legacy = 요청_Task_생성(d, null, null, null, PreferredTimeRange.LUNCH, null, null, null);
        assertEquals(List.of(d), policy.resolveCandidateDates(legacy, DATE));
        assertTrue(policy.generateCandidates(legacy, 하루_선호_설정_생성(), List.of(), List.of(), List.of(), NOW)
            .stream().anyMatch(c -> c.startAt().toLocalTime().equals(LocalTime.of(9,0))));
    }
    @Test
    void 날짜미지정_기간요일공존_기간마감_과거날짜경계를지킨다() {
        var noDate = 요청_Task_생성(null,null,null,null,null,false,false,null);
        assertEquals(14, policy.resolveCandidateDates(noDate, DATE).size());
        var weekly = 요청_Task_생성(null,DayOfWeek.TUESDAY,DATE,DATE.plusDays(6),null,false,false,null);
        assertEquals(7, policy.resolveCandidateDates(weekly,DATE).size());
        assertTrue(policy.generateCandidates(weekly,하루_선호_설정_생성(),List.of(),List.of(),List.of(),NOW)
            .stream().anyMatch(c -> c.matchType()==RecommendationMatchType.DATE_CHANGED));
        var hard = 요청_Task_생성(null,DayOfWeek.TUESDAY,DATE,DATE.plusDays(6),null,true,false,null);
        assertEquals(List.of(DATE.plusDays(1)), policy.resolveCandidateDates(hard,DATE));
        var past = 요청_Task_생성(DATE.minusDays(1),null,null,null,null,false,false,null);
        assertEquals(DATE, policy.resolveCandidateDates(past,DATE).getFirst());
        assertEquals(DATE.plusDays(12),policy.resolveCandidateDates(past,DATE).getLast());
        Task deadline = Task.create(user,"마감",TaskCategory.STUDY,60,null,null,null,null,null,
            DATE.plusDays(2),TaskPriority.NORMAL,"마감",false,false,null);
        assertEquals(3, policy.resolveCandidateDates(deadline,DATE).size());
    }
    @Test
    void 정확시각과격자후보중복없고_시간대만필수이면구간전체를지킨다() {
        var task = 요청_Task_생성(DATE.plusDays(3),null,null,null,PreferredTimeRange.EVENING,true,false,LocalTime.of(19,0));
        var candidates = policy.generateCandidates(task,하루_선호_설정_생성(),List.of(),List.of(),List.of(),NOW);
        assertEquals(1, candidates.stream().filter(c -> c.startAt().toLocalTime().equals(LocalTime.of(19,0))).count());
        var hardBand = 요청_Task_생성(DATE.plusDays(3),null,null,null,PreferredTimeRange.EVENING,true,true,null);
        assertTrue(policy.generateCandidates(hardBand,하루_선호_설정_생성(),List.of(),List.of(),List.of(),NOW)
            .stream().allMatch(c -> !c.startAt().toLocalTime().isBefore(LocalTime.of(18,0))
                && !c.endAt().toLocalTime().isAfter(LocalTime.of(22,0))));
    }

    @Test
    void 요일선호의_대안과_특정일선호의_대안은_요청일이지나도_미래이면선택가능하다() {
        var weekly = 요청_Task_생성(null,DayOfWeek.THURSDAY,null,null,PreferredTimeRange.EVENING,false,false,LocalTime.of(19,0));
        LocalDate thursday=DATE.plusDays(3);
        LocalDateTime start=thursday.plusDays(1).atTime(19,0);
        assertTrue(policy.generateCandidates(weekly,하루_선호_설정_생성(),List.of(),List.of(),List.of(),thursday.atTime(8,0))
            .stream().anyMatch(c->c.startAt().equals(start)));
        assertTrue(policy.isSelectable(weekly,하루_선호_설정_생성(),List.of(),List.of(),List.of(),start,start.plusHours(2),thursday.plusDays(1).atTime(8,0)));
        var dated=요청_Task_생성(thursday,null,null,null,PreferredTimeRange.EVENING,false,false,LocalTime.of(19,0));
        assertTrue(policy.isSelectable(dated,하루_선호_설정_생성(),List.of(),List.of(),List.of(),start,start.plusHours(2),thursday.plusDays(1).atTime(8,0)));
        assertFalse(policy.isSelectable(dated,하루_선호_설정_생성(),List.of(),List.of(),List.of(),
            thursday.plusDays(14).atTime(19,0),thursday.plusDays(14).atTime(21,0),thursday.plusDays(1).atTime(8,0)));
    }


    private Task 원조건_Task() {
        return 요청_Task_생성(DATE.plusDays(3), null, null, null, PreferredTimeRange.EVENING,
            false, false, LocalTime.of(19,0));
    }
    private String 제외_사유(Task task, Preference preference, List<TimetableSlot> slots,
        List<Task> scheduled, List<BusyBlock> calendar, LocalDateTime now) {
        return policy.explainOriginalConditionExclusion(task, preference, slots, scheduled, calendar, now, false);
    }
    @Test
    void 학교_시간표와_직접_겹친_원조건은_수업_충돌만_안내한다() {
        var slot = TimetableSlot.create(user, "수업", DayOfWeek.THURSDAY, "강의실", LocalTime.of(19,30), LocalTime.of(20,30));
        String reason = 제외_사유(원조건_Task(), 하루_선호_설정_생성(), List.of(slot), List.of(), List.of(), NOW);
        assertTrue(reason.contains("학교 시간표가 겹쳐요"));
        assertFalse(reason.contains("Google 캘린더"));
        assertFalse(reason.contains("여유 시간"));
    }
    @Test
    void 확정된_Task와_직접_겹친_원조건은_Task_충돌을_안내한다() {
        var scheduled = 요청_Task_생성(null,null,null,null,null,false,false,null);
        scheduled.schedule(DATE.plusDays(3).atTime(20,0), DATE.plusDays(3).atTime(22,0));
        String reason = 제외_사유(원조건_Task(), 하루_선호_설정_생성(), List.of(), List.of(scheduled), List.of(), NOW);
        assertTrue(reason.contains("이미 확정된 일정이 겹쳐요"));
        assertFalse(reason.contains("학교 시간표"));
    }
    @Test
    void 직접_겹치지_않고_여유_시간만_부족하면_구분해서_안내한다() {
        var preference = Preference.create(user, PreferredTimeRange.ANYTIME, LocalTime.of(9,0), LocalTime.of(22,0),
            30, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);
        var near = new BusyBlock(DATE.plusDays(3).atTime(18,0), DATE.plusDays(3).atTime(18,45));
        String reason = 제외_사유(원조건_Task(), preference, List.of(), List.of(), List.of(near), NOW);
        assertTrue(reason.contains("Google 캘린더 일정 전후의 최소 여유 시간이 부족"));
        assertFalse(reason.contains("겹쳐요"));
        var boundary = new BusyBlock(near.startAt(), DATE.plusDays(3).atTime(18,30));
        assertEquals("", 제외_사유(원조건_Task(), preference, List.of(), List.of(), List.of(boundary), NOW));
    }
    @Test
    void 수업과_Task의_최소_여유_시간도_출처별로_구분한다() {
        var preference = Preference.create(user, PreferredTimeRange.ANYTIME, LocalTime.of(9,0), LocalTime.of(22,0),
            30, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);
        var slot = TimetableSlot.create(user, "수업", DayOfWeek.THURSDAY, "강의실", LocalTime.of(18,0), LocalTime.of(18,45));
        assertTrue(제외_사유(원조건_Task(), preference, List.of(slot), List.of(), List.of(), NOW).contains("학교 시간표 전후의 최소 여유 시간"));
        var scheduled = 요청_Task_생성(null,null,null,null,null,false,false,null);
        scheduled.schedule(DATE.plusDays(3).atTime(16,45), DATE.plusDays(3).atTime(18,45));
        assertTrue(제외_사유(원조건_Task(), preference, List.of(), List.of(scheduled), List.of(), NOW).contains("이미 확정된 일정 전후의 최소 여유 시간"));
    }
    @Test
    void 여러_출처의_실제_충돌은_함께_안내한다() {
        var slot = TimetableSlot.create(user, "수업", DayOfWeek.THURSDAY, "강의실", LocalTime.of(19,0), LocalTime.of(20,0));
        var block = new BusyBlock(DATE.plusDays(3).atTime(20,0), DATE.plusDays(3).atTime(21,0));
        String reason = 제외_사유(원조건_Task(), 하루_선호_설정_생성(), List.of(slot), List.of(), List.of(block), NOW);
        assertTrue(reason.contains("학교 시간표와 Google 캘린더 일정이 겹쳐요"));
    }
    @Test
    void 일부_원조건만_충돌하고_유효한_원조건이_있으면_전체_충돌처럼_안내하지_않는다() {
        var task = 요청_Task_생성(DATE.plusDays(3), null, null,null,PreferredTimeRange.EVENING,false,false,null);
        var block = new BusyBlock(DATE.plusDays(3).atTime(18,0), DATE.plusDays(3).atTime(18,30));
        assertEquals("", 제외_사유(task, 하루_선호_설정_생성(),List.of(),List.of(),List.of(block),NOW));
    }
    @Test
    void 과거_요청은_충돌이_있어도_일정_충돌이라고_오안내하지_않는다() {
        var block = new BusyBlock(DATE.plusDays(3).atTime(19,0), DATE.plusDays(3).atTime(20,0));
        String reason = 제외_사유(원조건_Task(), 하루_선호_설정_생성(),List.of(),List.of(),List.of(block), DATE.plusDays(3).atTime(19,0));
        assertTrue(reason.contains("이미 지났어요"));
        assertFalse(reason.contains("Google 캘린더"));
    }
    @Test
    void 가용_시간_밖의_원조건은_Calendar_충돌로_추측하지_않는다() {
        var task = 요청_Task_생성(DATE.plusDays(3),null,null,null,null,false,false,LocalTime.of(23,0));
        var block = new BusyBlock(DATE.plusDays(3).atTime(23,0), DATE.plusDays(4).atTime(1,0));
        String reason = 제외_사유(task,하루_선호_설정_생성(),List.of(),List.of(),List.of(block),NOW);
        assertTrue(reason.contains("가용 시간"));
        assertFalse(reason.contains("Google 캘린더"));
    }
    @Test
    void 인접_날짜의_수업에_닿는_여유_시간도_동일하게_안내한다() {
        var task = 요청_Task_생성(DATE,null,null,null,null,false,false,LocalTime.of(0,30));
        var preference = Preference.create(user,PreferredTimeRange.ANYTIME,LocalTime.of(0,0),LocalTime.of(4,0),60,ScheduleDensity.BALANCED,DeadlineTiming.ASAP);
        var slot = TimetableSlot.create(user,"야간 수업",DayOfWeek.SUNDAY,"강의실",LocalTime.of(23,0),LocalTime.of(23,59));
        assertTrue(제외_사유(task,preference,List.of(slot),List.of(),List.of(),NOW).contains("학교 시간표 전후의 최소 여유 시간"));
    }
    @Test
    void 원조건이_없거나_Calendar_검증결과가_없으면_충돌을_추측하지_않는다() {
        var general = 요청_Task_생성(null,null,null,null,null,false,false,null);
        assertEquals("",제외_사유(general,하루_선호_설정_생성(),List.of(),List.of(),List.of(),NOW));
        assertEquals("",제외_사유(원조건_Task(),하루_선호_설정_생성(),List.of(),List.of(),null,NOW));
    }
    @Test
    void 안내_계산_전후_후보의_시각과_점수는_변하지_않는다() {
        var task = 원조건_Task();
        var block = new BusyBlock(DATE.plusDays(3).atTime(19,0), DATE.plusDays(3).atTime(20,0));
        var before = policy.generateCandidates(task,하루_선호_설정_생성(),List.of(),List.of(),List.of(block),NOW);
        제외_사유(task,하루_선호_설정_생성(),List.of(),List.of(),List.of(block),NOW);
        var after = policy.generateCandidates(task,하루_선호_설정_생성(),List.of(),List.of(),List.of(block),NOW);
        assertEquals(before, after);
    }
}
