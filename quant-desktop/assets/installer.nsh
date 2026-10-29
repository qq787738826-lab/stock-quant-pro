!macro customInstall
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayName" "Stock Quant Pro"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayIcon" "$INSTDIR\${APP_EXECUTABLE_FILENAME},0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "UninstallString" '$"$INSTDIR\${UNINSTALL_FILENAME}$" /currentuser'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "QuietUninstallString" '$"$INSTDIR\${UNINSTALL_FILENAME}$" /currentuser /S'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "NoRepair" 1
!macroend

!macro customUnInstall
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro"
!macroend
