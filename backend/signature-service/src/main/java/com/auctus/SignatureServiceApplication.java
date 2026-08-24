package com.auctus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class SignatureServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SignatureServiceApplication.class, args);
        System.out.println("========================================");
        System.out.println("SIGNATURE SERVICE STARTED - Port 8085");
        System.out.println("========================================");
    }
}