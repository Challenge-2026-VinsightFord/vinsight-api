package br.com.fiap.vinsight_api.infra.security;

/**
 * Login bloqueado temporariamente por excesso de falhas vindas do mesmo IP (LimiteTentativasLogin).
 * Vira 429 com header Retry-After no GlobalExceptionHandler.
 */
public class MuitasTentativasException extends RuntimeException {

    private final long segundosParaLiberar;

    public MuitasTentativasException(long segundosParaLiberar) {
        super("Muitas tentativas de login. Tente novamente em " + segundosParaLiberar + " segundos.");
        this.segundosParaLiberar = segundosParaLiberar;
    }

    public long getSegundosParaLiberar() {
        return segundosParaLiberar;
    }
}
