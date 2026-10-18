package com.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = "com.inventory", annotationClass = Mapper.class)
public class InventoryApplication {
    public static void main(String[] args) { SpringApplication.run(InventoryApplication.class, args); }
}
