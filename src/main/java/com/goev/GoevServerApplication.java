package com.goev;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GoevServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoevServerApplication.class, args);
    }
}
