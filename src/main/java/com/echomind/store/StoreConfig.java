package com.echomind.store;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.echomind.store")
public class StoreConfig {
}
