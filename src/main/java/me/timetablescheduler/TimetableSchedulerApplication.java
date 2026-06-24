package me.timetablescheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TimetableSchedulerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TimetableSchedulerApplication.class, args);
	}

}
