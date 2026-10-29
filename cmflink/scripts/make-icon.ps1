# Renders the app icon (a dark tile with two earbuds inside a battery ring) to
# src/CmfLink.App/Assets/CmfLink.ico, at 16, 20, 24, 32, 48, 64 and 256 px, PNG-compressed.
#   powershell -ExecutionPolicy Bypass -File scripts/make-icon.ps1
Add-Type -AssemblyName PresentationCore, WindowsBase

$out = Join-Path $PSScriptRoot "..\src\CmfLink.App\Assets\CmfLink.ico"
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null

function Render([int]$size) {
    $v = New-Object System.Windows.Media.DrawingVisual
    $dc = $v.RenderOpen()
    $s = $size / 64.0
    $dc.PushTransform((New-Object System.Windows.Media.ScaleTransform($s, $s)))

    $tile = New-Object System.Windows.Media.LinearGradientBrush(
        [System.Windows.Media.Color]::FromRgb(0x2B, 0x2D, 0x31), [System.Windows.Media.Color]::FromRgb(0x12, 0x13, 0x15), 90.0)
    $dc.DrawRoundedRectangle($tile, $null, (New-Object System.Windows.Rect(2, 2, 60, 60)), 14, 14)

    $center = New-Object System.Windows.Point(32, 32)
    $track = New-Object System.Windows.Media.Pen((New-Object System.Windows.Media.SolidColorBrush([System.Windows.Media.Color]::FromArgb(0x40, 255, 255, 255))), 4)
    $dc.DrawEllipse($null, $track, $center, 21, 21)
    # 75 % arc, clockwise from 12 o'clock.
    $fig = New-Object System.Windows.Media.PathFigure
    $fig.StartPoint = New-Object System.Windows.Point(32, 11)
    $arc = New-Object System.Windows.Media.ArcSegment((New-Object System.Windows.Point(11, 32)), (New-Object System.Windows.Size(21, 21)), 0, $true, [System.Windows.Media.SweepDirection]::Clockwise, $true)
    $fig.Segments.Add($arc)
    $geo = New-Object System.Windows.Media.PathGeometry
    $geo.Figures.Add($fig)
    $green = New-Object System.Windows.Media.Pen((New-Object System.Windows.Media.SolidColorBrush([System.Windows.Media.Color]::FromRgb(0x2E, 0xB8, 0x5C))), 4)
    $green.StartLineCap = 'Round'; $green.EndLineCap = 'Round'
    $dc.DrawGeometry($null, $green, $geo)

    $white = [System.Windows.Media.Brushes]::White
    foreach ($x in 25.2, 38.8) {
        $dc.DrawEllipse($white, $null, (New-Object System.Windows.Point($x, 27.5)), 5.2, 5.2)
        $dc.DrawRoundedRectangle($white, $null, (New-Object System.Windows.Rect(($x - 2.1), 29, 4.2, 13)), 2.1, 2.1)
    }
    $dc.Pop()
    $dc.Close()

    $bmp = New-Object System.Windows.Media.Imaging.RenderTargetBitmap($size, $size, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bmp.Render($v)
    $enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
    $enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($bmp))
    $ms = New-Object System.IO.MemoryStream
    $enc.Save($ms)
    return ,$ms.ToArray()
}

$sizes = 16, 20, 24, 32, 48, 64, 256
$images = $sizes | ForEach-Object { ,(Render $_) }

$fs = [System.IO.File]::Create($out)
$w = New-Object System.IO.BinaryWriter($fs)
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dim = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$images[$i].Length); $w.Write([uint32]$offset)
    $offset += $images[$i].Length
}
foreach ($img in $images) { $w.Write($img) }
$w.Close()
Write-Output "Wrote $out"
