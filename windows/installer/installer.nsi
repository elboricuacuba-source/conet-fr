; installer.nsi
; Instalador de Conet FR con NSIS (Nullsoft Install System), mismo estilo
; que el resto de instaladores GNS (banner con el logo, MUI2 clasico).
; Instala en la carpeta del usuario (sin pedir permisos de administrador).

!include "MUI2.nsh"

Name "Conet FR"
OutFile "output\ConetFR-Setup.exe"
InstallDir "$LOCALAPPDATA\Conet FR"
RequestExecutionLevel user
SetCompressor /SOLID lzma

!define MUI_ICON "..\GNSPhoneBridge.Client\Assets\icon.ico"
!define MUI_UNICON "..\GNSPhoneBridge.Client\Assets\icon.ico"
!define MUI_WELCOMEFINISHPAGE_BITMAP "installer_wizard_image.bmp"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "installer_nsis_header.bmp"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\ConetFR.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Iniciar Conet FR ahora"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Spanish"

Section "Conet FR (requerido)" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  File "..\publish\ConetFR.exe"

  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Conet FR"
  CreateShortCut "$SMPROGRAMS\Conet FR\Conet FR.lnk" "$INSTDIR\ConetFR.exe"
  CreateShortCut "$SMPROGRAMS\Conet FR\Desinstalar Conet FR.lnk" "$INSTDIR\Uninstall.exe"
SectionEnd

Section "Crear un ícono en el Escritorio" SecDesktop
  CreateShortCut "$DESKTOP\Conet FR.lnk" "$INSTDIR\ConetFR.exe"
SectionEnd

LangString DESC_SecMain ${LANG_SPANISH} "El programa principal - obligatorio."
LangString DESC_SecDesktop ${LANG_SPANISH} "Crea un acceso directo en el Escritorio."

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} $(DESC_SecMain)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} $(DESC_SecDesktop)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  Delete "$INSTDIR\ConetFR.exe"
  Delete "$INSTDIR\Uninstall.exe"
  Delete "$DESKTOP\Conet FR.lnk"
  Delete "$SMPROGRAMS\Conet FR\Conet FR.lnk"
  Delete "$SMPROGRAMS\Conet FR\Desinstalar Conet FR.lnk"
  RMDir "$SMPROGRAMS\Conet FR"
  RMDir "$INSTDIR"
SectionEnd
