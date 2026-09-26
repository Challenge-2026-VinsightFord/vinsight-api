package br.com.fiap.vinsight_api.infra.security;

import br.com.fiap.vinsight_api.usuario.Usuario;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Molde do TokenService do professor, estendido para o contrato:
 * - sub = id do usuario; claims email, perfil e concessionariaId (null para ANALISTA_FORD/ADMIN)
 * - dois tokens: access (15 min) e refresh (8 h), diferenciados pela claim "tipo",
 *   para que um refresh token nao seja aceito como access token e vice-versa
 */
@Service
public class TokenService {

    private static final String ISSUER = "vinsight-api";
    private static final String CLAIM_TIPO = "tipo";
    private static final String TIPO_ACCESS = "ACCESS";
    private static final String TIPO_REFRESH = "REFRESH";

    @Value("${api.security.token.secret}")
    private String secret;

    @Value("${api.security.token.access-expiration-minutes}")
    private long minutosAccess;

    @Value("${api.security.token.refresh-expiration-hours}")
    private long horasRefresh;

    // HS256 exige chave de pelo menos 256 bits (RFC 7518, 3.2). Segredo curto = token forjavel por
    // forca bruta offline. A aplicacao nao sobe com segredo fraco (no perfil prod, nem sem segredo).
    @PostConstruct
    void validarSegredo() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET precisa ter pelo menos 32 bytes (256 bits) para o HMAC-256.");
        }
    }

    public String gerarAccessToken(Usuario usuario) {
        return gerarToken(usuario, TIPO_ACCESS, Duration.ofMinutes(minutosAccess));
    }

    public String gerarRefreshToken(Usuario usuario) {
        return gerarToken(usuario, TIPO_REFRESH, Duration.ofHours(horasRefresh));
    }

    // Campo "expiresIn" da resposta de login: validade do access token em segundos
    public long getSegundosExpiracaoAccess() {
        return Duration.ofMinutes(minutosAccess).toSeconds();
    }

    /** Valida um access token e devolve o id do usuario (subject). */
    public Long validarAccessToken(String tokenJWT) {
        return validarToken(tokenJWT, TIPO_ACCESS);
    }

    /** Valida um refresh token e devolve o id do usuario (subject). */
    public Long validarRefreshToken(String tokenJWT) {
        return validarToken(tokenJWT, TIPO_REFRESH);
    }

    private String gerarToken(Usuario usuario, String tipo, Duration validade) {
        try {
            Instant agora = Instant.now();
            JWTCreator.Builder builder = JWT.create()
                    .withHeader(Map.of("typ", "JWT"))
                    .withIssuer(ISSUER)
                    .withSubject(usuario.getId().toString())
                    .withClaim("email", usuario.getEmail())
                    .withClaim("perfil", usuario.getPerfil().name())
                    .withClaim(CLAIM_TIPO, tipo)
                    .withIssuedAt(agora)
                    .withExpiresAt(agora.plus(validade));

            if (usuario.getConcessionaria() == null) {
                builder.withNullClaim("concessionariaId");
            } else {
                builder.withClaim("concessionariaId", usuario.getConcessionaria().getId());
            }

            return builder.sign(algoritmo());
        } catch (JWTCreationException ex) {
            throw new RuntimeException("Erro ao gerar o token JWT!", ex);
        }
    }

    private Long validarToken(String tokenJWT, String tipoEsperado) {
        try {
            String subject = JWT.require(algoritmo())
                    .withIssuer(ISSUER)
                    .withClaim(CLAIM_TIPO, tipoEsperado)
                    .build()
                    .verify(tokenJWT)
                    .getSubject();
            return Long.valueOf(subject);
        } catch (TokenExpiredException ex) {
            throw new TokenInvalidoException("Token JWT expirado.", ex, true);
        } catch (JWTVerificationException | NumberFormatException ex) {
            throw new TokenInvalidoException("Token JWT inválido.", ex, false);
        }
    }

    private Algorithm algoritmo() {
        return Algorithm.HMAC256(secret);
    }
}
