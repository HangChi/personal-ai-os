package com.personalos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Personal AI OS 后端启动入口。
 *
 * <p>{@code @ConfigurationPropertiesScan} 用于自动注册 {@code com.personalos} 包下以
 * {@code @ConfigurationProperties} 声明的类型化配置对象。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PersonalOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonalOsApplication.class, args);
    }
}
