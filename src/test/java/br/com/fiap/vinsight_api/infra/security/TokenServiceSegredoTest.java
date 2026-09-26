package br.com.fiap.vinsight_api.infra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JWT seguro no deploy: a aplicacao nao sobe com segredo ausente ou menor que 256 bits. */
class TokenServiceSegredoTest {

    private TokenService comSegredo(String segredo) {
        TokenService service = new TokenService();
        ReflectionTestUtils.setField(service, "secret", segredo);
        return service;
    }

    @Test
    @DisplayName("Segredo curto ou ausente impede a subida")
    void segredoFracoFalha() {
        assertThatThrownBy(() -> comSegredo("curto").validarSegredo())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> comSegredo(null).validarSegredo()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Segredo de 32 bytes ou mais é aceito")
    void segredoForteSobe() {
        assertThatCode(() -> comSegredo("a".repeat(32)).validarSegredo()).doesNotThrowAnyException();
    }
}
