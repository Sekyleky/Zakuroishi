param([string]$Root = (Resolve-Path "$PSScriptRoot/../../../build/frontend-preview").Path)
. "$Root/session.ps1"
function Click([string]$Label) { TapNode (CurrentUi) $Label; Start-Sleep -Milliseconds 250 }
function OpenSettings { Click 'Settings for Clockwork Orchard' }
function Resolution([string]$Label) {
    $ui=CurrentUi; $node=@($ui.SelectNodes('//node') | Where-Object text -like 'Rendering resolution · *')[0]
    if(!$node) { throw 'Missing resolution selector' }
    TapNode $ui $node.text; Click $Label
}
function ResolutionPref {
    $prefs=Prefs
    return @($prefs.map.int | Where-Object name -eq '0004000000000001.resolution')[0].value
}
& "$Root/install-fixtures.ps1"
Baseline 0 no
OpenSettings
foreach($group in @('Display','Performance','Motion','Controls','Audio','System')) {
    $ui=CurrentUi
    if($group -eq 'System') { Adb @('shell','input','swipe','500','1350','500','600','300') | Out-Null; $ui=CurrentUi }
    Check (@($ui.SelectNodes('//node') | Where-Object text -eq $group).Count -gt 0) ("Advanced group visible: "+$group)
}
Click 'Cancel'; OpenSettings; Click 'Display'; Resolution '2× · Sharper picture'; Click 'Cancel'; Click 'Save'
Check ((ResolutionPref) -eq '1') 'Group Cancel preserves legacy resolution'
OpenSettings; Click 'Display'; Resolution '3× · Highest detail'; Click 'Use these settings'; Click 'Cancel'
Check ((ResolutionPref) -eq '1') 'Root Cancel discards accepted group draft'
OpenSettings; Click 'Display'; Resolution '2× · Sharper picture'; Click 'Use a second physical display'; Click 'Use these settings'; Click 'Save'
Check ((ResolutionPref) -eq '2') 'Root Save commits chosen resolution'
$prefs=Prefs
Check (@($prefs.map.boolean | Where-Object { $_.name -eq '0004000000000001.advanced.dual_display' -and $_.value -eq 'true' }).Count -gt 0) 'Root Save commits actual dual-display setting'
OpenSettings; Capture 'advanced-new-groups-portrait' 'Game settings'; Click 'Cancel'
$ui=CurrentUi
$add=@($ui.SelectNodes('//node') | Where-Object text -eq 'Add game')[0]
if($add.enabled -eq 'true') {
    Click 'Add game'
    for($i=0;$i -lt 8;$i++) {
        $ui=CurrentUi; $pack=@($ui.SelectNodes('//node') | Where-Object text -eq 'Include texture pack (optional)')
        if($pack.Count) { break }
        Adb @('shell','input','swipe','500','1350','500','500','300') | Out-Null
    }
    Check ($pack.Count -gt 0 -and $pack[0].checked -eq 'false') 'Optional texture-pack checkbox defaults off'
    Capture 'advanced-optional-pack-portrait' 'Include texture pack (optional)'
    Click 'Cancel'
} else {
    Check ($add.enabled -eq 'false') 'Non-ARM64 emulator correctly disables Add game'
    'SKIP Optional-pack checkbox UI: x86_64 emulator cannot enter ARM64 import flow. Host optional-pack and separate SAF checks cover import logic.' | Tee-Object -FilePath "$root/checks.log" -Append | Write-Host
}Baseline 1 yes
OpenSettings; Capture 'advanced-new-groups-landscape-dark' 'Game settings'; Click 'Cancel'
Adb @('shell','settings','put','system','font_scale','2.0') | Out-Null
Baseline 0 yes
OpenSettings; Click 'Audio'; Capture 'advanced-audio-largefont-portrait-dark' 'Audio'; Click 'Cancel'; Click 'Cancel'
Adb @('shell','settings','put','system','font_scale','1.0') | Out-Null
Baseline 0 no
Write-Host 'Updated frontend emulator checks passed.'
