package dev.sentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.boot.context.properties.EnableConfigurationProperties(dev.sentinel.fraud.FraudProperties.class)
@SpringBootApplication
public class SentinelApplication {
    public static void main(String[] args) {
        SpringApplication.run(SentinelApplication.class, args);
    }
}
