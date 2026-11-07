package com.notenexus.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.notenexus.rag.**.mapper")
public class NoteNexusBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoteNexusBackendApplication.class, args);
    }
}
