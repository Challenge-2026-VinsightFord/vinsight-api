package br.com.fiap.vinsight_api.veiculo;

import br.com.fiap.vinsight_api.agendamento.DadosListagemAgendamento;
import br.com.fiap.vinsight_api.config.ErroDocumentado;
import br.com.fiap.vinsight_api.infra.exception.TipoProblema;
import br.com.fiap.vinsight_api.shared.DadosPagina;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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

/**
 * Vehicle Service API (US-34). Substitui o /api/v1/veiculos da v1; o veiculo passa a ser
 * identificado pelo VIN, como no contrato.
 *
 * O @Pattern no {vin} e validado pelo Spring MVC ANTES de o metodo rodar: VIN mal formado
 * responde 422 sem nenhuma consulta ao banco (cenario BDD da US-34).
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@Tag(name = "Veículos (Vehicle Service)", description = "Passaporte do veículo por VIN, cadastro e busca por placa")
@SecurityRequirement(name = "bearer-key")
public class VeiculoController {

    @Autowired
    private VeiculoService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Cadastra um novo veículo para um cliente da carteira")
    @ErroDocumentado(tipo = TipoProblema.CONFLITO, quando = "VIN ou placa já cadastrados.")
    public ResponseEntity<DadosDetalheVeiculo> cadastrar(
            @RequestBody @Valid DadosCadastroVeiculo dados,
            UriComponentsBuilder uriBuilder) {
        DadosDetalheVeiculo veiculo = service.cadastrar(dados);
        var uri = uriBuilder.path("/api/v1/vehicles/{vin}")
                .buildAndExpand(veiculo.vin()).toUri();
        return ResponseEntity.created(uri).body(veiculo);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Busca veículos da carteira por placa (paginado); sem placa, lista a carteira")
    public ResponseEntity<DadosPagina<DadosListagemVeiculo>> listar(
            @Parameter(description = "Placa, com ou sem hífen (ex.: ABC1D23 ou ABC-1D23)")
            @RequestParam(required = false) String placa,
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return ResponseEntity.ok(new DadosPagina<>(service.listar(placa, paginacao)));
    }

    @GetMapping("/{vin}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Passaporte do veículo: garantia, próxima revisão, aderência à rede, telemetria e histórico")
    public ResponseEntity<DadosPassaporteVeiculo> passaporte(
            @PathVariable @Pattern(regexp = Vin.REGEX, message = Vin.MENSAGEM) String vin) {
        return ResponseEntity.ok(service.passaporte(vin));
    }

    @PutMapping("/{vin}")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Atualiza um veículo")
    public ResponseEntity<DadosDetalheVeiculo> atualizar(
            @PathVariable @Pattern(regexp = Vin.REGEX, message = Vin.MENSAGEM) String vin,
            @RequestBody @Valid DadosAtualizacaoVeiculo dados) {
        return ResponseEntity.ok(service.atualizar(vin, dados));
    }

    @DeleteMapping("/{vin}")
    @PreAuthorize("hasAnyRole('GERENTE', 'ADMIN')")
    @Operation(summary = "Inativa um veículo (status=INATIVO)")
    public ResponseEntity<Void> inativar(
            @PathVariable @Pattern(regexp = Vin.REGEX, message = Vin.MENSAGEM) String vin) {
        service.inativar(vin);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{vin}/agendamentos")
    @PreAuthorize("hasAnyRole('CONSULTOR', 'GERENTE', 'ADMIN')")
    @Operation(summary = "Lista agendamentos do veículo na unidade do usuário (paginado)")
    public ResponseEntity<DadosPagina<DadosListagemAgendamento>> listarAgendamentos(
            @PathVariable @Pattern(regexp = Vin.REGEX, message = Vin.MENSAGEM) String vin,
            @ParameterObject @PageableDefault(size = 20, sort = "dataHora") Pageable paginacao) {
        return ResponseEntity.ok(new DadosPagina<>(service.listarAgendamentos(vin, paginacao)));
    }
}
