package vn.yourhrm.shared.auth.internal;

import org.springframework.aop.Pointcut;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.auth.api.spi.UserAccountResolver;

/**
 * Resource server (ADR-0005): stateless, JWT validated locally against the issuer's cached JWKS
 * ({@code spring.security.oauth2.resourceserver.jwt.*}); no roles are read from the token. Every
 * {@code /api/**} request must be authenticated; permissions are enforced by method security on
 * {@link RequiresPermission} (ADR-0006) plus a fail-closed guard for handlers without a declaration.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = false)
public class SecurityConfiguration implements WebMvcConfigurer {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, UserAccountResolver userAccountResolver)
            throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                // The TenantContextFilter must not be a bean: Boot would also register it as a servlet filter.
                .addFilterAfter(new TenantContextFilter(userAccountResolver), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** {@code @RequiresPermission} on any Spring bean method (controllers, application services). */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static AuthorizationManagerBeforeMethodInterceptor requiresPermissionInterceptor(
            ObjectProvider<AuthorizationService> authorizationService) {
        Pointcut pointcut = Pointcuts.union(
                new AnnotationMatchingPointcut(null, RequiresPermission.class, true),
                new AnnotationMatchingPointcut(RequiresPermission.class, true));
        var interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                pointcut, new RequiresPermissionAuthorizationManager(authorizationService));
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new EndpointDeclarationGuard()).addPathPatterns("/api/**");
    }
}
