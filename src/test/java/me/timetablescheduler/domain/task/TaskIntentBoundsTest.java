package me.timetablescheduler.domain.task;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.task.type.*;
import me.timetablescheduler.global.exception.TaskException;

class TaskIntentBoundsTest {
    final User user=new User("Tester","bounds@example.com","encoded");
    final LocalDate monday=LocalDate.of(2026,10,19);
    @Test void 필수날짜가_마감보다뒤이면_생성전에_모순을거절한다() {
        assertThrows(TaskException.class,()->Task.create(user,"과제",TaskCategory.STUDY,60,
            monday.plusDays(3),null,null,null,null,monday.plusDays(2),TaskPriority.NORMAL,"모순",true,false,null));
    }
    @Test void 허용기간안에_필수요일이_없으면_거절한다() {
        assertThrows(TaskException.class,()->Task.create(user,"과제",TaskCategory.STUDY,60,
            null,DayOfWeek.THURSDAY,monday,monday.plusDays(2),null,null,TaskPriority.NORMAL,"모순",true,false,null));
    }
    @Test void 허용기간시작이_마감보다뒤이면_거절한다() {
        assertThrows(TaskException.class,()->Task.create(user,"과제",TaskCategory.STUDY,60,
            null,null,monday.plusDays(3),monday.plusDays(4),null,monday.plusDays(2),TaskPriority.NORMAL,"모순",false,false,null));
    }
    @Test void 기존null의_데이터는_추가검증으로_재해석하지않는다() {
        assertDoesNotThrow(()->Task.create(user,"기존",TaskCategory.STUDY,60,
            monday.plusDays(3),null,null,null,null,monday.plusDays(2),TaskPriority.NORMAL,"기존"));
    }
}
