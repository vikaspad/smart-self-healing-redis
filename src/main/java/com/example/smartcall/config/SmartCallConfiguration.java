package com.example.smartcall.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * This class tells Spring:
 * "Here are the core objects my application needs.
 * Please create them once and share them everywhere."
 */
@Configuration   // Marks this class as a Spring configuration file
public class SmartCallConfiguration {

    /**
     * Creates a JSON engine (Jackson 3 version).
     *
     * Purpose:
     * - Converts Java objects to JSON
     * - Converts JSON back to Java
     *
     * Used for:
     * - Storing rules in Redis
     * - Sending/reading AI messages
     *
     * Spring will store this object in memory and
     * reuse it everywhere it is needed.
     */
    @Bean
    public JsonMapper jsonMapper() {
        // Build a Jackson mapper that understands Java dates, lists, maps, etc.
        return JsonMapper.builder()
                .findAndAddModules()   // auto-loads JavaTime, etc.
                .build();
    }

    /**
     * Provides a classic ObjectMapper reference.
     *
     * Why?
     * Some libraries expect ObjectMapper instead of JsonMapper.
     * We give them the same instance so everything stays consistent.
     */
    @Bean
    public ObjectMapper objectMapper(JsonMapper jsonMapper) {
        return jsonMapper;   // JsonMapper IS an ObjectMapper
    }

    /**
     * Creates an HTTP client for calling external APIs.
     *
     * This is what SmartCallService uses to call:
     * - /v1/orders
     * - /v2/createOrder
     * - etc.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Builder lets Spring auto-configure things like timeouts, SSL, etc.
        return builder.build();
    }

    /**
     * Creates a Redis client for reading/writing data.
     *
     * This is the heart of the self-healing system:
     * - Rules are stored in Redis
     * - Failures are pushed to Redis Streams
     * - AI workers read from Redis
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            LettuceConnectionFactory connectionFactory,
            JsonMapper jsonMapper) {

        // This object is Spring’s main interface to Redis
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        // Tell RedisTemplate how to connect to Redis
        template.setConnectionFactory(connectionFactory);

        // Keys like:
        //   rules:v1/orders
        //   fields:v1/orders
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Values will be JSON:
        // { "customerName":"Vikas", "age":10 }
        GenericJacksonJsonRedisSerializer serializer =
                new GenericJacksonJsonRedisSerializer(jsonMapper);

        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        // Finalize configuration
        template.afterPropertiesSet();

        return template;
    }
}