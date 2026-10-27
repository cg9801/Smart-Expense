package com.occ.smartexpense;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.occ.smartexpense.mapper")
public class SmartExpenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartExpenseApplication.class, args);
    }

}
