package com.btb.jmscamel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${jc.redis.host:localhost}")
    private String host;

    @Bean
    public RedisConnectionFactory jedisConnectionFactory() {
        log.error("Redis host: {}", host);
        return new JedisConnectionFactory(new RedisStandaloneConfiguration(host, 6379));
    }
}
