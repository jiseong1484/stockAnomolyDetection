package com.stock.anomaly.infrastructure.security.jwt;

import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    @Mock
    private UserDetailsService userDetailsService;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(userDetailsService);
        String secret = Base64.getEncoder().encodeToString(Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256).getEncoded());
        ReflectionTestUtils.setField(jwtTokenProvider, "secretKey", secret);
        ReflectionTestUtils.setField(jwtTokenProvider, "expirationMs", 60_000L);
        ReflectionTestUtils.invokeMethod(jwtTokenProvider, "init");
    }

    @Test
    void createToken_roundTripsTheSubjectEmail() {
        String token = jwtTokenProvider.createToken("user@example.com");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserEmail(token)).isEqualTo("user@example.com");
    }

    @Test
    void validateToken_rejectsGarbageInput() {
        assertThat(jwtTokenProvider.validateToken("not-a-jwt")).isFalse();
    }

    @Test
    void validateToken_rejectsExpiredToken() {
        ReflectionTestUtils.setField(jwtTokenProvider, "expirationMs", -1_000L);
        String expiredToken = jwtTokenProvider.createToken("user@example.com");

        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
        assertThat(jwtTokenProvider.getRemainingExpirationSeconds(expiredToken)).isEqualTo(0L);
    }

    @Test
    void getAuthentication_loadsUserDetailsForTokenSubject() {
        String token = jwtTokenProvider.createToken("user@example.com");
        UserDetails userDetails = new User("user@example.com", "pw", Collections.emptyList());
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);

        var authentication = jwtTokenProvider.getAuthentication(token);

        assertThat(authentication.getPrincipal()).isEqualTo(userDetails);
    }
}
