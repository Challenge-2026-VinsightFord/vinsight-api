package br.com.fiap.vinsight_api.infra.exception;

import java.net.URI;

/**
 * Catalogo dos "type" de erro da API (RFC 7807). O app trata erro por este campo, nunca pela
 * mensagem, entao um valor publicado aqui e contrato: renomear quebra o app.
 */
public enum TipoProblema {

    REQUISICAO_INVALIDA("requisicao-invalida", "Requisição inválida", 400),
    VALIDACAO("validacao", "Erro de validação", 422),
    NAO_AUTENTICADO("nao-autenticado", "Autenticação necessária", 401),
    CREDENCIAIS_INVALIDAS("credenciais-invalidas", "Credenciais inválidas", 401),
    TOKEN_EXPIRADO("token-expirado", "Token expirado", 401),
    TOKEN_INVALIDO("token-invalido", "Token inválido", 401),
    PERFIL_SEM_PERMISSAO("perfil-sem-permissao", "Acesso negado", 403),
    OUTRA_CONCESSIONARIA("outra-concessionaria", "Acesso negado", 403),
    NAO_ENCONTRADO("nao-encontrado", "Recurso não encontrado", 404),
    METODO_NAO_PERMITIDO("metodo-nao-permitido", "Método não permitido", 405),
    FORMATO_NAO_SUPORTADO("formato-nao-suportado", "Formato não suportado", 415),
    CONFLITO("conflito", "Conflito", 409),
    MUITAS_REQUISICOES("muitas-requisicoes", "Muitas requisições", 429),
    ERRO_INTERNO("erro-interno", "Erro interno", 500);

    private static final String BASE = "https://vinsight.ford/errors/";

    private final String codigo;
    private final String titulo;

    // Status HTTP que acompanha este type (usado nos exemplos do Swagger)
    private final int status;

    TipoProblema(String codigo, String titulo, int status) {
        this.codigo = codigo;
        this.titulo = titulo;
        this.status = status;
    }

    public URI uri() {
        return URI.create(BASE + codigo);
    }

    public int status() {
        return status;
    }

    public String titulo() {
        return titulo;
    }

    /** Para as excecoes do proprio Spring MVC, que chegam so com o status. */
    public static TipoProblema porStatus(int status) {
        return switch (status) {
            case 400 -> REQUISICAO_INVALIDA;
            case 401 -> NAO_AUTENTICADO;
            case 403 -> PERFIL_SEM_PERMISSAO;
            case 404 -> NAO_ENCONTRADO;
            case 405 -> METODO_NAO_PERMITIDO;
            case 406, 415 -> FORMATO_NAO_SUPORTADO;
            case 409 -> CONFLITO;
            case 422 -> VALIDACAO;
            case 429 -> MUITAS_REQUISICOES;
            default -> status >= 500 ? ERRO_INTERNO : REQUISICAO_INVALIDA;
        };
    }
}
