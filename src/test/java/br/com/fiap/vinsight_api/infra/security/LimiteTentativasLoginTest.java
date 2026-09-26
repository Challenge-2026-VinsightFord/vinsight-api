package br.com.fiap.vinsight_api.infra.security;

import br.com.fiap.vinsight_api.suporte.TesteIntegracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limit do login (OWASP API4). Contexto proprio com o limite real de producao (5 falhas / 60 s);
 * cada teste usa um IP diferente para nao herdar falhas de outro.
 */
@TestPropertySource(properties = {"api.security.login.max-falhas=5", "api.security.login.janela-segundos=60"})
class LimiteTentativasLoginTest extends TesteIntegracao {

    private ResultActions login(String ip, String senha) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .with(requisicao -> {
                    requisicao.setRemoteAddr(ip);
                    return requisicao;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","senha":"%s"}""".formatted(CONSULTOR_MORUMBI, senha)));
    }

    @Test
    @DisplayName("5 senhas erradas do mesmo IP: a 6a tentativa recebe 429 com Retry-After, mesmo com a senha certa")
    void bloqueiaAposCincoFalhas() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("10.0.0.1", "errada").andExpect(status().isUnauthorized());
        }

        login("10.0.0.1", "consultor123")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ERRORS + "muitas-requisicoes"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    @DisplayName("O bloqueio é por IP: outro IP continua fazendo login normalmente")
    void outroIpNaoEBloqueado() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("10.0.0.2", "errada").andExpect(status().isUnauthorized());
        }
        login("10.0.0.3", "consultor123").andExpect(status().isOk());
    }

    @Test
    @DisplayName("Login certo não conta como falha: 4 erros + acertos não bloqueiam")
    void loginCertoNaoConta() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("10.0.0.4", "errada").andExpect(status().isUnauthorized());
        }
        for (int i = 0; i < 3; i++) {
            login("10.0.0.4", "consultor123").andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("E-mail do log de falha sai mascarado e sem quebra de linha (log injection)")
    void emailDoLogMascaradoESemQuebraDeLinha() {
        assertThat(AuditoriaAcesso.mascararEmail("consultor@ford.com.br")).isEqualTo("c****@ford.com.br");
        assertThat(AuditoriaAcesso.mascararEmail("x@a.com\nLOGIN_OK usuario[admin]"))
                .doesNotContain("\n").startsWith("x****@a.com_");
        assertThat(AuditoriaAcesso.mascararEmail(null)).isEqualTo("(vazio)");
    }
}
