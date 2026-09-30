package com.redditclone.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2); // tuned for ~100-200ms
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf.disable()) // bearer-token API, not cookie-session based
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Spring Security's default anonymous-authentication + access-denied handling returns 403
                // for a missing/invalid token on an authenticated-only route; a bearer-token API should
                // return 401 there instead (403 is reserved for an authenticated principal lacking
                // permission, which Phase 1 doesn't have yet).
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Without this, an unhandled exception on an unauthenticated request triggers a
                        // servlet-container forward to /error, which then re-enters this same filter chain
                        // as a second, unauthenticated request — anyRequest().authenticated() denies THAT
                        // one and masks the real 500 behind a misleading 401 (found by hitting exactly this
                        // case: an ArithmeticException deep in a query surfaced as a bare 401 to the client).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/register", "/api/v1/access_token", "/api/v1/access_token/refresh")
                        .permitAll()
                        // Reddit's real API lets anyone browse without a token — only actions (vote, submit,
                        // comment, subscribe, save, message, delete) require one. jwtFilter still runs on these
                        // and populates the principal when a token IS present.
                        .requestMatchers(HttpMethod.GET, "/r/*/new", "/r/*/hot", "/r/*/top", "/r/*/rising",
                                "/r/*/controversial", "/r/*/comments/*", "/user/*/about")
                        .permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
