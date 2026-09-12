
param(
    [switch]$WithLogs
)

if ($args -contains '--with-logs') { $WithLogs = $true }

function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = 'White'
    )

    switch ($Color) {
        'Red' { Write-Host $Message -ForegroundColor Red }
        'Green' { Write-Host $Message -ForegroundColor Green }
        'Yellow' { Write-Host $Message -ForegroundColor Yellow }
        'Blue' { Write-Host $Message -ForegroundColor Blue }
        default { Write-Host $Message }
    }
}

function Test-OperatingSystem {
    Write-ColorOutput 'Running on Windows' 'Blue'
}


function Self-CheckScriptIntegrity {
    param([string]$ScriptPath)

    Write-ColorOutput '=== Self-check: Script integrity ===' 'Blue'

    if ([string]::IsNullOrWhiteSpace($ScriptPath) -or -not (Test-Path $ScriptPath)) {
        Write-ColorOutput 'Cannot locate script path; skipping self-check' 'Yellow'
        return $true
    }

    try {
        $bytes = [IO.File]::ReadAllBytes($ScriptPath)
    }
    catch {
        Write-ColorOutput "Failed to read script; skipping self-check: $_" 'Yellow'
        return $true
    }

    $hasUtf16Le = $bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE
    $hasUtf16Be = $bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF
    if ($hasUtf16Le -or $hasUtf16Be) {
        Write-ColorOutput 'UTF-16 detected; please save as UTF-8 without BOM' 'Yellow'
    }

    $text = [Text.Encoding]::UTF8.GetString($bytes)
    if ($text -match '[\u201C\u201D\u2018\u2019]') {
        Write-ColorOutput 'Smart quotes detected (curly quotes); PowerShell may fail to parse' 'Yellow'
    }
    if ($text -match '\x00') {
        Write-ColorOutput 'NUL character detected; possible encoding or copy/paste issue' 'Yellow'
    }

    $errors = $null
    [System.Management.Automation.PSParser]::Tokenize($text, [ref]$errors) | Out-Null
    if ($errors -and $errors.Count -gt 0) {
        Write-ColorOutput 'Self-check found PowerShell parse errors' 'Red'
        foreach ($e in $errors) {
            Write-ColorOutput "$($e.Message) at line $($e.Token.StartLine), column $($e.Token.StartColumn)" 'Red'
        }
        return $false
    }

    Write-ColorOutput 'Self-check passed' 'Green'
    return $true
}


function Test-HasAndroidNativeCode {
    param([string]$ProjectRoot)

    $hasNative = $false

    $androidDirs = @()
    $androidDirs += Join-Path $ProjectRoot 'android'
    $androidDirs += Join-Path $ProjectRoot 'app\android'

    foreach ($dir in $androidDirs) {
        if (Test-Path $dir) {
            $javaFiles = Get-ChildItem -Path $dir -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue
            $ktFiles = Get-ChildItem -Path $dir -Recurse -Filter '*.kt' -File -ErrorAction SilentlyContinue
            if (($javaFiles -and $javaFiles.Count -gt 0) -or ($ktFiles -and $ktFiles.Count -gt 0)) { $hasNative = $true }
        }
        if ($hasNative) { break }
    }

    if (-not $hasNative) {
        $nodeModulesDir = Join-Path $ProjectRoot 'node_modules'
        if (Test-Path $nodeModulesDir) {
            $candidateModules = Get-ChildItem -Path $nodeModulesDir -Directory
            foreach ($moduleDir in $candidateModules) {
            if ($moduleDir.Name -eq 'sn-plugin-lib') { continue }
                $dirsToScan = @()
                $dirsToScan += (Join-Path $moduleDir.FullName 'android')
                $dirsToScan += (Join-Path $moduleDir.FullName 'platforms\android')
                $dirsToScan += (Join-Path $moduleDir.FullName 'platforms\android-native')
                foreach ($scanDir in $dirsToScan) {
                    if (Test-Path $scanDir) {
                        $javaFiles = Get-ChildItem -Path $scanDir -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue
                        $ktFiles = Get-ChildItem -Path $scanDir -Recurse -Filter '*.kt' -File -ErrorAction SilentlyContinue
                        if (($javaFiles -and $javaFiles.Count -gt 0) -or ($ktFiles -and $ktFiles.Count -gt 0)) { $hasNative = $true; break }
                    }
                }
                if ($hasNative) { break }
            }
        }
    }

    if (-not $hasNative) {
        $javacDir = Join-Path $ProjectRoot 'android\app\build\intermediates\javac'
        if (Test-Path $javacDir) {
            $classesCandidates = Get-ChildItem -Path $javacDir -Directory -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match 'compile.*JavaWithJavac\\classes$' }
            foreach ($c in $classesCandidates) {
                $classFiles = Get-ChildItem -Path $c.FullName -Recurse -Filter '*.class' -File -ErrorAction SilentlyContinue
                if ($classFiles -and $classFiles.Count -gt 0) { $hasNative = $true; break }
            }
        }
    }

    return $hasNative
}

function New-RandomString {
    param([int]$Length = 16)

    $chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
    $randomString = ''

    for ($i = 0; $i -lt $Length; $i++) {
        $randomIndex = Get-Random -Maximum $chars.Length
        $randomString += $chars[$randomIndex]
    }

    return $randomString
}

function Get-PackageInfo {
    param([string]$ProjectRoot)

    $packageJsonPath = Join-Path $ProjectRoot 'package.json'

    if (Test-Path $packageJsonPath) {
        try {
            $packageJson = Get-Content $packageJsonPath -Raw | ConvertFrom-Json

            $name = $packageJson.name
            $description = if ($packageJson.description) { $packageJson.description } else { '' }
            $version = if ($packageJson.version) { $packageJson.version } else { '0.0.1' }

            return @{
                Name = $name
                Description = $description
                Version = $version
            }
        }
        catch {
            Write-ColorOutput "Failed to parse package.json file: $_" 'Red'
            exit 1
        }
    }
    else {
        Write-ColorOutput 'package.json file not found' 'Red'
        exit 1
    }
}

function New-PluginConfig {
    param(
        [string]$PluginId,
        [hashtable]$PackageInfo,
        [string]$ProjectRoot
    )

    $configFile = Join-Path $ProjectRoot 'PluginConfig.json'

    Write-ColorOutput 'Creating PluginConfig.json file...' 'Blue'

    $config = @{
        name = $PackageInfo.Name
        desc = $PackageInfo.Description
        iconPath = ''
        versionName = $PackageInfo.Version
        versionCode = '1'
        pluginID = $PluginId
        pluginKey = $PackageInfo.Name
        jsMainPath = 'index'
    }

    try {
        $config | ConvertTo-Json -Depth 10 | Set-Content $configFile -Encoding UTF8
        Write-ColorOutput "PluginConfig.json file created: $configFile" 'Green'
    }
    catch {
        Write-ColorOutput "Failed to create PluginConfig.json file: $_" 'Red'
        exit 1
    }
}

function Increment-PluginVersion {
    param([string]$ConfigFile)

    try {
        $config = Get-Content $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $oldCode = 0L
        if ($null -ne $config.versionCode) {
            [void][long]::TryParse([string]$config.versionCode, [ref]$oldCode)
        }
        $newCode = [string]($oldCode + 1)
        if ($null -eq $config.PSObject.Properties['versionCode']) {
            $config | Add-Member -NotePropertyName versionCode -NotePropertyValue $newCode
        } else {
            $config.versionCode = $newCode
        }

        $oldName = [string]$config.versionName
        if ([string]::IsNullOrWhiteSpace($oldName)) { $oldName = '0.0.0' }
        if ($oldName -match '^(.*?)(\d+)$') {
            $newName = "$($Matches[1])$(([long]$Matches[2]) + 1)"
        } else {
            $newName = "$oldName.1"
        }
        if ($null -eq $config.PSObject.Properties['versionName']) {
            $config | Add-Member -NotePropertyName versionName -NotePropertyValue $newName
        } else {
            $config.versionName = $newName
        }

        $config | ConvertTo-Json -Depth 10 | Set-Content $ConfigFile -Encoding UTF8
        Write-ColorOutput "Plugin version advanced: $oldName → $newName, code $oldCode → $newCode" 'Green'
    }
    catch {
        Write-ColorOutput "Failed to advance plugin version: $_" 'Red'
        exit 1
    }
}

function Update-PluginConfigPackages {
    param(
        [string]$ProjectRoot,
        [array]$FoundPackages,
        [string]$BuildGeneratedDir
    )

    $configFile = Join-Path $BuildGeneratedDir 'PluginConfig.json'

    if ($FoundPackages.Count -eq 0) {
        Write-ColorOutput 'No ReactPackage implementations found, skipping PluginConfig.json update' 'Yellow'
        return
    }

    Write-ColorOutput 'Updating reactPackages field in build/generated folder''s PluginConfig.json...' 'Blue'

    try {
        if (-not (Test-Path $configFile)) {
            $rootConfigFile = Join-Path $ProjectRoot 'PluginConfig.json'
            if (Test-Path $rootConfigFile) {
                Copy-Item $rootConfigFile $configFile -Force
                Write-ColorOutput 'Copied PluginConfig.json from project root to build/generated folder' 'Blue'
            }
            else {
                Write-ColorOutput 'PluginConfig.json file not found in both project root and build/generated folder' 'Red'
                return
            }
        }

        $config = Get-Content $configFile -Raw -Encoding UTF8 | ConvertFrom-Json

        $configHash = @{}
        $config.PSObject.Properties | ForEach-Object { $configHash[$_.Name] = $_.Value }

        $allPackages = @('me.laumss.notipal.InklingPackages') + @($FoundPackages)
        $seenPackages = @{}
        $uniquePackages = @(
            foreach ($package in $allPackages) {
                $packageName = [string]$package
                if ([string]::IsNullOrWhiteSpace($packageName) -or
                    $seenPackages.ContainsKey($packageName)) {
                    continue
                }
                $seenPackages[$packageName] = $true
                $packageName
            }
        )
        $configHash.reactPackages = $uniquePackages

        $configHash | ConvertTo-Json -Depth 10 | Set-Content $configFile -Encoding UTF8
        Write-ColorOutput 'PluginConfig.json in build/generated folder updated with reactPackages field' 'Green'
    }
    catch {
        Write-ColorOutput "Failed to update PluginConfig.json in build/generated folder: $_" 'Red'
    }
}

function Find-PackagesInDirectory {
    param(
        [string]$SearchDir,
        [string]$ResultFile,
        [ref]$FoundPackages
    )

    if (-not (Test-Path $SearchDir)) {
        return
    }

    $javaFiles = Get-ChildItem -Path $SearchDir -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue
    $ktFiles = Get-ChildItem -Path $SearchDir -Recurse -Filter '*.kt' -File -ErrorAction SilentlyContinue
    $sourceFiles = @()
    if ($javaFiles) { $sourceFiles += $javaFiles }
    if ($ktFiles) { $sourceFiles += $ktFiles }

    foreach ($file in $sourceFiles) {
        try {
            $content = Get-Content $file.FullName -Raw -ErrorAction SilentlyContinue
            $isKotlin = ([System.IO.Path]::GetExtension($file.FullName)).ToLower() -eq '.kt'

            $matchesClass = $false
            $className = $null
            $packageName = $null

            if ($isKotlin) {
                if ($content -match 'class\s+([A-Za-z0-9_]+)\s*:\s*[^\{\n]*\b(ReactPackage|TurboReactPackage|BaseReactPackage|ViewManagerOnDemandReactPackage)\b') {
                    $matchesClass = $true
                    $className = $matches[1].Trim()
                }
                if ($content -match 'package\s+([^\s;]+)') {
                    $packageName = $matches[1].Trim()
                }
            } else {
                if ($content -match '(implements\s+(ReactPackage|ViewManagerOnDemandReactPackage)|extends\s+(ReactPackage|TurboReactPackage|BaseReactPackage))') {
                    $matchesClass = $true
                }
                if ($content -match 'class\s+([A-Za-z0-9_]+)') {
                    $className = $matches[1].Trim()
                }
                if ($content -match 'package\s+([^;]+);') {
                    $packageName = $matches[1].Trim()
                }
            }

            if ($matchesClass -and $packageName -and $className) {
                $fullClassName = "$packageName.$className"
                Write-ColorOutput "  - Found ReactPackage implementation: $fullClassName" 'Green'
                Add-Content $ResultFile "  - $fullClassName"
                $FoundPackages.Value += $fullClassName
                Write-Host "Added to file: $fullClassName"
            }
        }
        catch {
            continue
        }
    }
}


function Is-IgnoredModuleName {
    param([string]$moduleName)

    if (-not $moduleName) { return $false }
    $lower = $moduleName.ToLower()

    if ($lower -eq 'react-native') { return $true }
    if ($lower -eq 'react') { return $true }
    if ($lower -eq 'sn-plugin-lib') { return $true }
    if ($lower -like '@react-native*') { return $true }
    if ($lower -like '@react-navigation*') { return $true }
    return $false
}


function Find-ProjectReactPackages {
    param([string]$ProjectRoot)

    $resultFile = Join-Path $ProjectRoot 'android_project_react_packages.txt'
    'ReactPackage implementations in project:' | Set-Content $resultFile -Encoding UTF8
    $foundPackages = @()

    $androidDir = Join-Path $ProjectRoot 'android'
    if (Test-Path $androidDir) {
        Find-PackagesInDirectory -SearchDir $androidDir -ResultFile $resultFile -FoundPackages ([ref]$foundPackages)
    }
    $appAndroidDir = Join-Path $ProjectRoot 'app\android'
    if (Test-Path $appAndroidDir) {
        Find-PackagesInDirectory -SearchDir $appAndroidDir -ResultFile $resultFile -FoundPackages ([ref]$foundPackages)
    }

    $foundPackages = $foundPackages | Sort-Object -Unique
    Write-ColorOutput "Detected ReactPackage/TurboReactPackage classes in project: $($foundPackages.Count)" 'Blue'
    foreach ($pkg in $foundPackages) { Write-ColorOutput "  - $pkg" 'Green' }
    return $foundPackages
}


function Scan-NodeModulesNativeCode {
    param([string]$ProjectRoot)

    $nodeModulesDir = Join-Path $ProjectRoot 'node_modules'
    $modsWithNative = @()
    if (-not (Test-Path $nodeModulesDir)) { return $modsWithNative }

    $topDirs = Get-ChildItem -Path $nodeModulesDir -Directory -ErrorAction SilentlyContinue

    foreach ($dir in $topDirs) {
        if ($dir.Name -like '@*') {
            $scoped = Get-ChildItem -Path $dir.FullName -Directory -ErrorAction SilentlyContinue
            foreach ($sub in $scoped) {
                $moduleName = "$($dir.Name)/$($sub.Name)"
                if (Is-IgnoredModuleName -moduleName $moduleName) { continue }
                $moduleRoot = $sub.FullName
                $dirsToScan = @()
                $dirsToScan += (Join-Path $moduleRoot 'android')
                $dirsToScan += (Join-Path $moduleRoot 'platforms\android')
                $dirsToScan += (Join-Path $moduleRoot 'platforms\android-native')
                $hasNative = $false
                foreach ($scanDir in $dirsToScan) {
                    if (Test-Path $scanDir) {
                        $javaFiles = Get-ChildItem -Path $scanDir -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue
                        $ktFiles = Get-ChildItem -Path $scanDir -Recurse -Filter '*.kt' -File -ErrorAction SilentlyContinue
                        if (($javaFiles -and $javaFiles.Count -gt 0) -or ($ktFiles -and $ktFiles.Count -gt 0)) { $hasNative = $true; break }
                    }
                }
                if ($hasNative) {
                    $modsWithNative += $moduleName
                    Write-ColorOutput "Third-party module contains Android sources: $moduleName" 'Yellow'
                }
            }
        } else {
            $moduleName = $dir.Name
            if (Is-IgnoredModuleName -moduleName $moduleName) { continue }
            $moduleRoot = $dir.FullName
            $dirsToScan = @()
            $dirsToScan += (Join-Path $moduleRoot 'android')
            $dirsToScan += (Join-Path $moduleRoot 'platforms\android')
            $dirsToScan += (Join-Path $moduleRoot 'platforms\android-native')
            $hasNative = $false
            foreach ($scanDir in $dirsToScan) {
                if (Test-Path $scanDir) {
                    $javaFiles = Get-ChildItem -Path $scanDir -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue
                    $ktFiles = Get-ChildItem -Path $scanDir -Recurse -Filter '*.kt' -File -ErrorAction SilentlyContinue
                    if (($javaFiles -and $javaFiles.Count -gt 0) -or ($ktFiles -and $ktFiles.Count -gt 0)) { $hasNative = $true; break }
                }
            }
            if ($hasNative) {
                $modsWithNative += $moduleName
                Write-ColorOutput "Third-party module contains Android sources: $moduleName" 'Yellow'
            }
        }
    }

    $modsWithNative = $modsWithNative | Sort-Object -Unique
    Write-ColorOutput "Third-party dependencies with Android sources: $($modsWithNative.Count)" 'Blue'
    return $modsWithNative
}


function Find-ManualReactPackagesFromApplication {
    param([string]$ProjectRoot)

    $dirsToScan = @()
    $dirsToScan += (Join-Path $ProjectRoot 'android\app\src\main\java')
    $dirsToScan += (Join-Path $ProjectRoot 'android\src\main\java')
    $dirsToScan += (Join-Path $ProjectRoot 'app\android\src\main\java')

    $found = @()

    foreach ($dir in $dirsToScan) {
        if (-not (Test-Path $dir)) { continue }
        $files = @()
        $files += (Get-ChildItem -Path $dir -Recurse -Filter '*.kt' -File -ErrorAction SilentlyContinue)
        $files += (Get-ChildItem -Path $dir -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue)

        foreach ($f in $files) {
            try {
                $text = Get-Content $f.FullName -Raw -ErrorAction SilentlyContinue
                $text = ($text -replace '(?m)^\s*//.*$', '')
                $text = ($text -replace '(?s)/\*.*?\*/', '')
                $packageName = $null
                if ($text -match '(?m)^\s*package\s+([^\s;]+)') {
                    $packageName = $matches[1].Trim()
                }

                $imports = @{}
                foreach ($imp in ($text -split "`r?`n")) {
                    if ($imp -match '^\s*import\s+([^\s;]+)') {
                        $fq = $matches[1].Trim()
                        $short = $fq.Split('.')[-1]
                        $imports[$short] = $fq
                    }
                }

                $resolveFqcn = {
                    param([string]$name)
                    if ($name -like '*.*') { return $name }
                    if ($imports.ContainsKey($name)) { return $imports[$name] }
                    if ($packageName) { return "$packageName.$name" }
                    return $name
                }

                $varToClass = @{}

                $matchesKotlinAssign = [System.Text.RegularExpressions.Regex]::Matches($text, '(?m)\b(?:val|var)\s+([A-Za-z0-9_]+)\s*=\s*([A-Za-z0-9_\.]+)\s*\(')
                foreach ($m in $matchesKotlinAssign) {
                    $varName = $m.Groups[1].Value
                    $className = $m.Groups[2].Value
                    if (-not [string]::IsNullOrWhiteSpace($varName) -and -not [string]::IsNullOrWhiteSpace($className)) {
                        $varToClass[$varName] = (& $resolveFqcn $className)
                    }
                }

                $matchesJavaTypedAssign = [System.Text.RegularExpressions.Regex]::Matches($text, '(?m)\b([A-Za-z0-9_\.]+)\s+([A-Za-z0-9_]+)\s*=\s*new\s+([A-Za-z0-9_\.]+)\s*\(')
                foreach ($m in $matchesJavaTypedAssign) {
                    $varName = $m.Groups[2].Value
                    $className = $m.Groups[3].Value
                    if (-not [string]::IsNullOrWhiteSpace($varName) -and -not [string]::IsNullOrWhiteSpace($className)) {
                        $varToClass[$varName] = (& $resolveFqcn $className)
                    }
                }

                $matchesJavaAssign = [System.Text.RegularExpressions.Regex]::Matches($text, '(?m)\b([A-Za-z0-9_]+)\s*=\s*new\s+([A-Za-z0-9_\.]+)\s*\(')
                foreach ($m in $matchesJavaAssign) {
                    $varName = $m.Groups[1].Value
                    $className = $m.Groups[2].Value
                    if (-not [string]::IsNullOrWhiteSpace($varName) -and -not [string]::IsNullOrWhiteSpace($className)) {
                        $varToClass[$varName] = (& $resolveFqcn $className)
                    }
                }

                $matchesKotlin = [System.Text.RegularExpressions.Regex]::Matches($text, '\badd\(\s*([A-Za-z0-9_\.]+)\s*\(')
                foreach ($m in $matchesKotlin) {
                    $name = $m.Groups[1].Value
                    $fqcn = (& $resolveFqcn $name)
                    if ($fqcn -match 'Package$') { $found += $fqcn }
                }

                $matchesKotlinVar = [System.Text.RegularExpressions.Regex]::Matches($text, '\badd\(\s*([A-Za-z0-9_]+)\s*\)')
                foreach ($m in $matchesKotlinVar) {
                    $varName = $m.Groups[1].Value
                    if ($varToClass.ContainsKey($varName)) {
                        $fqcn = $varToClass[$varName]
                        if ($fqcn -match 'Package$') { $found += $fqcn }
                    }
                }

                $matchesJava = [System.Text.RegularExpressions.Regex]::Matches($text, '\b(?:packages\.)?add\(\s*new\s+([A-Za-z0-9_\.]+)\s*\(')
                foreach ($m in $matchesJava) {
                    $name = $m.Groups[1].Value
                    $fqcn = (& $resolveFqcn $name)
                    if ($fqcn -match 'Package$') { $found += $fqcn }
                }

                $matchesJavaVar = [System.Text.RegularExpressions.Regex]::Matches($text, '\b(?:packages\.)?add\(\s*(?!new\b)([A-Za-z0-9_]+)\s*\)')
                foreach ($m in $matchesJavaVar) {
                    $varName = $m.Groups[1].Value
                    if ($varToClass.ContainsKey($varName)) {
                        $fqcn = $varToClass[$varName]
                        if ($fqcn -match 'Package$') { $found += $fqcn }
                    }
                }
            } catch { continue }
        }
    }

    $found = $found | Sort-Object -Unique
    Write-ColorOutput "Manually added packages in Application: $($found.Count)" 'Blue'
    foreach ($pkg in $found) { Write-ColorOutput "  - $pkg" 'Green' }
    return $found
}


function Get-ReactPackagesFromAutolinkingSource {
    param([string]$ProjectRoot, [string[]]$Exclude)

    $srcFile = Join-Path $ProjectRoot 'android\app\build\generated\autolinking\src\main\java\com\facebook\react\PackageList.java'
    if (-not (Test-Path $srcFile)) {
        Write-ColorOutput "Autolinking PackageList.java not found: $srcFile" 'Yellow'
        return @()
    }

    try {
        $text = Get-Content $srcFile -Raw
        $imports = @{}
        foreach ($line in ($text -split "`r?`n")) {
            if ($line -match '^\s*import\s+([^\s;]+)') {
                $fq = $matches[1].Trim()
                $short = $fq.Split('.')[-1]
                $imports[$short] = $fq
            }
        }
        $matchesNew = [System.Text.RegularExpressions.Regex]::Matches($text, 'new\s+([A-Za-z0-9_\.]+)\s*\(')
        $pkgs = @()
        foreach ($m in $matchesNew) {
            $name = $m.Groups[1].Value
            $fqcn = if ($name -like '*.*') { $name } elseif ($imports.ContainsKey($name)) { $imports[$name] } else { $name }
            if ($fqcn -match 'Package$') { $pkgs += $fqcn }
        }
        $pkgs = $pkgs | Sort-Object -Unique
        Write-ColorOutput "Packages extracted from autolinking source: $($pkgs.Count)" 'Blue'
        foreach ($p in $pkgs) { Write-ColorOutput "  - $p" 'Yellow' }
        if ($Exclude -and $Exclude.Count -gt 0) {
            $pkgs = $pkgs | Where-Object { $Exclude -notcontains $_ }
        }
        Write-ColorOutput "Filtered package count: $($pkgs.Count)" 'Blue'
        foreach ($p in $pkgs) { Write-ColorOutput "  - kept: $p" 'Green' }
        return $pkgs
    } catch {
        Write-ColorOutput "Failed to parse Autolinking PackageList.java: $_" 'Red'
        return @()
    }
}

function Find-ReactPackages {
    param([string]$ProjectRoot)

    Write-ColorOutput 'Starting to find and process dependencies with Android native code...' 'Green'

    $resultFile = Join-Path $ProjectRoot 'android_native_deps.txt'
    'List of dependencies with Android native code:' | Set-Content $resultFile -Encoding UTF8

    $foundPackages = @()

    $androidDir = Join-Path $ProjectRoot 'android'
    if (Test-Path $androidDir) {
        Write-ColorOutput 'Finding ReactPackage implementations in current project...' 'Blue'
        '' | Add-Content $resultFile
        'ReactPackage implementations in current project:' | Add-Content $resultFile

        Find-PackagesInDirectory -SearchDir $androidDir -ResultFile $resultFile -FoundPackages ([ref]$foundPackages)

        $appAndroidDir = Join-Path $ProjectRoot 'app\android'
        if (Test-Path $appAndroidDir) {
            Find-PackagesInDirectory -SearchDir $appAndroidDir -ResultFile $resultFile -FoundPackages ([ref]$foundPackages)
        }
    }

    $nodeModulesDir = Join-Path $ProjectRoot 'node_modules'
    if (Test-Path $nodeModulesDir) {
        Write-ColorOutput 'Finding ReactPackage implementations in node_modules...' 'Blue'
        '' | Add-Content $resultFile
        'ReactPackage implementations in node_modules:' | Add-Content $resultFile

        $candidateModules = Get-ChildItem -Path $nodeModulesDir -Directory
        foreach ($moduleDir in $candidateModules) {
            $moduleName = $moduleDir.Name
            if ($moduleName -eq 'sn-plugin-lib') { continue }

            $depName = "node_modules/$moduleName"
            $dirsToScan = @()

            $moduleAndroidDir = Join-Path $moduleDir.FullName 'android'
            $platformsAndroidDir = Join-Path $moduleDir.FullName 'platforms\android'
            $platformsAndroidNativeDir = Join-Path $moduleDir.FullName 'platforms\android-native'

            $moduleAndroidGradle = Join-Path $moduleAndroidDir 'build.gradle'
            $platformsAndroidGradle = Join-Path $platformsAndroidDir 'build.gradle'
            $platformsAndroidNativeGradle = Join-Path $platformsAndroidNativeDir 'build.gradle'

            if (Test-Path $moduleAndroidGradle) { $dirsToScan += $moduleAndroidDir }
            if (Test-Path $platformsAndroidGradle) { $dirsToScan += $platformsAndroidDir }
            if (Test-Path $platformsAndroidNativeGradle) { $dirsToScan += $platformsAndroidNativeDir }

            if ($dirsToScan.Count -gt 0) {
                Write-ColorOutput "Processing dependency: $depName" 'Yellow'
                '' | Add-Content $resultFile
                "$depName`:" | Add-Content $resultFile

                foreach ($scanDir in $dirsToScan) {
                    Find-PackagesInDirectory -SearchDir $scanDir -ResultFile $resultFile -FoundPackages ([ref]$foundPackages)
                }
            }
        }
    }

    Write-ColorOutput 'All dependencies processed!' 'Blue'
    Write-ColorOutput "Results saved to: $resultFile" 'Blue'
    Write-ColorOutput 'Final results:' 'Yellow'
    Get-Content $resultFile | Write-Host

    return $foundPackages
}

function Test-PluginConfigPackages {
    param([string]$ConfigFile)

    if (-not (Test-Path -LiteralPath $ConfigFile -PathType Leaf)) {
        Write-ColorOutput "PluginConfig.json not found: $ConfigFile" 'Red'
        return $false
    }
    try {
        $config = Get-Content -LiteralPath $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $packages = @($config.reactPackages | ForEach-Object { [string]$_ } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $duplicates = @($packages | Group-Object | Where-Object { $_.Count -gt 1 })
        if ($duplicates.Count -gt 0) {
            $names = ($duplicates | ForEach-Object { $_.Name }) -join ', '
            Write-ColorOutput "Duplicate reactPackages entries: $names" 'Red'
            return $false
        }
        if ($packages -notcontains 'me.laumss.notipal.InklingPackages') {
            Write-ColorOutput 'PluginConfig.json is missing me.laumss.notipal.InklingPackages' 'Red'
            return $false
        }
        Write-ColorOutput "reactPackages validated: $($packages.Count) unique entries" 'Green'
        return $true
    }
    catch {
        Write-ColorOutput "Unable to validate reactPackages: $_" 'Red'
        return $false
    }
}

function Remove-NativeCodePackageReference {
    param([string]$ConfigFile)

    if (-not (Test-Path -LiteralPath $ConfigFile -PathType Leaf)) { return $true }
    try {
        $config = Get-Content -LiteralPath $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $property = $config.PSObject.Properties['nativeCodePackage']
        if ($null -eq $property) { return $true }
        $config.PSObject.Properties.Remove('nativeCodePackage')
        $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ConfigFile -Encoding UTF8
        Write-ColorOutput "Removed nativeCodePackage reference from $ConfigFile" 'Yellow'
        return $true
    }
    catch {
        Write-ColorOutput "Unable to sanitize nativeCodePackage in $ConfigFile — $_" 'Red'
        return $false
    }
}

function Build-AndroidApk {
    param(
        [string]$ProjectRoot,
        [string]$BuildGeneratedConfigFile,
        [bool]$RequireReactPackagesCheck = $false
    )

    if ($RequireReactPackagesCheck) {
        try {
            $config = Get-Content $BuildGeneratedConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
            if (-not $config.reactPackages) {
                Write-ColorOutput 'No reactPackages field in build/generated folder''s PluginConfig.json, skipping APK build' 'Yellow'
                return $false
            }
        }
        catch {
            Write-ColorOutput 'No reactPackages field in build/generated folder''s PluginConfig.json, skipping APK build' 'Yellow'
            return $false
        }
    }

    Write-ColorOutput 'Starting gradle build script to generate APK...' 'Blue'

    $androidDir = Join-Path $ProjectRoot 'android'
    if (-not (Test-Path $androidDir)) {
        Write-ColorOutput 'Cannot find android directory' 'Red'
        return $false
    }

    $currentDir = Get-Location
    try {
        Set-Location $androidDir

        $apkTask = 'buildCustomApkDebug'
        $debugProp = if ($script:WithLogs) { '-PinklingEnableDebug=true' } else { '-PinklingEnableDebug=false' }

        $gradlewPath = Join-Path $androidDir 'gradlew.bat'
        if (Test-Path $gradlewPath) {
            Write-ColorOutput "Using gradlew.bat to execute $apkTask $debugProp..." 'Green'

            if (-not $env:JAVA_HOME) {
                Write-ColorOutput 'JAVA_HOME environment variable not set, trying to find Java installation...' 'Yellow'
                $javaPath = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
                           Where-Object { $_.Name -like 'jdk*' } |
                           Sort-Object Name -Descending |
                           Select-Object -First 1

                if ($javaPath) {
                    $env:JAVA_HOME = $javaPath.FullName
                    Write-ColorOutput "Set JAVA_HOME to: $($env:JAVA_HOME)" 'Green'
                }
                else {
                    Write-ColorOutput 'Java installation not found, please ensure JAVA_HOME environment variable is set' 'Red'
                    return $false
                }
            }

            $env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'

            Write-ColorOutput 'Cleaning previous build...' 'Blue'
            & cmd.exe /c "$gradlewPath clean"
            & cmd.exe /c "$gradlewPath $apkTask $debugProp"
            $buildResult = $LASTEXITCODE
        }
        elseif (Get-Command 'gradle' -ErrorAction SilentlyContinue) {
            Write-ColorOutput "Using gradle to execute $apkTask task..." 'Green'
            & gradle $apkTask $debugProp
            $buildResult = $LASTEXITCODE
        }
        else {
            Write-ColorOutput 'Neither gradle nor gradlew.bat found, cannot build APK' 'Red'
            return $false
        }

        if ($buildResult -eq 0) {
            Write-ColorOutput 'APK build successful' 'Green'
            return $true
        }
        else {
            Write-ColorOutput 'APK build failed' 'Red'
            return $false
        }
    }
    finally {
        Set-Location $currentDir
    }
}

function Copy-ApkAndUpdateConfig {
    param([string]$ProjectRoot, [string]$BuildGeneratedDir, [string]$BuildGeneratedConfigFile)

    $apkSearchPath = Join-Path $ProjectRoot 'android\app\build\outputs\apk'

    $customApkFiles = Get-ChildItem -Path $apkSearchPath -Recurse -Filter '*custom*.apk' -ErrorAction SilentlyContinue
    $apkPath = $null

    if ($customApkFiles) {
        $apkPath = $customApkFiles[0].FullName
        Write-ColorOutput "Found custom APK file: $apkPath" 'Green'
    }
    else {
        $apkFiles = Get-ChildItem -Path $apkSearchPath -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue
        if ($apkFiles) {
            $apkPath = $apkFiles[0].FullName
            Write-ColorOutput "Found APK file: $apkPath" 'Green'
        }
    }

    if (-not $apkPath -or -not (Test-Path $apkPath)) {
        Write-ColorOutput 'Generated APK file not found' 'Red'
        return $false
    }

    $newApkFileName = 'app.npk'
    $targetApkPath = Join-Path $BuildGeneratedDir $newApkFileName

    try {
        if (Test-Path $targetApkPath) { Remove-Item $targetApkPath -Force }
        Copy-Item $apkPath $targetApkPath -Force
        Write-ColorOutput "APK file copied and renamed to build/generated folder: $targetApkPath" 'Green'

        if (-not (Test-Path $BuildGeneratedConfigFile)) {
            $rootConfigFile = Join-Path $ProjectRoot 'PluginConfig.json'
            if (Test-Path $rootConfigFile) {
                Copy-Item $rootConfigFile $BuildGeneratedConfigFile -Force
                Write-ColorOutput 'Copied PluginConfig.json from project root to build/generated folder' 'Blue'
            }
            else {
                Write-ColorOutput 'PluginConfig.json file not found in both project root and build/generated folder' 'Red'
                return $false
            }
        }

        $config = Get-Content $BuildGeneratedConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json

        $configHash = @{}
        $config.PSObject.Properties | ForEach-Object { $configHash[$_.Name] = $_.Value }

        $configHash.nativeCodePackage = "/$newApkFileName"

        $configHash | ConvertTo-Json -Depth 10 | Set-Content $BuildGeneratedConfigFile -Encoding UTF8
        Write-ColorOutput "PluginConfig.json in build/generated folder updated with nativeCodePackage field: /$newApkFileName" 'Green'

        return $true
    }
    catch {
        Write-ColorOutput "Failed to copy APK file or update configuration: $_" 'Red'
        return $false
    }
}

function Build-ReactNativeBundle {
    param([string]$ProjectRoot, [string]$ProjectName, [string]$OutputDir)

    Write-ColorOutput 'Starting React Native bundling...' 'Blue'

    if ($script:WithLogs) {
        $env:WITH_LOGS = '1'
        Write-ColorOutput 'Log mode: INCLUDED (WITH_LOGS=1, debug build)' 'Yellow'
    } elseif ($env:WITH_LOGS -eq '1') {
        Write-ColorOutput 'Log mode: INCLUDED (WITH_LOGS=1 from environment)' 'Yellow'
    } else {
        Write-ColorOutput 'Log mode: STRIPPED (release build)' 'Blue'
    }

    Write-ColorOutput 'Copying mathjax from node_modules...' 'Blue'
    & npm run copy-mathjax --prefix $ProjectRoot

    $bundleOutput = Join-Path $OutputDir "$ProjectName.bundle"
    $assetsDir = $OutputDir

    $bundleCommand = "npx react-native bundle --entry-file index.js --bundle-output `"$bundleOutput`" --platform android --assets-dest `"$assetsDir`" --dev false"

    Write-ColorOutput "Executing command: $bundleCommand" 'Yellow'

    try {
        $process = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $bundleCommand -Wait -PassThru -NoNewWindow -WorkingDirectory $ProjectRoot

        if ($process.ExitCode -eq 0) {
            Write-ColorOutput 'React Native bundling successful' 'Green'
            Write-ColorOutput "Bundle file generated: $bundleOutput" 'Green'
            return $true
        } else {
            Write-ColorOutput "React Native bundling failed, exit code: $($process.ExitCode)" 'Red'
            return $false
        }
    }
    catch {
        Write-ColorOutput "Error occurred while executing React Native bundle command: $_" 'Red'
        return $false
    }
}


function Get-ReactPackagesFromPackageListClass {
    param([string]$ClassesDir)

    $classFile = Join-Path $ClassesDir 'com\facebook\react\PackageList.class'
    if (-not (Test-Path $classFile)) {
        Write-ColorOutput "PackageList.class not found: $classFile" 'Yellow'
        return @()
    }

    try {
        $output = & javap -classpath $ClassesDir -verbose com.facebook.react.PackageList 2>&1
        $lines = $output -split "`r?`n"
        $pkgs = @()
        foreach ($line in $lines) {
            if ($line -match 'new\s+#\d+\s+//\s+class\s+([\w/\.\-$]+)') {
                $raw = $matches[1]
                $normalized = $raw.Replace('/', '.')
                if ($normalized -notmatch '^java\.' -and $normalized -notmatch '^android\.') {
                    if ($normalized -match 'Package$') { $pkgs += $normalized }
                }
            }
        }
        $pkgs = $pkgs | Sort-Object -Unique
        return $pkgs
    }
    catch {
        Write-ColorOutput "Failed to parse PackageList.class via javap: $_" 'Red'
        return @()
    }
}


function Find-ReactPackagesInClassesDir {
    param([string]$ClassesDir)

    if (-not (Test-Path $ClassesDir)) { return @() }

    $classFiles = Get-ChildItem -Path $ClassesDir -Recurse -Filter '*.class' -ErrorAction SilentlyContinue
    $found = @()

    $hasJavap = Get-Command 'javap' -ErrorAction SilentlyContinue

    foreach ($file in $classFiles) {
        try {
            $relative = $file.FullName.Substring($ClassesDir.Length).TrimStart('\\','/')
            $fqcn = $relative.Replace('\\','.').Replace('/','.').Replace('.class','')
            if ([string]::IsNullOrWhiteSpace($fqcn)) { continue }
            if ($hasJavap) {
                $out = & javap -classpath $ClassesDir $fqcn 2>&1
                $text = ($out | Out-String)
                if ($text -match 'implements\s+com\.facebook\.react\.ReactPackage' -or
                    $text -match 'extends\s+com\.facebook\.react\.TurboReactPackage' -or
                    $text -match 'extends\s+com\.facebook\.react\.BaseReactPackage' -or
                    $text -match 'implements\s+com\.facebook\.react\.uimanager\.ViewManagerOnDemandReactPackage') {
                    if ($fqcn -notmatch '^java\.' -and $fqcn -notmatch '^android\.') { $found += $fqcn }
                }
            } else {
                $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
                $ascii = [System.Text.Encoding]::ASCII.GetString($bytes)
                if ($ascii -match 'com/facebook/react/ReactPackage' -or
                    $ascii -match 'com/facebook/react/TurboReactPackage' -or
                    $ascii -match 'com/facebook/react/BaseReactPackage' -or
                    $ascii -match 'com/facebook/react/uimanager/ViewManagerOnDemandReactPackage') {
                    if ($fqcn -notmatch '^java\.' -and $fqcn -notmatch '^android\.') { $found += $fqcn }
                }
            }
        } catch { continue }
    }

    $found = $found | Sort-Object -Unique
    return $found
}

function Copy-IconAndUpdatePath {
    param([string]$ProjectRoot, [string]$BuildGeneratedDir, [string]$BuildGeneratedConfigFile)

    Write-ColorOutput 'Checking and copying icon file...' 'Blue'

    try {
        $rootConfigFile = Join-Path $ProjectRoot 'PluginConfig.json'
        $rootConfig = Get-Content $rootConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json

        if ($rootConfig.iconPath -and $rootConfig.iconPath -ne '') {
            $iconPath = $rootConfig.iconPath
            Write-ColorOutput "Detected icon path: $iconPath" 'Yellow'

            if ([System.IO.Path]::IsPathRooted($iconPath)) {
                $sourceIconPath = $iconPath
            } else {
                $sourceIconPath = Join-Path $ProjectRoot $iconPath
            }

            if (Test-Path $sourceIconPath) {
                $iconFileName = Split-Path $sourceIconPath -Leaf
                $targetIconPath = Join-Path $BuildGeneratedDir $iconFileName

                Copy-Item $sourceIconPath $targetIconPath -Force
                Write-ColorOutput "Icon file copied to: $targetIconPath" 'Green'

                $config = Get-Content $BuildGeneratedConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json

                $configHash = @{}
                $config.PSObject.Properties | ForEach-Object { $configHash[$_.Name] = $_.Value }

                $configHash.iconPath = "/$iconFileName"

                $configHash | ConvertTo-Json -Depth 10 | Set-Content $BuildGeneratedConfigFile -Encoding UTF8
                Write-ColorOutput "Updated iconPath field in build/generated folder's PluginConfig.json: $iconFileName" 'Green'
            } else {
                Write-ColorOutput "Icon file does not exist: $sourceIconPath" 'Yellow'
            }
        } else {
            Write-ColorOutput 'iconPath not set or empty in root PluginConfig.json' 'Yellow'
        }
    }
    catch {
        Write-ColorOutput "Error occurred while copying icon file: $_" 'Red'
    }
}

function New-BuildOutputsDirectory {
    param([string]$ProjectRoot)

    $buildOutputsDir = Join-Path $ProjectRoot 'build\outputs'

    try {
        if (-not (Test-Path $buildOutputsDir)) {
            New-Item -ItemType Directory -Path $buildOutputsDir -Force | Out-Null
            Write-ColorOutput "Created build/outputs directory: $buildOutputsDir" 'Green'
        }
        else {
            Write-ColorOutput "build/outputs directory already exists: $buildOutputsDir" 'Blue'
        }
        return $buildOutputsDir
    }
    catch {
        Write-ColorOutput "Failed to create build/outputs directory: $_" 'Red'
        return $null
    }
}

function New-ZipPackage {
    param(
        [string]$SourceDir,
        [string]$DestinationPath
    )

    Write-ColorOutput 'Starting to package build/generated directory...' 'Blue'

    if (-not (Test-Path $SourceDir)) {
        Write-ColorOutput "Source directory does not exist: $SourceDir" 'Red'
        return $false
    }

    try {
        if (Get-Command 'Compress-Archive' -ErrorAction SilentlyContinue) {
            if (Test-Path $DestinationPath) {
                Remove-Item $DestinationPath -Force
            }

            $sourceItems = Get-ChildItem -Path $SourceDir -Recurse
            if ($sourceItems.Count -eq 0) {
                Write-ColorOutput 'Source directory is empty, cannot create zip file' 'Yellow'
                return $false
            }

            Compress-Archive -Path "$SourceDir\*" -DestinationPath $DestinationPath -Force
            Write-ColorOutput "Zip file created successfully: $DestinationPath" 'Green'
            return $true
        }
        else {
            Write-ColorOutput 'System does not support Compress-Archive command, cannot create zip file' 'Red'
            return $false
        }
    }
    catch {
        Write-ColorOutput "Failed to create zip file: $_" 'Red'
        return $false
    }
}

function Rename-ToSnplgFile {
    param(
        [string]$ZipFilePath,
        [string]$ProjectName,
        [string]$OutputDir
    )

    Write-ColorOutput 'Renaming zip file to .snplg format...' 'Blue'

    if (-not (Test-Path $ZipFilePath)) {
        Write-ColorOutput "Zip file does not exist: $ZipFilePath" 'Red'
        return $null
    }

    try {
        $snplgFileName = "$ProjectName.snplg"
        $snplgFilePath = Join-Path $OutputDir $snplgFileName

        if (Test-Path $snplgFilePath) {
            Remove-Item $snplgFilePath -Force
        }

        Move-Item $ZipFilePath $snplgFilePath -Force
        Write-ColorOutput "File renamed to: $snplgFilePath" 'Green'
        return $snplgFilePath
    }
    catch {
        Write-ColorOutput "Failed to rename file: $_" 'Red'
        return $null
    }
}

function Main {
    Test-OperatingSystem

    $selfCheckOk = Self-CheckScriptIntegrity -ScriptPath $PSCommandPath
    if (-not $selfCheckOk) { return }

    $projectRoot = Get-Location
    Write-ColorOutput "Project root directory: $projectRoot" 'Green'

    Write-ColorOutput '=== Step 1: Check build/generated directory ===' 'Blue'
    $packageInfo = Get-PackageInfo -ProjectRoot $projectRoot
    $projectName = $packageInfo.name
    $buildGeneratedDir = Join-Path $projectRoot 'build\generated'

    if (Test-Path $buildGeneratedDir) {
        Write-ColorOutput "Detected build/generated directory already exists: $buildGeneratedDir" 'Yellow'
    } else {
        New-Item -ItemType Directory -Path $buildGeneratedDir -Force | Out-Null
        Write-ColorOutput "Created build/generated directory: $buildGeneratedDir" 'Green'
    }

    $verifyFiles = Get-ChildItem -Path (Join-Path $projectRoot 'node_modules\sn-plugin-lib') -Recurse -Filter 'VerifyUtils.*' -File -ErrorAction SilentlyContinue
    foreach ($vf in $verifyFiles) {
        if (Select-String -Path $vf.FullName -Pattern "console.log\('verifyParams'" -Quiet) {
            (Get-Content $vf.FullName -Raw) -replace "  console\.log\('verifyParams'[^\r\n]*", '' | Set-Content $vf.FullName -Encoding UTF8 -NoNewline
            $rel = $vf.FullName.Substring($projectRoot.Path.Length + 1)
            Write-ColorOutput "Patched: $rel" 'Green'
        }
    }

    Write-ColorOutput '=== Step 2: Execute React Native bundling ===' 'Blue'
    $bundleSuccess = Build-ReactNativeBundle -ProjectRoot $projectRoot -ProjectName $projectName -OutputDir $buildGeneratedDir
    if (-not $bundleSuccess) {
        Write-ColorOutput 'React Native bundling failed, script terminated' 'Red'
        return
    }

    Write-ColorOutput '=== Step 3: Check root directory PluginConfig.json ===' 'Blue'
    $rootConfigFile = Join-Path $projectRoot 'PluginConfig.json'

    if (Test-Path $rootConfigFile) {
        Write-ColorOutput 'Detected root directory PluginConfig.json file already exists, skipping generation step' 'Yellow'
    } else {
        Write-ColorOutput '=== Step 4: Generate random pluginID ===' 'Blue'
        $pluginId = New-RandomString
        Write-ColorOutput "Generated pluginID: $pluginId" 'Blue'

        Write-ColorOutput '=== Step 5: Generate root directory PluginConfig.json ===' 'Blue'
        New-PluginConfig -PluginId $pluginId -PackageInfo $packageInfo -ProjectRoot $projectRoot
    }

    Increment-PluginVersion -ConfigFile $rootConfigFile

    Write-ColorOutput '=== Step 6: Copy PluginConfig.json to build/generated folder and handle icon ===' 'Blue'
    $buildGeneratedConfigFile = Join-Path $buildGeneratedDir 'PluginConfig.json'
    Copy-Item $rootConfigFile $buildGeneratedConfigFile -Force
    Write-ColorOutput 'Copied root directory PluginConfig.json to build/generated folder' 'Green'

    if ($script:WithLogs) {
        $cfg = Get-Content $buildGeneratedConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $cfgHash = @{}
        $cfg.PSObject.Properties | ForEach-Object { $cfgHash[$_.Name] = $_.Value }
        $cfgHash.name = $cfgHash.name + ' - Dev'
        $cfgHash | ConvertTo-Json -Depth 10 | Set-Content $buildGeneratedConfigFile -Encoding UTF8
        Write-ColorOutput "Dev build: name set to '$($cfgHash.name)'" 'Yellow'
    }

    Copy-IconAndUpdatePath -ProjectRoot $projectRoot -BuildGeneratedDir $buildGeneratedDir -BuildGeneratedConfigFile $buildGeneratedConfigFile

    Write-ColorOutput '=== Step 7: Parse project ReactPackage implementations ===' 'Blue'
    $projectReactPkgs = @()
    $projectReactPkgs += Find-ProjectReactPackages -ProjectRoot $projectRoot
    $projectReactPkgs += Find-ManualReactPackagesFromApplication -ProjectRoot $projectRoot
    $projectReactPkgs = @($projectReactPkgs | Where-Object { $_ } | Sort-Object -Unique)
    Write-ColorOutput "Project ReactPackage count: $($projectReactPkgs.Count)" 'Blue'

    Write-ColorOutput '=== Step 8: Scan node_modules for Android sources ===' 'Blue'
    $thirdPartyNativeMods = Scan-NodeModulesNativeCode -ProjectRoot $projectRoot

    Write-ColorOutput '=== Step 9: Build condition check ===' 'Blue'
    $shouldBuildNative = ($projectReactPkgs.Count -gt 0) -or ($thirdPartyNativeMods.Count -gt 0)
    if ($shouldBuildNative) {
        Write-ColorOutput "Build conditions met: project packages=$($projectReactPkgs.Count), third-party native modules=$($thirdPartyNativeMods.Count)" 'Green'

        Write-ColorOutput '=== Step 10: Invoke Gradle to build APK ===' 'Blue'
        $buildSuccess = Build-AndroidApk -ProjectRoot $projectRoot -BuildGeneratedConfigFile $buildGeneratedConfigFile -RequireReactPackagesCheck:$false

        if ($buildSuccess) {
            Write-ColorOutput '=== Step 11: Copy APK and update nativeCodePackage ===' 'Blue'
            $apkCopied = Copy-ApkAndUpdateConfig -ProjectRoot $projectRoot -BuildGeneratedDir $buildGeneratedDir -BuildGeneratedConfigFile $buildGeneratedConfigFile
            if (-not $apkCopied) {
                Write-ColorOutput 'Failed to copy APK or update configuration; package generation stopped' 'Red'
                return
            }
        } else {
            Write-ColorOutput 'Gradle build failed; native package generation stopped' 'Red'
            return
        }

        Write-ColorOutput '=== Step 12: Parse Autolinking PackageList.java and merge lists ===' 'Blue'
        $excludePkgs = @('com.facebook.react.shell.MainReactPackage', 'com.ratta.supernote.pluginlib.PluginPackage')
        $pkgFromAutolinking = Get-ReactPackagesFromAutolinkingSource -ProjectRoot $projectRoot -Exclude $excludePkgs

        $allPkgs = @()
        $allPkgs += $projectReactPkgs
        $allPkgs += $pkgFromAutolinking
        $dedupPkgs = $allPkgs | Sort-Object -Unique

        Write-ColorOutput '=== Step 13: Write reactPackages field ===' 'Blue'
        foreach ($p in $dedupPkgs) { Write-ColorOutput "  - write: $p" 'Green' }
        Update-PluginConfigPackages -ProjectRoot $projectRoot -FoundPackages $dedupPkgs -BuildGeneratedDir $buildGeneratedDir
    }
    else {
        Write-ColorOutput 'Build conditions not met; skipping steps 10–13 and proceeding to packaging' 'Yellow'
    }

    if (-not (Test-PluginConfigPackages -ConfigFile $buildGeneratedConfigFile)) {
        Write-ColorOutput 'Manifest validation failed; package generation stopped' 'Red'
        return
    }

    if (-not $shouldBuildNative -and
        (-not (Remove-NativeCodePackageReference -ConfigFile $buildGeneratedConfigFile))) {
        return
    }

    if ($shouldBuildNative -and
        (-not (Test-Path -LiteralPath (Join-Path $buildGeneratedDir 'app.npk') -PathType Leaf))) {
        Write-ColorOutput 'Native build was requested, but build/generated/app.npk is missing' 'Red'
        return
    }

    Write-ColorOutput 'Step 14: Package build/generated directory and generate .snplg file...' 'Green'

    $buildOutputsDir = New-BuildOutputsDirectory -ProjectRoot $projectRoot
    if (-not $buildOutputsDir) {
        Write-ColorOutput 'Unable to create build/outputs directory, skipping packaging step' 'Red'
        return
    }

    if (-not (Test-Path $buildGeneratedDir)) {
        Write-ColorOutput 'build/generated directory does not exist, cannot package' 'Red'
        return
    }

    $generatedItems = Get-ChildItem -Path $buildGeneratedDir -Recurse
    if ($generatedItems.Count -eq 0) {
        Write-ColorOutput 'build/generated directory is empty, cannot package' 'Yellow'
        return
    }

    $tempZipFileName = 'temp_package.zip'
    $tempZipPath = Join-Path $buildOutputsDir $tempZipFileName

    $zipResult = New-ZipPackage -SourceDir $buildGeneratedDir -DestinationPath $tempZipPath
    if (-not $zipResult) {
        Write-ColorOutput 'Packaging failed, unable to create zip file' 'Red'
        return
    }

    $devSuffix = if ($script:WithLogs) { '-dev' } else { '' }
    $snplgFileName = "$($packageInfo.Name)$devSuffix.snplg"
    $finalSnplgPath = Join-Path $buildOutputsDir $snplgFileName
    $outputName = "$($packageInfo.Name)$devSuffix"
    $renamedPath = Rename-ToSnplgFile -ZipFilePath $tempZipPath -ProjectName $outputName -OutputDir $buildOutputsDir
    if ($renamedPath -and (Test-Path -LiteralPath $finalSnplgPath -PathType Leaf)) {
        Write-ColorOutput "Plugin package successfully generated: $finalSnplgPath" 'Green'

        $fileInfo = Get-Item -LiteralPath $finalSnplgPath
        $fileSizeMB = [math]::Round($fileInfo.Length / 1MB, 2)
        Write-ColorOutput "File size: $fileSizeMB MB" 'Blue'
    } else {
        Write-ColorOutput 'Failed to rename to .snplg file' 'Red'
    }
}


function Resolve-ClassesDir {
    param([string]$ProjectRoot)

    $javacDir = Join-Path $ProjectRoot 'android\app\build\intermediates\javac'
    if (-not (Test-Path $javacDir)) { return '' }

    $candidates = Get-ChildItem -Path $javacDir -Directory -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'compile.*JavaWithJavac\\classes$' } |
        Sort-Object LastWriteTime -Descending

    if ($candidates -and $candidates.Count -gt 0) { return $candidates[0].FullName }
    return ''
}

Main
