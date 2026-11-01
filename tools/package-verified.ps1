$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verify = Join-Path $project 'build/verify'
$compiled = [DateTime]::Parse((Get-Content (Join-Path $verify 'compiled.stamp') -Raw)).ToUniversalTime()
$tested = [DateTime]::Parse((Get-Content (Join-Path $verify 'tested.stamp') -Raw)).ToUniversalTime()
if ($tested -lt $compiled) { throw 'Run verify-runtime.ps1 -Test before packaging' }
foreach ($source in Get-ChildItem (Join-Path $project 'src/main/java') -Recurse -File) {
    if ($source.LastWriteTimeUtc -gt $compiled) { throw ('Uncompiled source: '+$source.FullName) }
}
$version = ((Get-Content (Join-Path $project 'gradle.properties') | Where-Object { $_ -match '^mod_version=' }) -split '=',2)[1]
$target = Join-Path $project ('build/libs/newyear-countdown-'+$version+'.jar')
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($target,[IO.FileMode]::Create)
$zip = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
$utf8 = [Text.UTF8Encoding]::new($false)
try {
    foreach ($root in @((Join-Path $verify 'classes'),(Join-Path $project 'src/main/resources'))) {
        foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -File) {
            $name = $file.FullName.Substring($root.Length+1).Replace('\','/')
            $entry = $zip.CreateEntry($name,[IO.Compression.CompressionLevel]::Optimal)
            $output = $entry.Open()
            try {
                if ($name -eq 'fabric.mod.json') {
                    $json = (Get-Content -LiteralPath $file.FullName -Encoding UTF8 -Raw).Replace('${version}',$version)
                    $null = $json | ConvertFrom-Json
                    $bytes = $utf8.GetBytes($json)
                    $output.Write($bytes,0,$bytes.Length)
                } else {
                    $inputFile = [IO.File]::OpenRead($file.FullName)
                    try { $inputFile.CopyTo($output) } finally { $inputFile.Dispose() }
                }
            } finally { $output.Dispose() }
        }
    }
} finally { $zip.Dispose(); $stream.Dispose() }
Get-Item -LiteralPath $target | Select-Object FullName,Length
Get-FileHash -LiteralPath $target -Algorithm SHA256
