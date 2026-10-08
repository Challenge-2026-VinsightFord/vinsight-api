-- Exporta a frota da massa de demonstração (banco "vinsight", perfil dev) para o modelo de churn.
-- Uma linha por veículo ativo, só com o que o modelo usa: dados do veículo e do relacionamento com a
-- rede. Sem nome, CPF, e-mail, telefone ou endereço (LGPD: minimização).
--
-- Uso (PowerShell, na pasta ml):
--   & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p vinsight -B -e "source exportar_frota.sql" > dados\frota_demo.tsv
SELECT v.id                         AS veiculo_id,
       v.cliente_id,
       v.vin,
       v.modelo,
       v.ano_modelo,
       v.data_compra,
       v.quilometragem_estimada     AS km_estimada,
       v.garantia_data_limite,
       v.proxima_revisao_data,
       v.proxima_revisao_km,
       v.telemetria_codigos_falha,
       c.ultimo_nps,
       c.canal_preferido,
       c.opt_in_whats_app,
       c.consentimento_ativo,
       COUNT(o.id)                  AS os_total,
       COALESCE(SUM(o.na_rede), 0)  AS os_na_rede,
       MAX(o.data_servico)          AS ultimo_servico,
       MAX(CASE WHEN o.na_rede = 1 THEN o.data_servico END) AS ultimo_servico_rede,
       ROUND(AVG(o.valor), 2)       AS ticket_medio
FROM veiculos v
JOIN clientes c ON c.id = v.cliente_id
LEFT JOIN ordens_servico o ON o.veiculo_id = v.id
WHERE v.status = 'ATIVO'
GROUP BY v.id, c.id
ORDER BY v.id;
