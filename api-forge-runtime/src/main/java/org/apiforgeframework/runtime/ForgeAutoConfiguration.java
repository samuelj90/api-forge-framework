package org.apiforgeframework.runtime;

import org.apiforgeframework.annotations.ForgeAuthProvider;
import org.apiforgeframework.runtime.auth.ForgeTokenProvider;
import org.apiforgeframework.runtime.client.ForgeEgressClientFactory;
import org.apiforgeframework.runtime.exception.ForgeExceptionHandler;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot auto-configuration for the API-Forge runtime.
 *
 * <p>Activated automatically via {@code META-INF/spring/org.springframework.boot.autoconfigure
 * .AutoConfiguration.imports}. No {@code @Import} or {@code @ComponentScan} needed in
 * consuming applications.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link ForgeConfigurationProperties} — bound to {@code forge.*}</li>
 *   <li>{@link ForgeEgressClientFactory} — factory for egress WebClients</li>
 *   <li>{@link ForgeExceptionHandler} — base {@code @ControllerAdvice}</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(ForgeConfigurationProperties.class)
@ConditionalOnWebApplication
@Slf4j
public class ForgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ForgeEgressClientFactory forgeEgressClientFactory(
            ForgeConfigurationProperties props,
            WebClient.Builder webClientBuilder,
            @Autowired(required = false) Tracer tracer,
            ApplicationContext ctx) {

        // Discover all @ForgeAuthProvider beans and map by client name
        Map<String, ForgeTokenProvider> tokenProviders = new HashMap<>();
        Map<String, Object> providerBeans = ctx.getBeansWithAnnotation(ForgeAuthProvider.class);
        providerBeans.forEach((beanName, bean) -> {
            if (bean instanceof ForgeTokenProvider provider) {
                ForgeAuthProvider annotation = bean.getClass().getAnnotation(ForgeAuthProvider.class);
                tokenProviders.put(annotation.forClient(), provider);
                log.info("API-Forge: Registered custom token provider '{}' for client '{}'",
                        beanName, annotation.forClient());
            }
        });

        // Use a no-op tracer if micrometer-tracing is not on the classpath
        Tracer effectiveTracer = tracer != null ? tracer : Tracer.NOOP;

        log.info("API-Forge: Initializing ForgeEgressClientFactory with {} egress client(s) configured",
                props.getEgress().size());

        return new ForgeEgressClientFactory(props, webClientBuilder, effectiveTracer, tokenProviders);
    }

    @Bean
    @ConditionalOnMissingBean
    public ForgeExceptionHandler forgeExceptionHandler(
            @Autowired(required = false) Tracer tracer) {
        return new ForgeExceptionHandler(tracer != null ? tracer : Tracer.NOOP);
    }
}
