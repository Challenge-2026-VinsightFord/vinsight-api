package br.com.fiap.vinsight_api.usuario;

import br.com.fiap.vinsight_api.config.ErroDocumentado;
import br.com.fiap.vinsight_api.infra.exception.TipoProblema;
import br.com.fiap.vinsight_api.infra.security.AuditoriaAcesso;
import br.com.fiap.vinsight_api.infra.security.DadosTokenJWT;
import br.com.fiap.vinsight_api.infra.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Login e renovação de token JWT (endpoints públicos)")
public class AuthController {

    @Autowired
    private AuthenticationManager manager;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UsuarioRepository repository;

    @Autowired
    private AuditoriaAcesso auditoria;

    @PostMapping("/login")
    @Operation(summary = "Autentica por e-mail e senha e devolve access token (15 min) e refresh token (8 h)")
    @ErroDocumentado(tipo = TipoProblema.CREDENCIAIS_INVALIDAS, quando = "E-mail ou senha inválidos. E-mail inexistente e usuário inativo dão a mesma resposta.")
    @ErroDocumentado(tipo = TipoProblema.MUITAS_REQUISICOES, quando = "5 falhas de login do mesmo IP em 1 minuto: bloqueado até a janela acabar (header Retry-After).")
    public ResponseEntity<DadosTokenJWT> login(@RequestBody @Valid DadosLogin dados, HttpServletRequest request) {
        var token = new UsernamePasswordAuthenticationToken(dados.email(), dados.senha());
        Authentication authentication;
        try {
            authentication = manager.authenticate(token);
        } catch (AuthenticationException e) {
            auditoria.registrarFalhaLogin(dados.email(), request);
            throw e;
        }
        Usuario usuario = (Usuario) authentication.getPrincipal();
        auditoria.registrarLogin(usuario, request);
        return ResponseEntity.ok(gerarTokens(usuario));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Troca um refresh token válido por um novo par de tokens")
    @ErroDocumentado(tipo = TipoProblema.TOKEN_INVALIDO, quando = "Refresh token adulterado, ou access token enviado no lugar dele.")
    @ErroDocumentado(tipo = TipoProblema.TOKEN_EXPIRADO, quando = "Refresh token vencido (8 horas): é preciso fazer login de novo.")
    public ResponseEntity<DadosTokenJWT> refresh(@RequestBody @Valid DadosRefresh dados) {
        Long usuarioId = tokenService.validarRefreshToken(dados.refreshToken());
        // Usuario apagado ou inativado depois do login nao renova a sessao
        Usuario usuario = repository.findById(usuarioId)
                .filter(Usuario::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Usuário inexistente ou inativo"));
        return ResponseEntity.ok(gerarTokens(usuario));
    }

    private DadosTokenJWT gerarTokens(Usuario usuario) {
        return new DadosTokenJWT(
                tokenService.gerarAccessToken(usuario),
                tokenService.gerarRefreshToken(usuario),
                tokenService.getSegundosExpiracaoAccess(),
                new DadosUsuarioLogado(usuario));
    }
}
