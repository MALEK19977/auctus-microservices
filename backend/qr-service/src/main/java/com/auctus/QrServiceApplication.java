package com.auctus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class QrServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(QrServiceApplication.class, args);
        System.out.println("========================================");
        System.out.println("QR SERVICE STARTED - Port 8084");
        System.out.println("========================================");
    }
}