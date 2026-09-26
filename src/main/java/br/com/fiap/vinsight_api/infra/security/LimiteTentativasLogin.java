package br.com.fiap.vinsight_api.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit do login contra forca bruta e credential stuffing (OWASP API4:2023).
 *
 * Conta as falhas (401) do POST /api/v1/auth/login por IP numa janela fixa. Ao atingir o limite
 * (padrao: 5 falhas em 60 s), o IP recebe 429 com Retry-After ate a janela acabar, sem nem chegar
 * a consultar o banco. Login certo nao conta: varios consultores atras do mesmo IP da
 * concessionaria nao se bloqueiam.
 *
 * Contador em memoria: suficiente para uma instancia. Com varias instancias, o mesmo contrato vai
 * para um store compartilhado (Redis) ou para o API Gateway.
 *
 * Usa o IP da conexao (getRemoteAddr), nunca o X-Forwarded-For, que o cliente forja a vontade.
 * Atras de proxy confiavel: server.forward-headers-strategy=native.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // logo depois do CorrelationIdFilter
public class LimiteTentativasLogin extends OncePerRequestFilter {

    static final String ROTA_LOGIN = "/api/v1/auth/login";

    // Acima disto, limpa as janelas vencidas (evita crescer sem limite sob ataque distribuido)
    private static final int MAX_IPS_EM_MEMORIA = 10_000;

    private record Janela(Instant fim, int falhas) {
        boolean vencida(Instant agora) {
            return !agora.isBefore(fim);
        }
    }

    private final Map<String, Janela> janelas = new ConcurrentHashMap<>();

    @Value("${api.security.login.max-falhas:5}")
    private int maxFalhas;

    @Value("${api.security.login.janela-segundos:60}")
    private long janelaSegundos;

    @Autowired
    private Clock clock;

    @Autowired
    private AuditoriaAcesso auditoria;

    @Autowired
    @Qualifier("handlerExceptionResolver")
    private HandlerExceptionResolver resolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && ROTA_LOGIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        Instant agora = clock.instant();

        Janela janela = janelas.get(ip);
        if (janela != null && !janela.vencida(agora) && janela.falhas() >= maxFalhas) {
            long segundos = Math.max(1, Duration.between(agora, janela.fim()).toSeconds());
            auditoria.registrarLoginBloqueado(request, segundos);
            // Mesmo caminho do AutenticacaoEntryPoint: o GlobalExceptionHandler monta o problem+json
            resolver.resolveException(request, response, null, new MuitasTentativasException(segundos));
            return;
        }

        filterChain.doFilter(request, response);

        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            registrarFalha(ip, agora);
        }
    }

    private void registrarFalha(String ip, Instant agora) {
        if (janelas.size() > MAX_IPS_EM_MEMORIA) {
            janelas.values().removeIf(j -> j.vencida(agora));
        }
        janelas.compute(ip, (chave, atual) -> atual == null || atual.vencida(agora)
                ? new Janela(agora.plusSeconds(janelaSegundos), 1)
                : new Janela(atual.fim(), atual.falhas() + 1));
    }
}
