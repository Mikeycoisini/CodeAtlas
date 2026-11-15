package dev.codeatlas.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class ReviewConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock learningClock() { return Clock.systemUTC(); }
}
