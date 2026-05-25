package me.timetablescheduler.domain.recommendation.policy;

import java.time.LocalDateTime;

/// todo : 이것도 레코드 안에서 검증(컴팩트 생성자)
/// 이걸 그대로 둘 것인지 검증을 밖으로 뺄 것인지 설계 고민 해야 함
public record BusyBlock(
	LocalDateTime startAt,
	LocalDateTime endAt
) {
	// compact constructor
	public BusyBlock {
		if (startAt == null || endAt == null) {
			throw new IllegalArgumentException("busy block은 비어있을 수 없습니다");
		}

		if (!startAt.isBefore(endAt)) {
			throw new IllegalArgumentException("busy block 시작 시간은 종료 시간보다 이전이어야 합니다.");
		}
	}
}
