package me.timetablescheduler.domain.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.RecommendationException;
import org.junit.jupiter.api.Test;

class RecommendationTest {

	@Test
	void 추천_후보를_생성하면_PROPOSED_상태가_된다() {
		Recommendation recommendation = recommendation();

		assertEquals(RecommendationStatus.PROPOSED, recommendation.getStatus());
		assertEquals(1, recommendation.getRank());
		assertEquals(80, recommendation.getScore());
		assertEquals(LocalDateTime.of(2026, 5, 18, 10, 0), recommendation.getRecommendedStartAt());
		assertEquals(LocalDateTime.of(2026, 5, 18, 11, 0), recommendation.getRecommendedEndAt());
	}

	@Test
	void 추천_후보_시간이_Task_소요시간과_다르면_생성할_수_없다() {
		User user = user();
		RecommendationException exception = assertThrows(RecommendationException.class, () -> Recommendation.create(
			user,
			task(user),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			LocalDateTime.of(2026, 5, 18, 10, 30),
			1,
			80,
			"선호 시간대와 일치합니다."
		));

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION_TIME, exception.getExceptionCode());
	}

	@Test
	void 추천_후보_종료시간이_시작시간보다_빠르거나_같으면_생성할_수_없다() {
		User user = user();
		RecommendationException exception = assertThrows(RecommendationException.class, () -> Recommendation.create(
			user,
			task(user),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			1,
			80,
			"선호 시간대와 일치합니다."
		));

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION_TIME, exception.getExceptionCode());
	}

	@Test
	void 추천_순위는_1이상이어야_한다() {
		User user = user();
		RecommendationException exception = assertThrows(RecommendationException.class, () -> Recommendation.create(
			user,
			task(user),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			LocalDateTime.of(2026, 5, 18, 11, 0),
			0,
			80,
			"선호 시간대와 일치합니다."
		));

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION, exception.getExceptionCode());
	}

	@Test
	void 추천_점수는_0이상이어야_한다() {
		User user = user();
		RecommendationException exception = assertThrows(RecommendationException.class, () -> Recommendation.create(
			user,
			task(user),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			LocalDateTime.of(2026, 5, 18, 11, 0),
			1,
			-1,
			"선호 시간대와 일치합니다."
		));

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION, exception.getExceptionCode());
	}

	@Test
	void 추천_후보를_선택하면_SELECTED가_된다() {
		Recommendation recommendation = recommendation();

		recommendation.select();

		assertEquals(RecommendationStatus.SELECTED, recommendation.getStatus());
	}

	@Test
	void 선택된_추천_후보는_SYNCED가_될_수_있다() {
		Recommendation recommendation = recommendation();
		recommendation.select();

		recommendation.sync("google-event-id");

		assertEquals(RecommendationStatus.SYNCED, recommendation.getStatus());
		assertEquals("google-event-id", recommendation.getGoogleEventId());
	}

	@Test
	void 선택되지_않은_추천_후보는_SYNCED가_될_수_없다() {
		Recommendation recommendation = recommendation();

		RecommendationException exception = assertThrows(
			RecommendationException.class,
			() -> recommendation.sync("google-event-id")
		);

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void Google_Event_Id가_없으면_SYNCED가_될_수_없다() {
		Recommendation recommendation = recommendation();
		recommendation.select();

		RecommendationException exception = assertThrows(
			RecommendationException.class,
			() -> recommendation.sync(" ")
		);

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION, exception.getExceptionCode());
	}

	@Test
	void 선택된_추천_후보는_SYNC_FAILED가_될_수_있다() {
		Recommendation recommendation = recommendation();
		recommendation.select();

		recommendation.failSync();

		assertEquals(RecommendationStatus.SYNC_FAILED, recommendation.getStatus());
	}

	@Test
	void 선택되지_않은_추천_후보는_SYNC_FAILED가_될_수_없다() {
		Recommendation recommendation = recommendation();

		RecommendationException exception = assertThrows(RecommendationException.class, recommendation::failSync);

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 추천_후보는_거절될_수_있다() {
		Recommendation recommendation = recommendation();

		recommendation.reject();

		assertEquals(RecommendationStatus.REJECTED, recommendation.getStatus());
	}

	@Test
	void 추천_후보는_만료될_수_있다() {
		Recommendation recommendation = recommendation();

		recommendation.expire();

		assertEquals(RecommendationStatus.EXPIRED, recommendation.getStatus());
	}

	@Test
	void 이미_선택된_추천은_거절할_수_없다() {
		Recommendation recommendation = recommendation();
		recommendation.select();

		RecommendationException exception = assertThrows(RecommendationException.class, recommendation::reject);

		assertEquals(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION, exception.getExceptionCode());
	}

	private Recommendation recommendation() {
		User user = user();
		return Recommendation.create(
			user,
			task(user),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			LocalDateTime.of(2026, 5, 18, 11, 0),
			1,
			80,
			"선호 시간대와 일치합니다."
		);
	}

	private Task task(User user) {
		return Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.MONDAY,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"월요일에 과제할 시간 잡아줘"
		);
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
