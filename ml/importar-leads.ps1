# Envia para a API os leads gerados pelo notebook (saida/leads_modelo.csv): deploy em lote do modelo.
#
#   powershell -ExecutionPolicy Bypass -File ml\importar-leads.ps1
#
# - Autentica como ADMIN, a conta de servico da camada de inteligencia (unico perfil que pode criar lead)
# - Ignora veiculos que ja tem lead aberto na fila (OPEN, CONTATADO ou SEM_SUCESSO): nao duplica
# - So envia os veiculos com geraLead = 1 (score acima do limiar de decisao do modelo)
# - Cliente sem consentimento LGPD e enviado mesmo assim: a API grava o lead ja suprimido e ele nunca
#   aparece na fila (a regra de privacidade fica num lugar so)
param(
    [string]$Api = "http://localhost:8080/api/v1",
    [string]$Email = "admin@ford.com.br",
    [string]$Senha = "admin123",
    [string]$Arquivo = (Join-Path $PSScriptRoot "saida\leads_modelo.csv")
)

$ErrorActionPreference = "Stop"

# PowerShell 5.1 envia o corpo em Latin-1; a API exige UTF-8 (motivos com acento)
function Enviar($metodo, $url, $corpo, $token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    # Atribuir dentro do if: "$bytes = if (...)" desmontaria o byte[] num object[] e corromperia o corpo
    $bytes = $null
    if ($corpo) { $bytes = [System.Text.Encoding]::UTF8.GetBytes(($corpo | ConvertTo-Json -Compress)) }
    Invoke-RestMethod -Method $metodo -Uri $url -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes
}

$token = (Enviar POST "$Api/auth/login" @{ email = $Email; senha = $Senha }).accessToken

$comLeadAberto = @{}
foreach ($status in "OPEN", "CONTATADO", "SEM_SUCESSO") {
    $pagina = Enviar GET "$Api/leads?status=$status&size=500" $null $token
    $pagina.content | ForEach-Object { $comLeadAberto[$_.vin] = $true }
}
Write-Host ("Veiculos com lead aberto na fila: {0}" -f $comLeadAberto.Count)

$criados = 0; $suprimidos = 0; $ignorados = 0
foreach ($l in (Import-Csv -Path $Arquivo -Encoding UTF8)) {
    if ($l.geraLead -ne "1") { continue }
    if ($comLeadAberto.ContainsKey($l.vin)) { $ignorados++; continue }

    $lead = [ordered]@{
        clienteId            = [long]$l.clienteId
        veiculoId            = [long]$l.veiculoId
        score                = [double]::Parse($l.score, [Globalization.CultureInfo]::InvariantCulture)
        prioridade           = $l.prioridade
        motivo               = $l.motivo
        acaoRecomendada      = $l.acaoRecomendada
        perfilComportamental = $l.perfilComportamental
    }
    $resposta = Enviar POST "$Api/leads" $lead $token
    if ($l.consentimentoAtivo -eq "0") { $suprimidos++ } else { $criados++ }
    Write-Host ("  lead {0,-4} {1}  score {2}  {3,-8} {4}" -f $resposta.id, $l.vin, $l.score, $l.prioridade, $l.motivo)
}

Write-Host ""
Write-Host ("Leads criados na fila: {0} | registrados ja suprimidos (sem consentimento LGPD): {1} | ignorados (ja tinham lead aberto): {2}" -f $criados, $suprimidos, $ignorados)
