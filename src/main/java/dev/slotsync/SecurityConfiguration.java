package dev.slotsync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.svg",
                                "/api/resources", "/api/session", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/login", "/api/logout").permitAll()
                        .requestMatchers("/api/bookings", "/api/bookings/**").hasRole("REQUESTER")
                        .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/").loginProcessingUrl("/api/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> jsonError(response, 401, "LOGIN_FAILED")))
                .logout(logout -> logout.logoutUrl("/api/logout").invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> jsonError(response, 401, "LOGIN_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) -> jsonError(response, 403, "ACCESS_DENIED")))
                .build();
    }

    private static void jsonError(jakarta.servlet.http.HttpServletResponse response, int status, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    @Bean
    PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService accounts(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT * FROM users WHERE username = ?", (rs, row) ->
                        new SlotUser(rs.getObject("id", java.util.UUID.class), rs.getString("username"),
                                rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled")), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
