package dev.codeatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeAtlasApplication {
    public static void main(String[] args) { var app=new SpringApplication(CodeAtlasApplication.class); app.setHeadless(false); app.run(args); }
}
