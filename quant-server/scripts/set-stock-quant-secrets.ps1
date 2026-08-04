[CmdletBinding(DefaultParameterSetName = 'Configure')]
param(
    [Parameter(ParameterSetName = 'Status', Mandatory = $true)]
    [switch] $Status
)

$ErrorActionPreference = 'Stop'
$databaseTarget = 'StockQuant/ResearchDbPassword'
$tushareTarget = 'StockQuant/TushareToken'
$allowedTargets = @($databaseTarget, $tushareTarget)

if (-not ('StockQuant.CredentialManagerNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace StockQuant {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct NativeCredential {
        public UInt32 Flags;
        public UInt32 Type;
        [MarshalAs(UnmanagedType.LPWStr)] public string TargetName;
        [MarshalAs(UnmanagedType.LPWStr)] public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public UInt32 CredentialBlobSize;
        public IntPtr CredentialBlob;
        public UInt32 Persist;
        public UInt32 AttributeCount;
        public IntPtr Attributes;
        [MarshalAs(UnmanagedType.LPWStr)] public string TargetAlias;
        [MarshalAs(UnmanagedType.LPWStr)] public string UserName;
    }

    public static class CredentialManagerNative {
        [DllImport("Advapi32.dll", EntryPoint = "CredWriteW",
            CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool CredWrite(
            ref NativeCredential credential, UInt32 flags);

        [DllImport("Advapi32.dll", EntryPoint = "CredReadW",
            CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool CredRead(
            string target, UInt32 type, UInt32 flags,
            out IntPtr credential);

        [DllImport("Advapi32.dll", EntryPoint = "CredFree")]
        public static extern void CredFree(IntPtr credential);
    }
}
'@
}

function Assert-AllowedTarget([string] $Target) {
    if ($Target -notin $allowedTargets) {
        throw 'STOCK_QUANT_SECRET_TARGET_NOT_ALLOWED'
    }
}

function Test-CredentialExists([string] $Target) {
    Assert-AllowedTarget $Target
    [IntPtr] $credential = [IntPtr]::Zero
    $found = [StockQuant.CredentialManagerNative]::CredRead(
        $Target, 1, 0, [ref] $credential)
    if ($found) {
        try { return $true }
        finally {
            if ($credential -ne [IntPtr]::Zero) {
                [StockQuant.CredentialManagerNative]::CredFree($credential)
            }
        }
    }
    if ([Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 1168) {
        return $false
    }
    throw 'STOCK_QUANT_CREDENTIAL_STATUS_CHECK_FAILED'
}

function Write-SecureCredential(
    [string] $Target,
    [Security.SecureString] $Secret
) {
    Assert-AllowedTarget $Target
    if ($null -eq $Secret -or $Secret.Length -lt 8 -or
        $Secret.Length -gt 1280) {
        throw 'STOCK_QUANT_SECRET_VALUE_INVALID'
    }
    [IntPtr] $plaintext = [IntPtr]::Zero
    try {
        $plaintext = [Runtime.InteropServices.Marshal]::SecureStringToCoTaskMemUnicode(
            $Secret)
        $credential = [StockQuant.NativeCredential]::new()
        $credential.Type = 1
        $credential.TargetName = $Target
        $credential.CredentialBlobSize = [uint32]($Secret.Length * 2)
        $credential.CredentialBlob = $plaintext
        $credential.Persist = 2
        $credential.UserName = 'StockQuantLocalAutomation'
        if (-not [StockQuant.CredentialManagerNative]::CredWrite(
                [ref] $credential, 0)) {
            throw 'STOCK_QUANT_CREDENTIAL_WRITE_FAILED'
        }
    } finally {
        if ($plaintext -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeCoTaskMemUnicode(
                $plaintext)
        }
    }
}

$databasePresent = Test-CredentialExists $databaseTarget
$tusharePresent = Test-CredentialExists $tushareTarget
if ($Status) {
    Write-Output "$databaseTarget=$(if ($databasePresent) { 'PRESENT' } else { 'MISSING' })"
    Write-Output "$tushareTarget=$(if ($tusharePresent) { 'PRESENT' } else { 'MISSING' })"
    Write-Output "STOCK_QUANT_CREDENTIALS_READY=$($databasePresent -and $tusharePresent)"
    exit $(if ($databasePresent -and $tusharePresent) { 0 } else { 10 })
}

if ($Host.Name -ne 'ConsoleHost' -or
    [Console]::IsInputRedirected -or [Console]::IsOutputRedirected) {
    throw 'STOCK_QUANT_NATIVE_SECURE_CONSOLE_REQUIRED'
}
if ($databasePresent -or $tusharePresent) {
    $confirmation = Read-Host `
        'Existing Stock Quant credentials will be replaced. Type OVERWRITE to continue'
    if ($confirmation -cne 'OVERWRITE') {
        throw 'STOCK_QUANT_CREDENTIAL_OVERWRITE_NOT_CONFIRMED'
    }
}

$databaseSecret = $null
$tushareSecret = $null
try {
    $databaseSecret = Read-Host `
        'stock_quant_research database password' -AsSecureString
    $tushareSecret = Read-Host 'Tushare Token' -AsSecureString
    Write-SecureCredential $databaseTarget $databaseSecret
    Write-SecureCredential $tushareTarget $tushareSecret
    Write-Output 'STOCK_QUANT_CREDENTIALS_CONFIGURED=true'
    Write-Output 'STOCK_QUANT_CREDENTIAL_STORAGE=WINDOWS_CREDENTIAL_MANAGER_CURRENT_USER'
} finally {
    if ($null -ne $databaseSecret) { $databaseSecret.Dispose() }
    if ($null -ne $tushareSecret) { $tushareSecret.Dispose() }
}
