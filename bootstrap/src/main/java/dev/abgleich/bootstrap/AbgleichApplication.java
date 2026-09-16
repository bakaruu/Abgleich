package dev.abgleich.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Component scanning covers bootstrap and the driving adapters, whose controllers are Spring MVC
 * components. Driven adapters are plain classes wired explicitly in {@code config}.
 */
@SpringBootApplication(scanBasePackages = {"dev.abgleich.bootstrap", "dev.abgleich.adapter.in"})
public class AbgleichApplication {

    public static void main(String[] args) {
        SpringApplication.run(AbgleichApplication.class, args);
    }
}
