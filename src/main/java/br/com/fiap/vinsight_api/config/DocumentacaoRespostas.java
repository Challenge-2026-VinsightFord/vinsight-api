package br.com.fiap.vinsight_api.config;

import br.com.fiap.vinsight_api.agendamento.AgendamentoController;
import br.com.fiap.vinsight_api.cliente.ClienteController;
import br.com.fiap.vinsight_api.infra.exception.TipoProblema;
import br.com.fiap.vinsight_api.lead.LeadController;
import br.com.fiap.vinsight_api.usuario.Perfil;
import br.com.fiap.vinsight_api.veiculo.VeiculoController;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Documenta no Swagger os status de resposta de TODOS os endpoints, deduzidos da assinatura:
 *
 * - POST -> 201 com header Location · DELETE -> 204
 * - corpo na requisicao -> 400 (JSON malformado) e 422 (validacao, com a lista de violacoes)
 * - listagem paginada -> 400 (?sort= com campo inexistente)
 * - restricao em parametro da URL (ex.: VIN) -> 422
 * - {id}/{vin} na rota -> 404
 * - @PreAuthorize -> 401 (token) e 403 (perfil; e outra concessionaria nos servicos com escopo)
 * - todos -> 500 generico
 * - @ErroDocumentado -> erros de regra de negocio (ex.: 409 CPF ja cadastrado)
 *
 * Assim um endpoint novo ja aparece documentado com seus codigos (cenario BDD da US-32), sem
 * repetir anotacao em cada metodo. Todo erro usa o schema "Problema" (RFC 7807, SwaggerConfig).
 */
@Component
public class DocumentacaoRespostas implements OperationCustomizer {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String REF_PROBLEMA = "#/components/schemas/Problema";

    // Servicos cujos dados sao filtrados pela concessionaria do usuario (ContextoSeguranca)
    private static final Set<Class<?>> COM_ESCOPO_DE_UNIDADE = Set.of(
            LeadController.class, ClienteController.class, VeiculoController.class, AgendamentoController.class);

    @Override
    public Operation customize(Operation operacao, HandlerMethod metodo) {
        // So os controllers da aplicacao. O /actuator/health usa um metodo generico do Spring (com um
        // corpo opcional na assinatura) e ganharia um 400 que ele nunca responde.
        if (!metodo.getBeanType().getPackageName().startsWith("br.com.fiap.vinsight_api")) {
            return operacao;
        }
        ApiResponses respostas = operacao.getResponses();
        MethodParameter[] parametros = metodo.getMethodParameters();

        ajustarSucesso(metodo, respostas);

        if (temParametro(parametros, RequestBody.class)) {
            erro(respostas, TipoProblema.REQUISICAO_INVALIDA, "Corpo da requisição malformado ou ilegível.");
        }
        if (Arrays.stream(parametros).anyMatch(p -> Pageable.class.isAssignableFrom(p.getParameterType()))) {
            erro(respostas, TipoProblema.REQUISICAO_INVALIDA, "Parâmetro sort com campo inexistente.");
        }
        if (validaEntrada(parametros)) {
            erro(respostas, TipoProblema.VALIDACAO, "Um ou mais campos estão inválidos.");
        }
        PreAuthorize regra = metodo.getMethodAnnotation(PreAuthorize.class);
        if (regra != null) {
            erro(respostas, TipoProblema.NAO_AUTENTICADO, "Envie o header Authorization: Bearer <accessToken>.");
            erro(respostas, TipoProblema.TOKEN_EXPIRADO, "Token JWT expirado.");
            // Endpoint liberado para os quatro perfis (ex.: GET /concessionarias) nunca responde 403 por perfil
            if (!liberadoParaTodosOsPerfis(regra)) {
                erro(respostas, TipoProblema.PERFIL_SEM_PERMISSAO, "Seu perfil não tem permissão para este recurso.");
            }
            if (temEscopoDeUnidade(metodo)) {
                erro(respostas, TipoProblema.OUTRA_CONCESSIONARIA, "Recurso pertence a outra concessionária.");
            }
        }
        if (temParametro(parametros, PathVariable.class)) {
            erro(respostas, TipoProblema.NAO_ENCONTRADO, "Nenhum registro com este identificador.");
        }
        for (ErroDocumentado e : metodo.getMethod().getAnnotationsByType(ErroDocumentado.class)) {
            erro(respostas, e.tipo(), e.quando());
        }
        erro(respostas, TipoProblema.ERRO_INTERNO, "Erro inesperado. Informe o correlationId ao suporte.");
        return operacao;
    }

    // O springdoc documenta todo retorno como 200; a API responde 201 na criacao e 204 na remocao
    private void ajustarSucesso(HandlerMethod metodo, ApiResponses respostas) {
        ApiResponse ok = respostas.get("200");
        if (ok == null) return;
        boolean criacao = metodo.hasMethodAnnotation(PostMapping.class) && metodo.hasMethodAnnotation(PreAuthorize.class);
        if (criacao) {
            respostas.remove("200");
            respostas.addApiResponse("201", ok.description("Criado. O header Location aponta para o recurso novo.")
                    .addHeaderObject("Location", new Header()
                            .description("URI do recurso criado")
                            .schema(new StringSchema().example("http://localhost:8080/api/v1/customers/31"))));
        } else if (metodo.hasMethodAnnotation(DeleteMapping.class)) {
            respostas.remove("200");
            respostas.addApiResponse("204", new ApiResponse().description("Removido (inativação lógica). Sem corpo."));
        } else {
            ok.description("OK");
        }
    }

    /** Acrescenta o exemplo deste tipo de erro na resposta do seu status, criando-a se preciso. */
    private void erro(ApiResponses respostas, TipoProblema tipo, String detalhe) {
        String status = String.valueOf(tipo.status());
        ApiResponse resposta = respostas.get(status);
        if (resposta == null) {
            resposta = new ApiResponse().description(tipo.titulo())
                    .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(new Schema<>().$ref(REF_PROBLEMA))));
            respostas.addApiResponse(status, resposta);
        } else if (!resposta.getDescription().contains(tipo.titulo())) {
            resposta.description(resposta.getDescription() + " · " + tipo.titulo());
        }
        MediaType problema = resposta.getContent().get(PROBLEM_JSON);
        if (problema != null) {
            problema.addExamples(codigo(tipo), new Example().summary(detalhe).value(exemplo(tipo, detalhe)));
        }
    }

    private static Map<String, Object> exemplo(TipoProblema tipo, String detalhe) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", tipo.uri().toString());
        corpo.put("title", tipo.titulo());
        corpo.put("status", tipo.status());
        corpo.put("detail", detalhe);
        corpo.put("instance", "/api/v1/...");
        corpo.put("timestamp", "2026-09-22T14:30:00Z");
        corpo.put("correlationId", "b7f3c2a1-5d4e-4f6a-9b8c-1d2e3f4a5b6c");
        if (tipo == TipoProblema.VALIDACAO) {
            corpo.put("violacoes", List.of(Map.of("campo", "desfecho", "mensagem", "não deve ser nulo")));
        }
        return corpo;
    }

    private static String codigo(TipoProblema tipo) {
        String uri = tipo.uri().toString();
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    private static boolean liberadoParaTodosOsPerfis(PreAuthorize regra) {
        return Arrays.stream(Perfil.values()).allMatch(p -> regra.value().contains("'" + p.name() + "'"));
    }

    private static boolean temEscopoDeUnidade(HandlerMethod metodo) {
        // Alem dos servicos com escopo, a agenda de uma concessionaria tambem e filtrada pela unidade
        return COM_ESCOPO_DE_UNIDADE.contains(metodo.getBeanType())
                || metodo.getMethod().getName().equals("listarAgendamentos");
    }

    private static boolean temParametro(MethodParameter[] parametros, Class<? extends Annotation> anotacao) {
        return Arrays.stream(parametros).anyMatch(p -> p.hasParameterAnnotation(anotacao));
    }

    // Corpo com @Valid ou parametro com restricao do Bean Validation (ex.: @Pattern no VIN, @Size no header)
    private static boolean validaEntrada(MethodParameter[] parametros) {
        return Arrays.stream(parametros).anyMatch(p ->
                p.hasParameterAnnotation(Valid.class)
                        || Arrays.stream(p.getParameterAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(Constraint.class)));
    }
}
