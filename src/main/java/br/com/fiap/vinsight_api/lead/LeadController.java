package br.com.fiap.vinsight_api.lead;

import br.com.fiap.vinsight_api.config.ErroDocumentado;
import br.com.fiap.vinsight_api.infra.exception.TipoProblema;
import br.com.fiap.vinsight_api.infra.idempotencia.ServicoIdempotencia;
import br.com.fiap.vinsight_api.shared.DadosPagina;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Lead Engine API (US-35): a fila do app do consultor.
 * Substitui o PATCH /{id}/status e o /{id}/conversao da v1 pelo PATCH /{id} com desfecho.
 */
@RestController
@RequestMapping("/api/v1/leads")
@Tag(name = "Leads (Lead Engine)", description = "Fila priorizada de leads de retenção e registro de desfecho")
@SecurityRequirement(name = "bearer-key")
public class LeadController {

    @Autowired
    private LeadService service;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registra um lead gerado pelo modelo de churn (cliente sem consentimento entra já suprimido)")
    @ErroDocumentado(tipo = TipoProblema.CONFLITO, quando = "O veículo não pertence ao cliente informado.")
    public ResponseEntity<DadosDetalheLead> cadastrar(
            @RequestBody @Valid DadosCadastroLead dados,
            UriComponentsBuilder uriBuilder) {
        DadosDetalheLead lead = service.cadastrar(dados);
        var uri = uriBuilder.path("/api/v1/leads/{id}")
                .buildAndExpand(lead.id()).toUri();
        return ResponseEntity.created(uri).body(lead);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Fila de leads da unidade, por padrão do maior score para o menor (sem leads suprimidos)",
            description = "A fila do dia do consultor: use `?status=OPEN`. Vem sempre restrita à concessionária do "
                    + "usuário (tirada do token) e nunca traz leads de clientes sem consentimento (LGPD). "
                    + "`faixaRisco` é derivada do score: ALTO (>= 0,70), MEDIO (0,40 a 0,69), BAIXO (< 0,40).")
    public ResponseEntity<DadosPagina<DadosFilaLead>> fila(
            @Parameter(description = "Ex.: OPEN para a fila de trabalho do dia")
            @RequestParam(required = false) StatusLead status,
            @Parameter(description = "ALTO (score >= 0.70), MEDIO (0.40 a 0.69) ou BAIXO (< 0.40)")
            @RequestParam(required = false) FaixaRisco risco,
            @RequestParam(required = false) Long clienteId,
            @ParameterObject @PageableDefault(size = 20, sort = "score", direction = Sort.Direction.DESC) Pageable paginacao) {
        return ResponseEntity.ok(new DadosPagina<>(service.fila(status, risco, clienteId, paginacao)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Detalha um lead, com supressão (se houver) e histórico de desfechos")
    public ResponseEntity<DadosDetalheLead> detalhar(@PathVariable Long id) {
        return ResponseEntity.ok(service.detalhar(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Registra o desfecho de um contato (CONTATADO, AGENDADO, SEM_SUCESSO, RECUSADO, NUMERO_INVALIDO)",
            description = "Cada desfecho fica gravado no histórico do lead (base do retreinamento do modelo). "
                    + "Envie um UUID no header `Idempotency-Key`, gerado quando o usuário toca no botão: "
                    + "reenviar a mesma chave (ex.: depois de uma queda de rede) devolve a resposta original, "
                    + "com o header `Idempotent-Replayed: true`, sem gravar de novo. A mesma chave com outro corpo responde 422.")
    @ErroDocumentado(tipo = TipoProblema.CONFLITO, quando = "Lead já encerrado (AGENDADO, RECUSADO ou NUMERO_INVALIDO) ou suprimido pela LGPD.")
    public ResponseEntity<DadosDetalheLead> registrarDesfecho(
            @PathVariable Long id,
            @Parameter(description = "UUID gerado pelo app por ação. Reenviar a mesma chave não duplica o registro.")
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
            @RequestBody @Valid DadosDesfecho dados) {
        ServicoIdempotencia.Resultado<DadosDetalheLead> resultado =
                service.registrarDesfecho(id, dados, idempotencyKey);
        // Sinaliza ao app que esta resposta e a gravada da primeira tentativa
        return ResponseEntity.ok()
                .header("Idempotent-Replayed", String.valueOf(resultado.repetido()))
                .body(resultado.corpo());
    }
}
