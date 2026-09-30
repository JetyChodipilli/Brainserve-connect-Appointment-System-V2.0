package com.brainserve.appointment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableScheduling
@EnableAsync
@SpringBootApplication
public class BrainServeAppointmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(BrainServeAppointmentApplication.class, args);
    }
}
