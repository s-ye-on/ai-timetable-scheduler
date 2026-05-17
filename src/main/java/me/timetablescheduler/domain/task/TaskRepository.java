package me.timetablescheduler.domain.task;

import me.timetablescheduler.domain.task.type.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
	Optional<Task> findByIdAndUserId(Long id, Long userId);

	List<Task> findAllByUserIdOrderByCreatedAtDesc(Long userId);

	List<Task> findAllByUserIdAndStatus(Long userId, TaskStatus taskStatus);
}
