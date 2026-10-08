package br.com.fiap.vinsight_api.infra.exception;

import br.com.fiap.vinsight_api.suporte.TesteIntegracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-31: formato RFC 7807 em todos os erros, status coerentes e correlation id. */
class ErrosApiTest extends TesteIntegracao {

    @Test
    @DisplayName("Erro traz todos os campos do contrato: type, title, status, detail, instance, timestamp, correlationId")
    void formatoProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/leads/999").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ERRORS + "nao-encontrado"))
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Lead com id 999 não encontrado."))
                .andExpect(jsonPath("$.instance").value("/api/v1/leads/999"))
                .andExpect(jsonPath("$.timestamp", matchesPattern(".*Z$")))
                .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    @DisplayName("JSON malformado responde 400 requisicao-invalida")
    void jsonMalformado() throws Exception {
        mvc.perform(patch("/api/v1/leads/1")
                        .header("Authorization", bearer(CONSULTOR_MORUMBI))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desfecho\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ERRORS + "requisicao-invalida"));
    }

    @Test
    @DisplayName("Rota inexistente responde 404, não 500")
    void rotaInexistente() throws Exception {
        mvc.perform(get("/api/v1/nao-existe").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ERRORS + "nao-encontrado"));
    }

    @Test
    @DisplayName("Método HTTP não suportado responde 405")
    void metodoNaoPermitido() throws Exception {
        mvc.perform(post("/api/v1/leads/1").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value(ERRORS + "metodo-nao-permitido"));
    }

    @Test
    @DisplayName("Parâmetro de URL com tipo errado responde 400 com mensagem em português")
    void tipoErradoNaUrl() throws Exception {
        mvc.perform(get("/api/v1/leads/abc").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parâmetro 'id' com valor inválido: 'abc'."));
    }

    @Test
    @DisplayName("Ordenação por campo inexistente responde 400, não 500")
    void ordenacaoInvalida() throws Exception {
        mvc.perform(get("/api/v1/leads?sort=campoInexistente").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parâmetro sort com campo inexistente."));
    }

    // O exemplo padrao do Pageable no Swagger ({"sort": ["string"]}) chega como sort=["string"]
    @Test
    @DisplayName("Ordenação com aspas ou colchetes no campo (exemplo padrão do Swagger) responde 400, não 500")
    void ordenacaoComCaracteresInvalidos() throws Exception {
        for (String sort : new String[]{"[\"string\"]", "\"string\""}) {
            mvc.perform(get("/api/v1/leads").param("sort", sort).header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Parâmetro sort com campo inexistente."));
        }
    }

    @Test
    @DisplayName("Toda resposta traz X-Correlation-Id, o mesmo valor do corpo do erro")
    void correlationIdNoHeaderENoCorpo() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/leads/999").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                .andReturn();
        String doHeader = r.getResponse().getHeader("X-Correlation-Id");

        assertThat(doHeader).matches("[0-9a-f-]{36}");
        assertThat(r.getResponse().getContentAsString()).contains("\"correlationId\":\"" + doHeader + "\"");
    }

    @Test
    @DisplayName("Correlation id enviado pelo app é reaproveitado; valor inseguro para log é trocado")
    void correlationIdDoCliente() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "app-123"))
                .andExpect(header().string("X-Correlation-Id", "app-123"));
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "abc<script>"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")));
    }
}
