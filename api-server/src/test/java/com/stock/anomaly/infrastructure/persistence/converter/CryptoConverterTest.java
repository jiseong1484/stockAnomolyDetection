package com.stock.anomaly.infrastructure.persistence.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoConverterTest {

    private CryptoConverter converter;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        converter = new CryptoConverter(Base64.getEncoder().encodeToString(key));
    }

    @Test
    void encryptThenDecrypt_returnsOriginalPlaintext() {
        String plaintext = "kis-app-key-1234567890";

        String encrypted = converter.convertToDatabaseColumn(plaintext);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void convertToDatabaseColumn_nullInput_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_nullInput_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
