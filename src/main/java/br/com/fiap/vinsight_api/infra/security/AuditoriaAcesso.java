package br.com.fiap.vinsight_api.infra.security;

import br.com.fiap.vinsight_api.usuario.Usuario;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Trilha de auditoria de seguranca: acessos negados (US-30), logins (sucesso, falha e bloqueio)
 * e alteracoes criticas (desfecho de lead, que realimenta o modelo).
 *
 * Logger dedicado "AUDITORIA": filtre no log com
 *   Select-String AUDITORIA   (PowerShell)   ou   grep AUDITORIA   (bash)
 * Toda linha sai tambem com o correlationId da requisicao. Nunca registra senha nem token.
 */
@Component
public class AuditoriaAcesso {

    private static final Logger AUDITORIA = LoggerFactory.getLogger("AUDITORIA");

    public void registrarAcessoNegado(HttpServletRequest request, String motivo) {
        AUDITORIA.warn("ACESSO_NEGADO {} {} usuario[{}] motivo=\"{}\"",
                request.getMethod(), request.getRequestURI(), descreverUsuarioLogado(), motivo);
    }

    public void registrarLogin(Usuario usuario, HttpServletRequest request) {
        AUDITORIA.info("LOGIN_OK usuario[{}] ip={}", descrever(usuario), request.getRemoteAddr());
    }

    /** O e-mail vem digitado pelo cliente: sai mascarado (LGPD) e sem caracteres de controle. */
    public void registrarFalhaLogin(String email, HttpServletRequest request) {
        AUDITORIA.warn("LOGIN_FALHA email={} ip={}", mascararEmail(email), request.getRemoteAddr());
    }

    public void registrarLoginBloqueado(HttpServletRequest request, long segundos) {
        AUDITORIA.warn("LOGIN_BLOQUEADO ip={} retryAfter={}s", request.getRemoteAddr(), segundos);
    }

    public void registrarDesfecho(Long leadId, Object desfecho) {
        AUDITORIA.info("DESFECHO_REGISTRADO lead={} desfecho={} usuario[{}]",
                leadId, desfecho, descreverUsuarioLogado());
    }

    private String descreverUsuarioLogado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Usuario usuario) {
            return descrever(usuario);
        }
        return "anonimo";
    }

    private static String descrever(Usuario usuario) {
        Long concessionariaId = usuario.getConcessionaria() == null ? null : usuario.getConcessionaria().getId();
        return "id=%d email=%s perfil=%s concessionariaId=%s".formatted(
                usuario.getId(), usuario.getEmail(), usuario.getPerfil(), concessionariaId);
    }

    // c****@ford.com.br. Tudo fora de [letra, digito, . _ @ + -] vira "_": sem quebra de linha
    // forjada no log (log injection, OWASP A09)
    static String mascararEmail(String email) {
        if (email == null || email.isBlank()) return "(vazio)";
        String seguro = email.replaceAll("[^A-Za-z0-9._@+-]", "_");
        if (seguro.length() > 100) seguro = seguro.substring(0, 100);
        int arroba = seguro.indexOf('@');
        return arroba < 1 ? "****" : seguro.charAt(0) + "****" + seguro.substring(arroba);
    }
}
