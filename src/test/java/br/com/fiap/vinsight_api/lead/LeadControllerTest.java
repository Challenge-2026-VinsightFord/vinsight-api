package br.com.fiap.vinsight_api.lead;

import br.com.fiap.vinsight_api.suporte.TesteIntegracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-35 (Lead Engine) e o escopo por concessionaria da US-30 aplicado aos leads.
 * Massa: Morumbi tem os leads 1, 2, 5, 6 (o 3 e suprimido por LGPD); Campinas tem o 4.
 */
class LeadControllerTest extends TesteIntegracao {

    @Autowired
    private DesfechoLeadRepository desfechoRepository;

    private ResultActions registrar(String usuario, long leadId, String corpo, String idempotencyKey) throws Exception {
        var requisicao = patch("/api/v1/leads/" + leadId)
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo);
        if (idempotencyKey != null) {
            requisicao.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(requisicao);
    }

    @Nested
    @DisplayName("BDD: fila ordenada por score decrescente")
    class Fila {

        @Test
        @DisplayName("GET /leads?status=OPEN vem por score decrescente, com VIN, score, faixa de risco, motivo e ação")
        void filaOrdenadaNoFormatoDoContrato() throws Exception {
            mvc.perform(get("/api/v1/leads?status=OPEN").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", contains(1, 2)))
                    .andExpect(jsonPath("$.content[*].score", contains(0.91, 0.55)))
                    .andExpect(jsonPath("$.content[0].vin").value("9BF8313PF9WBWDX01"))
                    .andExpect(jsonPath("$.content[0].faixaRisco").value("ALTO"))
                    .andExpect(jsonPath("$.content[0].motivoContato").value("Garantia encerra em 54 dias"))
                    .andExpect(jsonPath("$.content[0].acaoRecomendada").value("Oferecer extensao de garantia"))
                    .andExpect(jsonPath("$.content[0].perfilComportamental").value("ESQUECIDO"))
                    .andExpect(jsonPath("$.content[0].geradoEm").value("2026-09-15T11:00:00Z"))
                    .andExpect(jsonPath("$.content[0].veiculo.modelo").value("Ranger"))
                    .andExpect(jsonPath("$.content[1].faixaRisco").value("MEDIO"));
        }

        @Test
        @DisplayName("Envelope de paginação plano, como no contrato")
        void envelopeDePaginacao() throws Exception {
            mvc.perform(get("/api/v1/leads?size=2").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.content", hasSize(2)));
        }

        @Test
        @DisplayName("Filtro ?risco=ALTO devolve só leads com score >= 0,70")
        void filtroPorRisco() throws Exception {
            mvc.perform(get("/api/v1/leads?risco=ALTO").header("Authorization", bearer(ADMIN)))
                    .andExpect(jsonPath("$.content[*].id", contains(1, 4)))
                    .andExpect(jsonPath("$.content[*].faixaRisco", everyItem(org.hamcrest.Matchers.is("ALTO"))));
        }

        @Test
        @DisplayName("Consultor recebe o telefone mascarado; gerente, completo")
        void mascaramentoDoTelefone() throws Exception {
            mvc.perform(get("/api/v1/leads?status=OPEN").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(jsonPath("$.content[0].cliente.telefoneMascarado").value("(11) *****-4321"));
            mvc.perform(get("/api/v1/leads?status=OPEN").header("Authorization", bearer(GERENTE_MORUMBI)))
                    .andExpect(jsonPath("$.content[0].cliente.telefoneMascarado").value("11910004321"));
        }
    }

    @Nested
    @DisplayName("Escopo por concessionária (US-30)")
    class Escopo {

        @Test
        @DisplayName("Consultor vê só a fila da própria unidade; admin vê a rede")
        void filaPorUnidade() throws Exception {
            mvc.perform(get("/api/v1/leads").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(jsonPath("$.content[*].id", contains(1, 2, 6, 5)));
            mvc.perform(get("/api/v1/leads").header("Authorization", bearer(CONSULTOR_CAMPINAS)))
                    .andExpect(jsonPath("$.content[*].id", contains(4)));
            mvc.perform(get("/api/v1/leads").header("Authorization", bearer(ADMIN)))
                    .andExpect(jsonPath("$.totalElements").value(5));
        }

        @Test
        @DisplayName("Consultor da unidade A pedindo lead da unidade B recebe 403 outra-concessionaria")
        void leadDeOutraUnidade() throws Exception {
            mvc.perform(get("/api/v1/leads/4").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(ERRORS + "outra-concessionaria"));
        }

        @Test
        @DisplayName("Desfecho em lead de outra unidade também é 403, e nada é gravado")
        void desfechoEmLeadDeOutraUnidade() throws Exception {
            registrar(CONSULTOR_CAMPINAS, 1, "{\"desfecho\":\"CONTATADO\"}", null)
                    .andExpect(status().isForbidden());
            assertThat(desfechoRepository.findAllByLeadIdOrderByRegistradoEmDescIdDesc(1L)).isEmpty();
        }

        @Test
        @DisplayName("Lead inexistente responde 404 nao-encontrado")
        void leadInexistente() throws Exception {
            mvc.perform(get("/api/v1/leads/999").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(ERRORS + "nao-encontrado"));
        }

        @Test
        @DisplayName("Analista Ford não acessa a fila (403 perfil-sem-permissao)")
        void analistaSemPermissao() throws Exception {
            mvc.perform(get("/api/v1/leads").header("Authorization", bearer(ANALISTA)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(ERRORS + "perfil-sem-permissao"));
        }

        @Test
        @DisplayName("Consultor sem concessionária vinculada é barrado, nunca vê a rede inteira")
        void consultorSemUnidade() throws Exception {
            mvc.perform(get("/api/v1/leads").header("Authorization", bearer(SEM_UNIDADE)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Consultor não pode cadastrar lead (entrada do modelo é só do ADMIN)")
        void consultorNaoCadastraLead() throws Exception {
            mvc.perform(post("/api/v1/leads")
                            .header("Authorization", bearer(CONSULTOR_MORUMBI))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"clienteId":1,"veiculoId":1,"score":0.5,"prioridade":"ALTA","motivo":"x"}"""))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("BDD: cliente sem consentimento é suprimido da fila (LGPD)")
    class SupressaoLgpd {

        @Test
        @DisplayName("Lead de cliente sem consentimento não aparece na fila, nem para o admin")
        void leadSuprimidoForaDaFila() throws Exception {
            mvc.perform(get("/api/v1/leads?size=50").header("Authorization", bearer(ADMIN)))
                    .andExpect(jsonPath("$.content[*].id", not(org.hamcrest.Matchers.hasItem(3))));
        }

        @Test
        @DisplayName("O detalhe do lead mostra a supressão registrada com o motivo LGPD_OPT_OUT")
        void supressaoRegistrada() throws Exception {
            mvc.perform(get("/api/v1/leads/3").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.supressao.motivo").value("LGPD_OPT_OUT"))
                    .andExpect(jsonPath("$.supressao.em", notNullValue()));
        }

        @Test
        @DisplayName("Lead novo do modelo para cliente sem consentimento entra já suprimido")
        void leadNovoEntraSuprimido() throws Exception {
            mvc.perform(post("/api/v1/leads")
                            .header("Authorization", bearer(ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"clienteId":3,"veiculoId":3,"score":0.88,"prioridade":"ALTA",
                                     "motivo":"Revisao proxima","perfilComportamental":"ABANDONO"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.supressao.motivo").value("LGPD_OPT_OUT"));
        }

        @Test
        @DisplayName("Lead suprimido não aceita desfecho: o cliente não pode ser contatado (409)")
        void suprimidoNaoAceitaDesfecho() throws Exception {
            registrar(CONSULTOR_MORUMBI, 3, "{\"desfecho\":\"CONTATADO\"}", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(ERRORS + "conflito"));
        }
    }

    @Nested
    @DisplayName("BDD: registro de desfecho encerra o lead")
    class Desfecho {

        @Test
        @DisplayName("PATCH AGENDADO encerra o lead e grava o desfecho para o retreinamento")
        void agendadoEncerraLead() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, """
                    {"desfecho":"AGENDADO","observacao":"Cliente aceitou revisão","proximoContato":"2030-10-02"}""", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AGENDADO"))
                    .andExpect(jsonPath("$.ultimoContatoEm", notNullValue()))
                    .andExpect(jsonPath("$.desfechos", hasSize(1)))
                    .andExpect(jsonPath("$.desfechos[0].desfecho").value("AGENDADO"))
                    .andExpect(jsonPath("$.desfechos[0].observacao").value("Cliente aceitou revisão"))
                    .andExpect(jsonPath("$.desfechos[0].registradoPor").value("Ana Souza"));

            // Encerrado, sai da fila de trabalho
            mvc.perform(get("/api/v1/leads?status=OPEN").header("Authorization", bearer(CONSULTOR_MORUMBI)))
                    .andExpect(jsonPath("$.content[*].id", contains(2)));
        }

        @Test
        @DisplayName("SEM_SUCESSO mantém o lead aberto, que aceita novo desfecho")
        void semSucessoMantemAberto() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, "{\"desfecho\":\"SEM_SUCESSO\"}", null)
                    .andExpect(jsonPath("$.status").value("SEM_SUCESSO"));
            registrar(CONSULTOR_MORUMBI, 1, "{\"desfecho\":\"RECUSADO\"}", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.desfechos", hasSize(2)))
                    .andExpect(jsonPath("$.desfechos[0].desfecho").value("RECUSADO"));
        }

        @Test
        @DisplayName("Lead encerrado não aceita novo desfecho (409)")
        void encerradoNaoAceitaDesfecho() throws Exception {
            registrar(CONSULTOR_MORUMBI, 5, "{\"desfecho\":\"CONTATADO\"}", null)
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("OPEN não é desfecho válido: o app não reabre lead (422)")
        void naoReabreLead() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, "{\"desfecho\":\"OPEN\"}", null)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.violacoes[0].campo").value("desfecho"));
        }

        @Test
        @DisplayName("Sem desfecho responde 422 apontando o campo do corpo, não o parâmetro Java")
        void semDesfecho() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, "{}", null)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.violacoes[0].campo").value("desfecho"));
        }

        @Test
        @DisplayName("Próximo contato no passado responde 422")
        void proximoContatoNoPassado() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, "{\"desfecho\":\"CONTATADO\",\"proximoContato\":\"2020-01-01\"}", null)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.violacoes[0].campo").value("proximoContato"));
        }
    }

    @Nested
    @DisplayName("Idempotency-Key: reenvio não duplica o registro")
    class Idempotencia {

        private static final String CORPO = """
                {"desfecho":"AGENDADO","observacao":"Aceitou"}""";
        private static final String CHAVE = "3f6c2b1e-9a4d-4c8e-b2f0-7d1e5a9c4b21";

        @Test
        @DisplayName("Mesma chave três vezes: uma gravação, mesma resposta, e Idempotent-Replayed nos reenvios")
        void reenvioDevolveRespostaOriginal() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, CORPO, CHAVE)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "false"));
            for (int i = 0; i < 2; i++) {
                registrar(CONSULTOR_MORUMBI, 1, CORPO, CHAVE)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Idempotent-Replayed", "true"))
                        .andExpect(jsonPath("$.status").value("AGENDADO"));
            }
            assertThat(desfechoRepository.findAllByLeadIdOrderByRegistradoEmDescIdDesc(1L)).hasSize(1);
        }

        @Test
        @DisplayName("Mesma chave com outro corpo responde 422 no campo Idempotency-Key")
        void mesmaChaveOutroCorpo() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, CORPO, CHAVE).andExpect(status().isOk());

            registrar(CONSULTOR_MORUMBI, 1, "{\"desfecho\":\"RECUSADO\"}", CHAVE)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.violacoes[0].campo").value("Idempotency-Key"));
        }

        @Test
        @DisplayName("Chave com mais de 64 caracteres responde 422 com o nome do header")
        void chaveLongaDemais() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, CORPO, "a".repeat(65))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.violacoes[0].campo").value("Idempotency-Key"));
        }

        @Test
        @DisplayName("A chave não dá acesso a lead de outra unidade")
        void chaveNaoFuraEscopo() throws Exception {
            registrar(CONSULTOR_MORUMBI, 1, CORPO, CHAVE).andExpect(status().isOk());

            registrar(CONSULTOR_CAMPINAS, 1, CORPO, CHAVE).andExpect(status().isForbidden());
        }
    }
}
