package com.latifi.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Matches only when {@code latifi.chain.rpc-url} holds a value. Without a node URL the API still
 * starts and every bean that needs the node stays out of the context.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("!'${latifi.chain.rpc-url:}'.isBlank()")
public @interface ConditionalOnChainRpc {}
