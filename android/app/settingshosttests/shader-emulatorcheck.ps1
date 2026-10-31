param([string]$Root = (Resolve-Path "$PSScriptRoot/../../../build/frontend-preview").Path)
. "$Root/session.ps1"
function ClickShader([string]$Label) {
    for($i=0;$i -lt 12;$i++) {
        $ui=CurrentUi
        if(@($ui.SelectNodes('//node') | Where-Object { $_.text -eq $Label -or $_.'content-desc' -eq $Label }).Count) { TapNode $ui $Label; return }
        Adb @('shell','input','swipe','500','1400','500','550','250') | Out-Null
    }
    throw "Shader UI node missing: $Label"
}
Baseline 0 no
ClickShader 'Settings for Clockwork Orchard'; ClickShader 'Performance'
ClickShader 'Vertex shader processing · Auto'; ClickShader 'CPU'
ClickShader 'GPU vertex shader execution · Compiled'; ClickShader 'Interpreted'
ClickShader 'Compile CPU vertex shaders'
ClickShader 'Specialized shader compilation · Asynchronous'; ClickShader 'Wait for compilation'
Capture 'advanced-shader-selectors-final' 'Performance'
foreach($label in @('Use generic shaders','Use supported depth blits','Prefer floating-point depth','Use static blend state')) { ClickShader $label }
Capture 'advanced-shader-flags-final' 'Performance'
ClickShader 'Use these settings'; ClickShader 'Save'
$prefs=Prefs
Check (@($prefs.map.string | Where-Object { $_.name -eq '0004000000000001.advanced.shader_mode' -and $_.'#text' -eq 'cpu' }).Count -gt 0) 'Shader processing selector saves CPU mode'
Check (@($prefs.map.string | Where-Object { $_.name -eq '0004000000000001.advanced.shader_execution' -and $_.'#text' -eq 'interpreted' }).Count -gt 0) 'GPU shader execution saves interpreted independently'
Check (@($prefs.map.boolean | Where-Object { $_.name -eq '0004000000000001.advanced.cpu_shader_jit' -and $_.value -eq 'false' }).Count -gt 0) 'CPU vertex shader JIT saves independently'
Check (@($prefs.map.string | Where-Object { $_.name -eq '0004000000000001.advanced.shader_compilation' -and $_.'#text' -eq 'wait' }).Count -gt 0) 'Specialized shader compilation saves wait mode'
foreach($key in @('generic_shaders','depth_blits','float_depth','static_blend')) {
    $expected=if($key -eq 'depth_blits') { 'false' } else { 'true' }
    Check (@($prefs.map.boolean | Where-Object { $_.name -eq "0004000000000001.advanced.$key" -and $_.value -eq $expected }).Count -gt 0) "Shader flag $key saves $expected"
}
ClickShader 'Settings for Clockwork Orchard'; ClickShader 'Performance'
ClickShader 'Vertex shader processing · CPU'; ClickShader 'GPU'; ClickShader 'Cancel'; ClickShader 'Save'
$prefs=Prefs
Check (@($prefs.map.string | Where-Object { $_.name -eq '0004000000000001.advanced.shader_mode' -and $_.'#text' -eq 'cpu' }).Count -gt 0) 'Shader group Cancel preserves previous CPU mode'
Adb @('shell','am','force-stop',$package) | Out-Null
Write-Host 'Shader frontend emulator checks passed.'
