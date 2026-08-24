package com.auctus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class OcrServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(OcrServiceApplication.class, args);
        System.out.println("========================================");
        System.out.println("OCR SERVICE STARTED - Port 8083");
        System.out.println("========================================");
    }
}