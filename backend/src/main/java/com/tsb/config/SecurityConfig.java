package com.tsb.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tsb.auth.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security wiring.
 *
 * <p>There is no hand-written JWT filter here, which surprises people.
 * {@code spring-boot-starter-oauth2-resource-server} already contains a
 * correct one: it reads the Bearer header, validates the signature, checks
 * expiry, and puts a {@link org.springframework.security.oauth2.jwt.Jwt} in
 * the security context. Writing that again by hand is roughly a hundred
 * lines whose only distinguishing feature would be its bugs.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost 10 is ~50 ms on ordinary hardware. Raising it strengthens
        // every stored hash but also makes the login endpoint a more
        // effective way to burn server CPU, which is why the rate limiter
        // runs before the comparison.
        return new BCryptPasswordEncoder(10);
    }

    /* ------------------------------------------------ our own access tokens */

    private SecretKeySpec secretKey(AuthProperties props) {
        return new SecretKeySpec(
                props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(props)));
    }

    /**
     * Marked {@code @Primary} because there are two decoders in the context
     * — this one and the Google one inside
     * {@code GoogleTokenVerifier}. Without it, Spring cannot tell which
     * should validate incoming Bearer headers, and picking wrong would mean
     * accepting Google tokens as TSB sessions.
     */
    @Bean
    @Primary
    public JwtDecoder jwtDecoder(AuthProperties props) {
        return NimbusJwtDecoder.withSecretKey(secretKey(props))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /* ------------------------------------------------------- filter chain */

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {

        http
                // No cookie carries authority on any endpoint except /api/auth/refresh,
                // and that cookie is SameSite=Lax, which the browser will not send on a
                // cross-site POST. Everything else authenticates with a Bearer header,
                // which a cross-site form cannot set. So there is no CSRF surface to
                // defend, and the token would only be ceremony.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(a -> a
                        // Sign-in, sign-up and refresh must be reachable by people
                        // who are, by definition, not yet signed in.
                        .requestMatchers("/api/auth/signup", "/api/auth/login",
                                "/api/auth/google", "/api/auth/google/username",
                                "/api/auth/refresh", "/api/auth/logout",
                                "/api/auth/username-available").permitAll()

                        // Market data is public. It is the same data Binance serves
                        // to anyone, it is what the landing chart renders before
                        // sign-in, and putting it behind a token buys nothing.
                        .requestMatchers(HttpMethod.GET, "/api/candles/**", "/api/symbols/**").permitAll()

                        // Indicators are a view of the same public candles, computed by
                        // the engine. Gating them would mean the landing chart could show
                        // prices but not a moving average over those prices, which reads
                        // as broken rather than as a reason to sign up.
                        .requestMatchers(HttpMethod.GET, "/api/indicators/catalog").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/indicators").permitAll()

                        .requestMatchers("/actuator/health").permitAll()

                        // Everything else — strategies, backtests, competition
                        // entries — needs a user, because everything else is owned.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtDecoder))
                        .authenticationEntryPoint((request, response, ex) -> {
                            // Default is a WWW-Authenticate challenge, which makes
                            // the browser pop a native basic-auth dialog. Return the
                            // same JSON shape as every other auth error instead.
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sign in to continue.\"}");
                        }));

        return http.build();
    }

    /**
     * Only needed when the frontend is served from a different origin. With
     * the Vite dev proxy from SETUP.md everything is same-origin and this is
     * inert — but leaving it out means a confusing failure the first time
     * someone deploys the two halves separately.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value(
                    "${tsb.auth.allowed-origins:http://localhost:5173}")
            List<String> allowedOrigins) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Required for the refresh cookie to travel cross-origin. Note that
        // this is why allowedOrigins must be an explicit list: the spec
        // forbids "*" together with credentials, and browsers enforce it.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}