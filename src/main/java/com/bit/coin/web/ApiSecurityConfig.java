package com.bit.coin.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@EnableConfigurationProperties(NodeApiProperties.class)
public class ApiSecurityConfig {
    @Bean org.springframework.security.core.userdetails.UserDetailsService disabledPasswordUsers() {
        return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Password authentication is disabled"); };
    }
    @Bean SecurityFilterChain nodeSecurity(HttpSecurity http, NodeApiProperties properties) throws Exception {
        properties.validate();
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Credentials are accepted only in an explicit Authorization header, never in cookies or URLs.
                .csrf(csrf -> csrf.disable()).cors(cors -> {})
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ApiAccessFilter.reject(response, 401, "authentication_required"))
                        .accessDeniedHandler((request, response, exception) -> ApiAccessFilter.reject(response, 403, "access_denied")))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(HttpMethod.GET, "/", "/*.html", "/*.js", "/*.css", "/home/**", "/favicon.ico", "/actuator/health", "/actuator/health/**").permitAll();
                    if (properties.isPublicRead()) auth.requestMatchers(HttpMethod.GET, "/chain/**", "/bitcoin/chain/**", "/txpool/**", "/peer/**", "/p2p/**", "/explorer/**", "/node/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/**").hasAnyRole("READ", "ADMIN")
                            .requestMatchers(HttpMethod.POST, "/tx/broadcast", "/bitcoin/chain/transfer", "/txpool/replace", "/chain/getCommonAncestor", "/bitcoin/chain/hasTx").hasAnyRole("READ", "ADMIN")
                            .anyRequest().hasRole("ADMIN");
                }).addFilterBefore(new ApiAccessFilter(properties), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
