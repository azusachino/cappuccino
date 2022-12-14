package cn.az.code;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.reactive.config.EnableWebFlux;

/**
 * Another Spring Application
 * 
 * @author az
 */
@EnableWebFlux
@SpringBootApplication
public class CupApplication {

    public static void main(String[] args) {
        SpringApplication.run(CupApplication.class, args);
    }
}