package com.auctus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableDiscoveryClient
@EnableJpaRepositories(basePackages = "com.auctus.repository")
public class ChequeServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ChequeServiceApplication.class, args);
        System.out.println("========================================");
        System.out.println("CHEQUE SERVICE STARTED");
        System.out.println("📍 http://localhost:8082");
        System.out.println("========================================");
    }
}