package dev.abgleich.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Web security. There is no login: the public demo is open on purpose, holds only synthetic data and is limited
 * per address instead (B44, {@code RateLimitFilter}). The protections that are painful to add later are on:
 * <ul>
 *   <li>CSRF for every state-changing request of the web UI, including htmx ones (B35);</li>
 *   <li>a strict Content-Security-Policy, so even an escaping mistake cannot run injected script (B32).</li>
 * </ul>
 * The JSON API under /api is excluded from CSRF: it is called by integrations, not by a browser holding
 * a session cookie, and will authenticate with tokens.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }

    /** No users yet; declared so Spring Boot does not generate a default user and log its password. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
