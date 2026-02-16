package com.shadowstack.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.shadowstack.api.config.ShadowStackConfig;

@SpringBootApplication
@EnableConfigurationProperties(ShadowStackConfig.class)
public class ShadowStackApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShadowStackApplication.class, args);
    }
}
