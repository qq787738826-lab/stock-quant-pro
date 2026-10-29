[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Drawing

$assetRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\assets'))
New-Item -ItemType Directory -Path $assetRoot -Force | Out-Null
$pngPath = Join-Path $assetRoot 'icon.png'
$icoPath = Join-Path $assetRoot 'icon.ico'

$bitmap = [Drawing.Bitmap]::new(256, 256,
    [Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint =
        [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear([Drawing.Color]::Transparent)

    $shape = [Drawing.Drawing2D.GraphicsPath]::new()
    try {
        $shape.AddArc(8, 8, 56, 56, 180, 90)
        $shape.AddArc(192, 8, 56, 56, 270, 90)
        $shape.AddArc(192, 192, 56, 56, 0, 90)
        $shape.AddArc(8, 192, 56, 56, 90, 90)
        $shape.CloseFigure()
        $background = [Drawing.Drawing2D.LinearGradientBrush]::new(
            [Drawing.Point]::new(18, 18),
            [Drawing.Point]::new(238, 238),
            [Drawing.Color]::FromArgb(255, 55, 220, 199),
            [Drawing.Color]::FromArgb(255, 72, 139, 250))
        try { $graphics.FillPath($background, $shape) }
        finally { $background.Dispose() }
    } finally {
        $shape.Dispose()
    }

    $font = [Drawing.Font]::new('Segoe UI', 85,
        [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
    $brush = [Drawing.SolidBrush]::new(
        [Drawing.Color]::FromArgb(255, 5, 23, 35))
    $format = [Drawing.StringFormat]::new()
    try {
        $format.Alignment = [Drawing.StringAlignment]::Center
        $format.LineAlignment = [Drawing.StringAlignment]::Center
        $graphics.DrawString('SQ', $font, $brush,
            [Drawing.RectangleF]::new(4, 3, 248, 245), $format)
    } finally {
        $format.Dispose()
        $brush.Dispose()
        $font.Dispose()
    }
    $bitmap.Save($pngPath, [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}

$pngBytes = [IO.File]::ReadAllBytes($pngPath)
$stream = [IO.File]::Open($icoPath, [IO.FileMode]::Create,
    [IO.FileAccess]::Write, [IO.FileShare]::None)
$writer = [IO.BinaryWriter]::new($stream)
try {
    $writer.Write([uint16]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]1)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$pngBytes.Length)
    $writer.Write([uint32]22)
    $writer.Write($pngBytes)
} finally {
    $writer.Dispose()
}

Write-Output 'DESKTOP_ICON_STATUS=GENERATED'
Write-Output 'DESKTOP_ICON_SIZE=256'
