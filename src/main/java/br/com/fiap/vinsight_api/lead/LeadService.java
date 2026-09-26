package br.com.fiap.vinsight_api.lead;

import br.com.fiap.vinsight_api.cliente.Cliente;
import br.com.fiap.vinsight_api.cliente.ClienteRepository;
import br.com.fiap.vinsight_api.infra.exception.EntidadeNaoEncontradaException;
import br.com.fiap.vinsight_api.infra.exception.RegraNegocioException;
import br.com.fiap.vinsight_api.infra.idempotencia.ServicoIdempotencia;
import br.com.fiap.vinsight_api.infra.security.AuditoriaAcesso;
import br.com.fiap.vinsight_api.infra.security.ContextoSeguranca;
import br.com.fiap.vinsight_api.infra.security.MascaradorDados;
import br.com.fiap.vinsight_api.veiculo.Veiculo;
import br.com.fiap.vinsight_api.veiculo.VeiculoRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Lead Engine (US-35): fila priorizada por concessionaria, registro de desfecho e supressao LGPD.
 */
@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    @Autowired
    private AuditoriaAcesso auditoria;

    @Autowired
    private LeadRepository repository;

    @Autowired
    private DesfechoLeadRepository desfechoRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private VeiculoRepository veiculoRepository;

    @Autowired
    private ContextoSeguranca contexto;

    @Autowired
    private MascaradorDados mascarador;

    @Autowired
    private ServicoIdempotencia idempotencia;

    @Autowired
    private Clock clock;

    /**
     * Entrada de lead vindo do modelo de churn. Cliente sem consentimento: o lead e gravado, mas
     * ja suprimido (LGPD_OPT_OUT), para ficar registrado por que nao entrou na fila.
     */
    @Transactional
    public DadosDetalheLead cadastrar(DadosCadastroLead dados) {
        Cliente cliente = clienteRepository.findById(dados.clienteId())
                .orElseThrow(() -> new EntidadeNaoEncontradaException(
                        "Cliente com id " + dados.clienteId() + " não encontrado."));
        Veiculo veiculo = veiculoRepository.findById(dados.veiculoId())
                .orElseThrow(() -> new EntidadeNaoEncontradaException(
                        "Veículo com id " + dados.veiculoId() + " não encontrado."));
        if (!veiculo.getCliente().getId().equals(cliente.getId())) {
            throw new RegraNegocioException("Veículo não pertence ao cliente informado.");
        }

        Lead lead = new Lead(dados, cliente, veiculo);
        if (!cliente.isConsentimentoAtivo()) {
            lead.suprimir(MotivoSupressao.LGPD_OPT_OUT, LocalDateTime.now(clock));
        }
        repository.save(lead);
        if (lead.suprimido()) {
            log.info("Lead {} suprimido na entrada: cliente {} sem consentimento (LGPD_OPT_OUT)",
                    lead.getId(), cliente.getId());
        }
        return new DadosDetalheLead(lead, desfechoRepository.findAllByLeadIdOrderByRegistradoEmDescIdDesc(lead.getId()), mascarador);
    }

    /** Fila priorizada: so a unidade do usuario, sem suprimidos, filtros opcionais. */
    @Transactional
    public Page<DadosFilaLead> fila(StatusLead status, FaixaRisco risco, Long clienteId, Pageable paginacao) {
        return repository.fila(
                        status,
                        risco == null ? null : risco.scoreMinimo(),
                        risco == null ? null : risco.scoreMaximoExclusivo(),
                        clienteId,
                        contexto.concessionariaEscopo(),
                        paginacao)
                .map(lead -> new DadosFilaLead(lead, mascarador));
    }

    @Transactional
    public DadosDetalheLead detalhar(Long id) {
        return detalhe(buscar(id));
    }

    /**
     * PATCH /leads/{id}: aplica o desfecho e grava o registro no historico (retreinamento).
     * Com Idempotency-Key, um reenvio da mesma acao devolve a resposta original sem duplicar nada.
     */
    @Transactional
    public ServicoIdempotencia.Resultado<DadosDetalheLead> registrarDesfecho(Long id, DadosDesfecho dados,
                                                                            String idempotencyKey) {
        // Escopo antes de tudo, inclusive do reenvio: a chave nao da acesso a lead de outra unidade
        Lead lead = buscar(id);
        return idempotencia.executar(idempotencyKey, "PATCH", "/api/v1/leads/" + id, dados,
                DadosDetalheLead.class, () -> {
                    LocalDateTime agora = LocalDateTime.now(clock);
                    lead.registrarDesfecho(dados.desfecho(), agora);
                    desfechoRepository.save(new DesfechoLead(lead, dados, contexto.usuarioLogado(), agora));
                    // Alteracao critica: realimenta o modelo. Reenvio idempotente nao passa aqui (nao duplica)
                    auditoria.registrarDesfecho(lead.getId(), dados.desfecho());
                    return detalhe(lead);
                });
    }

    private DadosDetalheLead detalhe(Lead lead) {
        return new DadosDetalheLead(lead,
                desfechoRepository.findAllByLeadIdOrderByRegistradoEmDescIdDesc(lead.getId()),
                mascarador);
    }

    // Todo acesso individual passa por aqui: 404 se nao existe, 403 se e de outra unidade
    private Lead buscar(Long id) {
        Lead lead = repository.findById(id)
                .orElseThrow(() -> new EntidadeNaoEncontradaException(
                        "Lead com id " + id + " não encontrado."));
        contexto.verificarAcesso(lead.getConcessionaria());
        return lead;
    }
}
