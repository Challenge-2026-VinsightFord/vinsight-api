# Camada de inteligência — modelo de churn e segmentação

[![Abrir no Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/Challenge-2026-VinsightFord/vinsight-api/blob/master/ml/vinsight_churn.ipynb)

O notebook [`vinsight_churn.ipynb`](vinsight_churn.ipynb) produz o score de risco de evasão e o perfil
comportamental que a API usa para montar a fila de leads do consultor.

| Etapa | Conteúdo |
|---|---|
| Problema | Classificação binária: o veículo vai ficar 12 meses sem serviço na rede? + agrupamento em perfis |
| Dados | Base de treino sintética (8.000 veículos, mesmas colunas da frota real) + a frota da demonstração exportada do MySQL |
| Preparação | Erros de digitação de km, NPS ausente como sinal, variáveis derivadas, one-hot, padronização, divisão estratificada |
| Modelos | Regressão Logística, Random Forest, Gradient Boosting e MLP, com validação cruzada de 5 dobras e busca de hiperparâmetros |
| Escolha | Maior precisão com recall de pelo menos 80% → **Regressão Logística**: recall 78,8%, precisão 78,7%, ROC-AUC 0,903 no teste |
| Segmentação | K-Means k=4 (Fiel, Abandono, Esquecido, Econômico), silhueta e cotovelo, comparado com o hierárquico |
| Deploy | Pontua a frota e gera `saida/leads_modelo.csv` no formato do `POST /api/v1/leads` |

## Como rodar

**No Colab:** clique no selo acima e use *Ambiente de execução → Executar tudo*. A frota da demonstração é
lida direto do GitHub.

**Localmente** (Python 3.10+):

```bash
pip install scikit-learn pandas matplotlib jupyter
jupyter nbconvert --to notebook --execute --inplace ml/vinsight_churn.ipynb
```

## Fluxo completo com a API

1. **Exportar a frota** do banco da demonstração. Só dados do veículo e do relacionamento, sem dados pessoais:
   ```powershell
   & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p vinsight -B -e "source ml/exportar_frota.sql" > ml\dados\frota.tsv
   ```
   O arquivo versionado `dados/frota_demo.csv` já é essa exportação, convertida para CSV.
2. **Rodar o notebook**, que gera `saida/leads_modelo.csv`.
3. **Enviar os leads** para a API, que precisa estar no ar:
   ```powershell
   powershell -ExecutionPolicy Bypass -File ml\importar-leads.ps1
   ```
   O script entra como ADMIN (a conta de serviço da camada de inteligência), pula os veículos que já têm
   lead aberto e cria os demais. Cliente sem consentimento LGPD é registrado já suprimido pela própria API.

## Arquivos

| Arquivo | Para quê |
|---|---|
| `vinsight_churn.ipynb` | Notebook completo, já executado (gráficos e tabelas salvos) |
| `exportar_frota.sql` | Consulta que exporta a frota do MySQL |
| `dados/frota_demo.csv` | Frota da massa de demonstração (37 veículos) |
| `saida/leads_modelo.csv` | Score, prioridade, motivo, ação e perfil de cada veículo |
| `saida/*.png` | Avaliação dos modelos, ganho da priorização e importância das variáveis |
| `importar-leads.ps1` | Deploy em lote: cria os leads pela API |
