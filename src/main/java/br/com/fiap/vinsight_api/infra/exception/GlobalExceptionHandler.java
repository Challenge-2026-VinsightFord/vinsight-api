package br.com.fiap.vinsight_api.infra.exception;

import br.com.fiap.vinsight_api.infra.security.AcessoForaDoEscopoException;
import br.com.fiap.vinsight_api.infra.security.AuditoriaAcesso;
import br.com.fiap.vinsight_api.infra.security.MuitasTentativasException;
import br.com.fiap.vinsight_api.infra.security.TokenInvalidoException;
import br.com.fiap.vinsight_api.infra.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.query.sqm.UnknownPathException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Todo erro da API sai em application/problem+json (RFC 7807), no formato do contrato:
 * type, title, status, detail, instance, timestamp, correlationId e, na validacao, violacoes.
 *
 * Estende ResponseEntityExceptionHandler para que as excecoes do proprio Spring MVC (rota
 * inexistente, metodo errado, parametro com tipo errado...) tambem saiam neste formato com o
 * status certo, em vez de cairem no 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record Violacao(String campo, String mensagem) {
    }

    @Autowired
    private AuditoriaAcesso auditoria;

    // ------------------------------------------------------------------ excecoes da aplicacao

    @ExceptionHandler(EntidadeNaoEncontradaException.class)
    public ResponseEntity<ProblemDetail> handle404(EntidadeNaoEncontradaException e, HttpServletRequest request) {
        return responder(HttpStatus.NOT_FOUND, TipoProblema.NAO_ENCONTRADO, e.getMessage(), request);
    }

    @ExceptionHandler(CampoInvalidoException.class)
    public ResponseEntity<Object> handle422Campo(CampoInvalidoException e, HttpServletRequest request) {
        return validacao(List.of(new Violacao(e.getCampo(), e.getMessage())), request);
    }

    @ExceptionHandler(RegraNegocioException.class)
    public ResponseEntity<ProblemDetail> handle409(RegraNegocioException e, HttpServletRequest request) {
        return responder(HttpStatus.CONFLICT, TipoProblema.CONFLITO, e.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handle409Integridade(DataIntegrityViolationException e,
                                                              HttpServletRequest request) {
        return responder(HttpStatus.CONFLICT, TipoProblema.CONFLITO,
                "Violação de integridade: registro duplicado ou referência inválida.", request);
    }

    // ?sort= com campo inexistente. Derived query: PropertyReferenceException; @Query: o Hibernate
    // lanca UnknownPathException embrulhada; campo com aspas ou colchetes (o exemplo padrao do Pageable
    // no Swagger manda sort=["string"]): o Spring Data recusa a "Sort expression".
    // Qualquer outro mau uso de API de dados segue 500.
    @ExceptionHandler({PropertyReferenceException.class, InvalidDataAccessApiUsageException.class})
    public ResponseEntity<ProblemDetail> handle400Ordenacao(Exception e, HttpServletRequest request) {
        if (e instanceof PropertyReferenceException || buscarCausa(e, UnknownPathException.class) != null
                || expressaoDeOrdenacaoRecusada(e)) {
            return responder(HttpStatus.BAD_REQUEST, TipoProblema.REQUISICAO_INVALIDA,
                    "Parâmetro sort com campo inexistente.", request);
        }
        return handle500(e, request);
    }

    // Token expirado/invalido: no filtro (via AutenticacaoEntryPoint) ou no /auth/refresh
    @ExceptionHandler(TokenInvalidoException.class)
    public ResponseEntity<ProblemDetail> handle401Token(TokenInvalidoException e, HttpServletRequest request) {
        TipoProblema tipo = e.isExpirado() ? TipoProblema.TOKEN_EXPIRADO : TipoProblema.TOKEN_INVALIDO;
        return responder(HttpStatus.UNAUTHORIZED, tipo, e.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handle401(AuthenticationException e, HttpServletRequest request) {
        // Sem token em rota protegida (chega pelo AutenticacaoEntryPoint)
        if (e instanceof InsufficientAuthenticationException || e instanceof AuthenticationCredentialsNotFoundException) {
            return responder(HttpStatus.UNAUTHORIZED, TipoProblema.NAO_AUTENTICADO,
                    "Envie o header Authorization: Bearer <accessToken>.", request);
        }
        // Login com senha errada, e-mail inexistente ou usuario inativo: mensagem unica e generica,
        // para nao revelar se o e-mail esta cadastrado
        return responder(HttpStatus.UNAUTHORIZED, TipoProblema.CREDENCIAIS_INVALIDAS,
                "E-mail ou senha inválidos.", request);
    }

    // US-30: perfil sem permissao (@PreAuthorize) ou recurso de outra concessionaria
    // (ContextoSeguranca). Toda negacao fica registrada na trilha de auditoria.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handle403(AccessDeniedException e, HttpServletRequest request) {
        TipoProblema tipo;
        String detalhe;
        if (e instanceof AcessoForaDoEscopoException) {
            tipo = TipoProblema.OUTRA_CONCESSIONARIA;
            detalhe = e.getMessage();
        } else if (e instanceof AuthorizationDeniedException) {
            tipo = TipoProblema.PERFIL_SEM_PERMISSAO;
            detalhe = "Seu perfil não tem permissão para este recurso.";
        } else {
            tipo = TipoProblema.PERFIL_SEM_PERMISSAO;
            detalhe = e.getMessage();
        }
        auditoria.registrarAcessoNegado(request, detalhe);
        return responder(HttpStatus.FORBIDDEN, tipo, detalhe, request);
    }

    // Login bloqueado pelo LimiteTentativasLogin (forca bruta). Retry-After diz quando tentar de novo
    @ExceptionHandler(MuitasTentativasException.class)
    public ResponseEntity<ProblemDetail> handle429(MuitasTentativasException e, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getSegundosParaLiberar()))
                .body(completar(problema, TipoProblema.MUITAS_REQUISICOES, request));
    }

    // Nenhum detalhe interno para o cliente: nem stack trace, nem nome de classe.
    // O correlationId da resposta leva a linha exata deste log.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle500(Exception e, HttpServletRequest request) {
        log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, TipoProblema.ERRO_INTERNO,
                "Erro inesperado. Informe o correlationId ao suporte.", request);
    }

    // ------------------------------------------------------------------ excecoes do Spring MVC

    // @Valid no corpo: 422 com a lista de campos (o contrato pede 422, nao 400)
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Violacao> violacoes = new ArrayList<>();
        ex.getFieldErrors().forEach(fe -> violacoes.add(new Violacao(fe.getField(), fe.getDefaultMessage())));
        ex.getGlobalErrors().forEach(ge -> violacoes.add(new Violacao(ge.getObjectName(), ge.getDefaultMessage())));
        return validacao(violacoes, request);
    }

    // Boot 4: restricoes em @PathVariable/@RequestParam lancam esta excecao
    // (e nao ConstraintViolationException, como no Boot 3)
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Violacao> violacoes = new ArrayList<>();
        ex.getParameterValidationResults().forEach(resultado -> {
            if (resultado instanceof ParameterErrors errosDoObjeto) {
                // @Valid @RequestBody validado junto com outro parametro restrito (ex.: o header
                // Idempotency-Key do PATCH de lead): os erros vem aqui, e nao em
                // MethodArgumentNotValidException. Aponta o campo do corpo, nao o nome do parametro Java.
                errosDoObjeto.getFieldErrors().forEach(fe -> violacoes.add(new Violacao(fe.getField(), fe.getDefaultMessage())));
                errosDoObjeto.getGlobalErrors().forEach(ge -> violacoes.add(new Violacao(ge.getObjectName(), ge.getDefaultMessage())));
            } else {
                String parametro = nomeNaRequisicao(resultado.getMethodParameter());
                resultado.getResolvableErrors().forEach(erro ->
                        violacoes.add(new Violacao(parametro, erro.getDefaultMessage())));
            }
        });
        return validacao(violacoes, request);
    }

    // Nome que o cliente enxerga ("Idempotency-Key", "vin"), e nao o da variavel Java ("idempotencyKey")
    private static String nomeNaRequisicao(MethodParameter parametro) {
        RequestHeader header = parametro.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isBlank()) return header.value();
        RequestParam param = parametro.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.value().isBlank()) return param.value();
        PathVariable path = parametro.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.value().isBlank()) return path.value();
        return parametro.getParameterName();
    }

    // JSON malformado -> 400. Valor que nao converte para o tipo do campo (enum desconhecido,
    // data invalida, texto em campo numerico) -> 422 apontando o campo, como no exemplo do contrato.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        InvalidFormatException formatoInvalido = buscarCausa(ex, InvalidFormatException.class);
        if (formatoInvalido != null && !formatoInvalido.getPath().isEmpty()) {
            return validacao(List.of(new Violacao(caminhoDoCampo(formatoInvalido), mensagemDeFormato(formatoInvalido))),
                    request);
        }
        return responderObjeto(HttpStatus.BAD_REQUEST, TipoProblema.REQUISICAO_INVALIDA,
                "Corpo da requisição malformado ou ilegível.", request);
    }

    // Parametro de URL com tipo errado (ex.: GET /leads/abc)
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        return responderObjeto(HttpStatus.BAD_REQUEST, TipoProblema.REQUISICAO_INVALIDA,
                "Parâmetro '" + ex.getPropertyName() + "' com valor inválido: '" + ex.getValue() + "'.", request);
    }

    // Todas as demais excecoes do Spring MVC passam por aqui: so completamos o formato
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problema = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(statusCode);
        TipoProblema tipo = TipoProblema.porStatus(statusCode.value());
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();

        switch (tipo) {
            case NAO_ENCONTRADO -> problema.setDetail("Nenhum recurso em " + servletRequest.getRequestURI() + ".");
            case METODO_NAO_PERMITIDO -> problema.setDetail(
                    "Método " + servletRequest.getMethod() + " não é suportado neste recurso.");
            case ERRO_INTERNO -> {
                log.error("Erro inesperado em {} {}", servletRequest.getMethod(), servletRequest.getRequestURI(), ex);
                problema.setDetail("Erro inesperado. Informe o correlationId ao suporte.");
            }
            default -> { } // 400 de parametro ausente/tipo errado: o detail do Spring ja e util
        }

        return ResponseEntity.status(statusCode).headers(headers)
                .body(completar(problema, tipo, servletRequest));
    }

    // ------------------------------------------------------------------ montagem da resposta

    private ResponseEntity<ProblemDetail> responder(HttpStatus status, TipoProblema tipo, String detalhe,
                                                    HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        return ResponseEntity.status(status).body(completar(problema, tipo, request));
    }

    // Os overrides de ResponseEntityExceptionHandler exigem ResponseEntity<Object>
    private ResponseEntity<Object> responderObjeto(HttpStatus status, TipoProblema tipo, String detalhe,
                                                   WebRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        return ResponseEntity.status(status)
                .body(completar(problema, tipo, ((ServletWebRequest) request).getRequest()));
    }

    private ResponseEntity<Object> validacao(List<Violacao> violacoes, WebRequest request) {
        return validacao(violacoes, ((ServletWebRequest) request).getRequest());
    }

    private ResponseEntity<Object> validacao(List<Violacao> violacoes, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "Um ou mais campos estão inválidos.");
        problema.setProperty("violacoes", violacoes);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(completar(problema, TipoProblema.VALIDACAO, request));
    }

    private ProblemDetail completar(ProblemDetail problema, TipoProblema tipo, HttpServletRequest request) {
        problema.setType(tipo.uri());
        problema.setTitle(tipo.titulo());
        problema.setInstance(URI.create(request.getRequestURI()));
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", MDC.get(CorrelationIdFilter.CHAVE_MDC));
        return problema;
    }

    // ------------------------------------------------------------------ utilitarios de JSON

    // Mensagem do QueryUtils do Spring Data JPA ao aplicar um Sort que nao e referencia de propriedade
    private static boolean expressaoDeOrdenacaoRecusada(Throwable ex) {
        for (Throwable atual = ex; atual != null; atual = atual.getCause()) {
            if (atual.getMessage() != null && atual.getMessage().startsWith("Sort expression")) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Throwable> T buscarCausa(Throwable ex, Class<T> tipo) {
        for (Throwable atual = ex; atual != null; atual = atual.getCause()) {
            if (tipo.isInstance(atual)) {
                return tipo.cast(atual);
            }
        }
        return null;
    }

    // Monta "endereco.uf" ou "itens[2].valor" a partir do caminho do Jackson.
    // Nao usar getPathReference(): ele inclui o nome completo da classe (vazamento interno)
    private static String caminhoDoCampo(InvalidFormatException ex) {
        StringBuilder caminho = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!caminho.isEmpty()) caminho.append('.');
                caminho.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                caminho.append('[').append(ref.getIndex()).append(']');
            }
        }
        return caminho.toString();
    }

    private static String mensagemDeFormato(InvalidFormatException ex) {
        Class<?> alvo = ex.getTargetType();
        if (alvo != null && alvo.isEnum()) {
            String aceitos = Arrays.stream(alvo.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "valor não permitido. Aceitos: " + aceitos;
        }
        return "formato inválido";
    }
}
