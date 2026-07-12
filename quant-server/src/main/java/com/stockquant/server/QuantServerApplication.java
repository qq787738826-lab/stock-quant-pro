package com.stockquant.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.stockquant")
public class QuantServerApplication {
    public static void main(String[] args) { SpringApplication.run(QuantServerApplication.class, args); }
}
