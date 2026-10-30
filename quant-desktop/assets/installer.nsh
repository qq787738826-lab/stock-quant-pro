!macro customInstall
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayName" "Stock Quant Pro"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayIcon" "$INSTDIR\${APP_FILENAME}.exe,0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "UninstallString" "$\"$INSTDIR\Uninstall ${PRODUCT_FILENAME}.exe$\" /currentuser"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "QuietUninstallString" "$\"$INSTDIR\Uninstall ${PRODUCT_FILENAME}.exe$\" /currentuser /S"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "NoRepair" 1
!macroend

!macro customUnInstall
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro"
!macroend

!ifndef BUILD_UNINSTALLER
Function .onInstSuccess
  SetRegView 64
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayName" "Stock Quant Pro"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "DisplayIcon" "$INSTDIR\${APP_FILENAME}.exe,0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "UninstallString" "$\"$INSTDIR\Uninstall ${PRODUCT_FILENAME}.exe$\" /currentuser"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "QuietUninstallString" "$\"$INSTDIR\Uninstall ${PRODUCT_FILENAME}.exe$\" /currentuser /S"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\com.stockquant.pro" "NoRepair" 1
FunctionEnd
!endif
