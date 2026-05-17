package me.timetablescheduler.domain.task.type;

public enum TaskPriority {
	HIGH(30, 5),
	NORMAL(15, 2),
	LOW(5, 0);

	private final int baseScore;
	private final int dailyPenalty;

	TaskPriority(int baseScore, int dailyPenalty) {
		this.baseScore = baseScore;
		this.dailyPenalty = dailyPenalty;
	}

	public int calculateDateScore(long daysFromBase) {
		return Math.max(0, baseScore - (int)daysFromBase * dailyPenalty);
	}
}
