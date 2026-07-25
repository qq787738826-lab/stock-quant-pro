param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]{6}$')]
    [string]$Symbol,

    [Parameter(Mandatory = $true)]
    [datetime]$StartDate,

    [Parameter(Mandatory = $true)]
    [datetime]$EndDate,

    [string]$ServerUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

if ($EndDate.Date -lt $StartDate.Date) {
    throw 'EndDate cannot be earlier than StartDate.'
}

$payload = @{
    symbol = $Symbol
    startDate = $StartDate.ToString('yyyy-MM-dd')
    endDate = $EndDate.ToString('yyyy-MM-dd')
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "$($ServerUrl.TrimEnd('/'))/api/research/announcements/captures" `
    -ContentType 'application/json' `
    -Body $payload
