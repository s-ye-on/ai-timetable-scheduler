package me.timetablescheduler.domain.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.*;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.*;
import me.timetablescheduler.domain.recommendation.type.*;
import me.timetablescheduler.domain.recommendation.policy.*;

class RecommendationQualityExamplesTest {
    static final LocalDate TODAY = LocalDate.of(2026,10,19);
    static final LocalDateTime NOW = TODAY.atTime(8,0);
    final User user = new User("Fixture", "fixture@example.com", "encodedPassword");
    final RecommendationPolicy policy = new RecommendationPolicy(Clock.fixed(NOW.toInstant(ZoneOffset.ofHours(9)), ZoneId.of("Asia/Seoul")));
    final Preference preference = Preference.create(user,PreferredTimeRange.ANYTIME,LocalTime.of(9,0),LocalTime.of(22,0),0,ScheduleDensity.BALANCED,DeadlineTiming.ASAP);
    final RecommendationService service = new RecommendationService(null,null,null,null,null,policy,null,null,null,null);

    @SuppressWarnings("unchecked")
    List<CandidateSlot> 후보_선정(Task task) {
        var candidates = policy.generateCandidates(task,preference,List.of(),List.of(),List.of(),NOW).stream()
            .sorted(Comparator.comparingInt(CandidateSlot::score).reversed()).toList();
        List<CandidateSlot> top = ReflectionTestUtils.invokeMethod(service,"selectDiverseCandidates",candidates);
        assertTrue(top.size()<=3);
        for(int i=1;i<top.size();i++) assertTrue(top.get(i-1).score()>=top.get(i).score());
        for(int i=0;i<top.size();i++) {
            var c=top.get(i);
            System.out.printf("T09_FIXTURE rank=%d start=%s end=%s type=%s raw=%d display=%.1f reason=%s%n",
                i+1,c.startAt(),c.endAt(),c.matchType(),c.score(),Math.round(c.score()/140.0*1000)/10.0,c.reason());
        }
        return top;
    }
    @Test
    void 목요일_저녁7시_2시간은_정확후보와_의미있는_대안을_선정한다() {
        var task=Task.create(user,"알고리즘",TaskCategory.STUDY,120,null,DayOfWeek.THURSDAY,null,null,
            PreferredTimeRange.EVENING,null,TaskPriority.NORMAL,"목요일 저녁 7시에 알고리즘 공부 2시간",false,false,LocalTime.of(19,0));
        var top=후보_선정(task);
        assertEquals(3,top.size());
        var exact=top.stream().filter(c->c.matchType()==RecommendationMatchType.EXACT).findFirst().orElseThrow();
        assertEquals(TODAY.plusDays(3).atTime(19,0),exact.startAt());
        assertEquals(89,exact.score());
        assertTrue(top.stream().anyMatch(c->c.matchType()==RecommendationMatchType.DATE_CHANGED && c.startAt().toLocalTime().equals(LocalTime.of(19,0))));
        assertTrue(top.stream().anyMatch(c->c.matchType()==RecommendationMatchType.TIME_CHANGED));
    }
    @Test
    void 목요일_19시10분도_정확한시각을_보존한다() {
        var task=Task.create(user,"공부",TaskCategory.STUDY,120,null,DayOfWeek.THURSDAY,null,null,
            PreferredTimeRange.EVENING,null,TaskPriority.NORMAL,"목요일 19시 10분에 공부하고 싶어",false,false,LocalTime.of(19,10));
        var top=후보_선정(task);
        var exact=top.stream().filter(c->c.matchType()==RecommendationMatchType.EXACT).findFirst().orElseThrow();
        assertEquals(TODAY.plusDays(3).atTime(19,10),exact.startAt());
        assertEquals(TODAY.plusDays(3).atTime(21,10),exact.endAt());
        assertEquals(89,exact.score());
    }
    @Test
    void 화요일_점심선호는_다른날_점심과_같은날_다른시간을_함께_제공한다() {
        var task=Task.create(user,"점심약속",TaskCategory.APPOINTMENT,60,null,DayOfWeek.TUESDAY,null,null,
            PreferredTimeRange.LUNCH,null,TaskPriority.NORMAL,"화요일 점심이면 좋겠어. 안 되면 다른 날도 괜찮아",false,false,null);
        var top=후보_선정(task);
        var exact=top.stream().filter(c->c.matchType()==RecommendationMatchType.EXACT).findFirst().orElseThrow();
        assertEquals(TODAY.plusDays(1).atTime(12,0),exact.startAt());
        assertEquals(93,exact.score());
        assertTrue(top.stream().anyMatch(c->c.matchType()==RecommendationMatchType.DATE_CHANGED));
        assertTrue(top.stream().anyMatch(c->c.matchType()==RecommendationMatchType.TIME_CHANGED));
    }
    @Test
    void 날짜미지정과제는_기본14일에서_서로다른날의대표를준다() {
        var task=Task.create(user,"과제",TaskCategory.ASSIGNMENT,60,null,null,null,null,
            null,null,TaskPriority.NORMAL,"과제할 시간 1시간 추천해 줘",false,false,null);
        var top=후보_선정(task);
        assertEquals(3,top.size());
        assertEquals(3,top.stream().map(c->c.startAt().toLocalDate()).distinct().count());
        assertEquals(List.of(65,63,61),top.stream().map(CandidateSlot::score).toList());
        assertTrue(top.stream().allMatch(c->c.matchType()==RecommendationMatchType.GENERAL));
    }
    @Test
    void 필수날짜와시각의_후보가_하나면_세개를_억지로_채우지않는다() {
        var task=Task.create(user,"고정",TaskCategory.STUDY,120,TODAY.plusDays(3),null,null,null,
            PreferredTimeRange.EVENING,null,TaskPriority.NORMAL,"목요일 19시만",true,true,LocalTime.of(19,0));
        var top=후보_선정(task);
        assertEquals(1,top.size());
        assertEquals(RecommendationMatchType.EXACT,top.getFirst().matchType());
    }
    @Test
    void 필수날짜만있으면_같은날의_실제로다른시간대를_지나치게_제거하지않는다() {
        var task=Task.create(user,"날짜고정",TaskCategory.STUDY,60,TODAY.plusDays(3),null,null,null,
            null,null,TaskPriority.NORMAL,"목요일만 가능하고 시간은 자유",true,false,null);
        var top=후보_선정(task);
        assertEquals(3,top.size());
        assertEquals(List.of(LocalTime.of(9,0),LocalTime.of(15,0),LocalTime.of(21,0)),
            top.stream().map(c->c.startAt().toLocalTime()).toList());
    }

}
