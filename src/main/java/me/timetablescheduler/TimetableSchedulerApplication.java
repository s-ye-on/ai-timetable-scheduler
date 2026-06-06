package me.timetablescheduler;

import me.timetablescheduler.domain.calendar.external.GoogleCalendarProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties(GoogleCalendarProperties.class)
@SpringBootApplication
public class TimetableSchedulerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TimetableSchedulerApplication.class, args);
	}

}
