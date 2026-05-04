package com.sxxian.multiagentcreator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@MapperScan("com.sxxian.multiagentcreator.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class MultiAgentCreatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiAgentCreatorApplication.class, args);
    }

}
