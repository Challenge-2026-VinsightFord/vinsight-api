package br.com.fiap.vinsight_api.cliente;

import br.com.fiap.vinsight_api.config.ErroDocumentado;
import br.com.fiap.vinsight_api.infra.exception.TipoProblema;
import br.com.fiap.vinsight_api.shared.DadosPagina;
import br.com.fiap.vinsight_api.veiculo.DadosListagemVeiculo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * Customer Service API (US-33). Substitui o /api/v1/clientes da v1.
 *
 * Todo cliente e filtrado pela carteira da unidade do usuario (ClienteRepository.CARTEIRA) e
 * sai com CPF/telefone/e-mail mascarados para o CONSULTOR (MascaradorDados).
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Clientes (Customer Service)", description = "Cadastro, busca e visão 360° do cliente Ford")
@SecurityRequirement(name = "bearer-key")
public class ClienteController {

    @Autowired
    private ClienteService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Cadastra um novo cliente (entra na carteira da unidade de quem cadastrou)")
    @ErroDocumentado(tipo = TipoProblema.CONFLITO, quando = "CPF já cadastrado.")
    public ResponseEntity<DadosDetalheCliente> cadastrar(
            @RequestBody @Valid DadosCadastroCliente dados,
            UriComponentsBuilder uriBuilder) {
        DadosDetalheCliente cliente = service.cadastrar(dados);
        var uri = uriBuilder.path("/api/v1/customers/{id}")
                .buildAndExpand(cliente.id()).toUri();
        return ResponseEntity.created(uri).body(cliente);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Busca clientes ativos da carteira por nome parcial, CPF ou telefone (paginado)")
    public ResponseEntity<DadosPagina<DadosResumoCliente>> buscar(
            @Parameter(description = "Nome parcial, CPF ou trecho do telefone. Vazio lista a carteira inteira.")
            @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return ResponseEntity.ok(new DadosPagina<>(service.buscar(q, paginacao)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Detalha o cadastro de um cliente")
    public ResponseEntity<DadosDetalheCliente> detalhar(@PathVariable Long id) {
        return ResponseEntity.ok(service.detalhar(id));
    }

    @GetMapping("/{id}/overview")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Visão 360°: cadastro, veículos, consentimento, NPS e resumo do histórico de serviços")
    public ResponseEntity<DadosVisao360Cliente> visao360(@PathVariable Long id) {
        return ResponseEntity.ok(service.visao360(id));
    }

    @GetMapping("/{id}/vehicles")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Lista os veículos de um cliente")
    public ResponseEntity<List<DadosListagemVeiculo>> listarVeiculos(@PathVariable Long id) {
        return ResponseEntity.ok(service.listarVeiculos(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Atualiza um cliente (cada seção enviada substitui a atual)")
    public ResponseEntity<DadosDetalheCliente> atualizar(
            @PathVariable Long id,
            @RequestBody @Valid DadosAtualizacaoCliente dados) {
        return ResponseEntity.ok(service.atualizar(id, dados));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('GERENTE', 'ADMIN')")
    @Operation(summary = "Inativa um cliente (soft delete)")
    public ResponseEntity<Void> inativar(@PathVariable Long id) {
        service.inativar(id);
        return ResponseEntity.noContent().build();
    }
}
