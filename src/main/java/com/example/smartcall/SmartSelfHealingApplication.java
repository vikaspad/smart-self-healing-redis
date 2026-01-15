package com.example.smartcall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Smart Self‑Healing application.  This class uses
 * {@code @SpringBootApplication} to enable component scanning, auto
 * configuration and property support.  Run the application with
 * {@code mvn spring-boot:run} or by executing the generated JAR.
 */
@SpringBootApplication
public class SmartSelfHealingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartSelfHealingApplication.class, args);
    }
}