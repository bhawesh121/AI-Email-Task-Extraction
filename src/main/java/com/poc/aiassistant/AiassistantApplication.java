package com.poc.aiassistant;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiassistantApplication {

	public static void main(String[] args) {
		// Keep the backend/JDBC/Hibernate process on UTC so PostgreSQL receives
		// the portable IANA timezone identifier "UTC" instead of platform
		// aliases such as "Asia/Calcutta".
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(AiassistantApplication.class, args);
	}

}
