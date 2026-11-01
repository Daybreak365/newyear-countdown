param([switch]$PrepareOnly, [switch]$Test)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verify = Join-Path $project 'build/verify'
$cache = Join-Path $project '.gradle-user/caches/modules-2/files-2.1'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$utf8 = [Text.UTF8Encoding]::new($false)
$jars = [Collections.Generic.List[string]]::new()
$mi = Get-Content (Join-Path $project '.gradle-user/caches/fabric-loom/26.3/mojang_minecraft_info.json') -Raw | ConvertFrom-Json
foreach ($lib in $mi.libraries) {
    $parts = $lib.name.Split(':')
    if ($parts.Length -ne 3) { continue }
    $dir = Join-Path $cache ($parts[0]+'/'+$parts[1]+'/'+$parts[2])
    if (Test-Path -LiteralPath $dir) {
        $file = Get-ChildItem -LiteralPath $dir -Recurse -Filter ($parts[1]+'-'+$parts[2]+'.jar') | Select-Object -First 1
        if ($file) { $jars.Add($file.FullName) }
    }
}
$apiPom = Get-ChildItem (Join-Path $cache 'net.fabricmc.fabric-api/fabric-api/0.161.0+26.3') -Recurse -Filter '*.pom' | Select-Object -First 1
[xml]$api = Get-Content $apiPom.FullName -Raw
foreach ($dep in $api.project.dependencies.dependency) {
    $file = Get-ChildItem (Join-Path $cache ($dep.groupId+'/'+$dep.artifactId+'/'+$dep.version)) -Recurse -Filter ($dep.artifactId+'-'+$dep.version+'.jar') | Select-Object -First 1
    if ($file) { $jars.Add($file.FullName) }
}
foreach ($group in @('net.fabricmc','io.github.llamalad7','org.ow2.asm','com.terraformersmc','org.jetbrains','org.junit.jupiter','org.junit.platform','org.opentest4j','org.apiguardian')) {
    $dir = Join-Path $cache $group
    if (!(Test-Path -LiteralPath $dir)) { continue }
    foreach ($artifact in Get-ChildItem -LiteralPath $dir -Directory) {
        $versions = Get-ChildItem -LiteralPath $artifact.FullName -Directory | Sort-Object LastWriteTime -Descending
        $version = $versions | Where-Object { $_.Name -match '26.3' } | Select-Object -First 1
        if (!$version) { $version = $versions | Select-Object -First 1 }
        $file = Get-ChildItem -LiteralPath $version.FullName -Recurse -Filter ($artifact.Name+'-'+$version.Name+'.jar') | Select-Object -First 1
        if ($file) { $jars.Add($file.FullName) }
    }
}
$minecraft = Get-ChildItem (Join-Path $project '.gradle/loom-cache/minecraftMaven') -Recurse -Filter '*.jar' | Select-Object -First 1
$jars.Insert(0,$minecraft.FullName)
$classpath = [Collections.Generic.List[string]]::new()
foreach ($jar in $jars | Select-Object -Unique) {
    $name = [IO.Path]::GetFileNameWithoutExtension($jar)
    $dest = Join-Path $verify ('cp/'+$name)
    $done = Join-Path $dest '.extracted'
    if (!(Test-Path -LiteralPath $done)) {
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $zip = [IO.Compression.ZipFile]::OpenRead($jar)
        try {
            foreach ($entry in $zip.Entries) {
                if (!$entry.Name -or $entry.FullName.StartsWith('META-INF/versions/')) { continue }
                $target = [IO.Path]::GetFullPath((Join-Path $dest $entry.FullName))
                if (!$target.StartsWith($dest+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe ZIP entry' }
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
            }
        } finally { $zip.Dispose() }
        [IO.File]::WriteAllText($done,'ok',$utf8)
    }
    $classpath.Add($dest)
}
[IO.File]::WriteAllText((Join-Path $verify 'classpath.txt'),($classpath -join ';'),$utf8)
if ($PrepareOnly) { Write-Output ('Prepared '+$classpath.Count+' dependency directories'); exit 0 }
$out = Join-Path $verify 'classes'
if (Test-Path -LiteralPath $out) {
    $resolved = (Resolve-Path -LiteralPath $out).Path
    if (!$resolved.StartsWith($project+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $resolved -ne [IO.Path]::GetFullPath($out) -or ((Get-Item -LiteralPath $out).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe compiler output path' }
    Remove-Item -LiteralPath $out -Recurse -Force
}
[IO.Directory]::CreateDirectory($out) | Out-Null
$argsFile = Join-Path $verify 'javac.args'
$sources = Get-ChildItem (Join-Path $project 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
$compilerArgs = @('-encoding','UTF-8','-proc:none','--release','25','-Xmaxerrs','100','-classpath',('"'+($classpath -join ';').Replace('\','/')+'"'),'-d',('"'+$out.Replace('\','/')+'"')) + $sources
[IO.File]::WriteAllLines($argsFile,$compilerArgs,$utf8)
$exports = @('--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED','--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED','--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED')
& javac @exports -d $verify (Join-Path $project 'tools/DirectoryCompiler.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java @exports -cp $verify DirectoryCompiler ('@'+$argsFile)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output 'All main sources compiled successfully.'
[IO.File]::WriteAllText((Join-Path $verify 'compiled.stamp'),[DateTime]::UtcNow.ToString('O'),$utf8)
if ($Test) {
    $testOut = Join-Path $verify 'test-classes'
    [IO.Directory]::CreateDirectory($testOut) | Out-Null
    $testSources = Get-ChildItem (Join-Path $project 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testSources += Get-ChildItem (Join-Path $project 'docs/examples') -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testCp = $out+';'+($classpath -join ';')
    $testArgs = @('-encoding','UTF-8','-proc:none','--release','25','-classpath',('"'+$testCp.Replace('\','/')+'"'),'-d',('"'+$testOut.Replace('\','/')+'"')) + $testSources + @(('"'+(Join-Path $project 'tools/TestRunner.java').Replace('\','/')+'"'))
    $testFile = Join-Path $verify 'test.args'
    [IO.File]::WriteAllLines($testFile,$testArgs,$utf8)
    & java @exports -cp $verify DirectoryCompiler ('@'+$testFile)
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $runFile = Join-Path $verify 'test-run.args'
    [IO.File]::WriteAllLines($runFile,@('-cp',('"'+($testOut+';'+$testCp).Replace('\','/')+'"'),'TestRunner'),$utf8)
    & java ('@'+$runFile)
    if ($LASTEXITCODE -eq 0) { [IO.File]::WriteAllText((Join-Path $verify 'tested.stamp'),[DateTime]::UtcNow.ToString('O'),$utf8) }
    exit $LASTEXITCODE
}
