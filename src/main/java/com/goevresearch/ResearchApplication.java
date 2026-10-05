package com.goevresearch;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.*;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Optional numerical/API demo entry point; ordinary GoEV remains the default bootRun. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,HibernateJpaAutoConfiguration.class,
    RedisAutoConfiguration.class,RedisRepositoriesAutoConfiguration.class})
@ComponentScan("com.goev.v2g")
@EnableScheduling
public class ResearchApplication {
    public static void main(String[] args){SpringApplication.run(ResearchApplication.class,args);}
    @org.springframework.context.annotation.Bean
    public org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictInputs(){
        return builder->builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
