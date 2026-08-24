package com.auctus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Messaging and calendar for the platform.
 *
 * <p>Both concerns live here because they are the same conversation between an
 * agent and an administrator: a message arranges something, an appointment
 * records it. Keeping them together avoids a service whose only job is one table.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class CollabServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollabServiceApplication.class, args);
    }
}
