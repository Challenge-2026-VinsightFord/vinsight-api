package br.com.fiap.vinsight_api.concessionaria;

import br.com.fiap.vinsight_api.agendamento.AgendamentoService;
import br.com.fiap.vinsight_api.agendamento.DadosListagemAgendamento;
import br.com.fiap.vinsight_api.config.ErroDocumentado;
import br.com.fiap.vinsight_api.infra.exception.TipoProblema;
import br.com.fiap.vinsight_api.shared.DadosPagina;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/concessionarias")
@Tag(name = "Concessionárias", description = "Cadastro e gestão de concessionárias Ford")
@SecurityRequirement(name = "bearer-key")
public class ConcessionariaController {

    @Autowired
    private ConcessionariaService service;

    @Autowired
    private AgendamentoService agendamentoService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cadastra uma nova concessionária")
    @ErroDocumentado(tipo = TipoProblema.CONFLITO, quando = "CNPJ já cadastrado.")
    public ResponseEntity<DadosDetalheConcessionaria> cadastrar(
            @RequestBody @Valid DadosCadastroConcessionaria dados,
            UriComponentsBuilder uriBuilder) {
        DadosDetalheConcessionaria concessionaria = service.cadastrar(dados);
        var uri = uriBuilder.path("/api/v1/concessionarias/{id}")
                .buildAndExpand(concessionaria.id()).toUri();
        return ResponseEntity.created(uri).body(concessionaria);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ANALISTA_FORD', 'ADMIN')")
    @Operation(summary = "Lista concessionárias ativas paginadas")
    public ResponseEntity<DadosPagina<DadosListagemConcessionaria>> listar(
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return ResponseEntity.ok(new DadosPagina<>(service.listar(paginacao)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ANALISTA_FORD', 'ADMIN')")
    @Operation(summary = "Detalha uma concessionária por ID")
    public ResponseEntity<DadosDetalheConcessionaria> detalhar(@PathVariable Long id) {
        return ResponseEntity.ok(service.detalhar(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualiza uma concessionária")
    public ResponseEntity<DadosDetalheConcessionaria> atualizar(
            @PathVariable Long id,
            @RequestBody @Valid DadosAtualizacaoConcessionaria dados) {
        return ResponseEntity.ok(service.atualizar(id, dados));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Inativa uma concessionária (soft delete)")
    public ResponseEntity<Void> inativar(@PathVariable Long id) {
        service.inativar(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/agendamentos")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Lista agendamentos de uma concessionária (paginado)")
    public ResponseEntity<DadosPagina<DadosListagemAgendamento>> listarAgendamentos(
            @PathVariable Long id,
            @ParameterObject @PageableDefault(size = 20, sort = "dataHora") Pageable paginacao) {
        return ResponseEntity.ok(new DadosPagina<>(agendamentoService.listarPorConcessionaria(id, paginacao)));
    }
}
