using System.Diagnostics;
using Microsoft.Win32;

namespace GNSPhoneBridge.Client.Services;

/// <summary>
/// Registers Conet FR as a selectable handler for ftp:// links (per-user, no
/// admin needed). Windows still requires the person to actually pick it in
/// Settings &gt; Default apps - no app is allowed to silently take over a
/// protocol association since Windows 8.
/// </summary>
public static class ProtocolRegistration
{
    private const string ProgId = "ConetFR.ftp";
    private const string AppRegName = "Conet FR";

    public static void EnsureRegistered()
    {
        try
        {
            var exePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName;
            if (string.IsNullOrEmpty(exePath)) return;

            using (var progIdRoot = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{ProgId}"))
            {
                progIdRoot?.SetValue("", "Conet FR - abrir archivo del teléfono");
            }
            using (var command = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{ProgId}\shell\open\command"))
            {
                command?.SetValue("", $"\"{exePath}\" \"%1\"");
            }
            using (var capabilities = Registry.CurrentUser.CreateSubKey(@"Software\ConetFR\Capabilities"))
            {
                capabilities?.SetValue("ApplicationName", "Conet FR");
                capabilities?.SetValue("ApplicationDescription", "Abre fotos, música y archivos de tu teléfono");
            }
            using (var urlAssociations = Registry.CurrentUser.CreateSubKey(@"Software\ConetFR\Capabilities\URLAssociations"))
            {
                urlAssociations?.SetValue("ftp", ProgId);
            }
            using (var registeredApps = Registry.CurrentUser.CreateSubKey(@"Software\RegisteredApplications"))
            {
                registeredApps?.SetValue(AppRegName, @"Software\ConetFR\Capabilities");
            }
        }
        catch
        {
            // Not critical - Explorer still browses ftp fine either way; only the
            // per-file "open" association would stay pointed at the default browser.
        }
    }

    /// <summary>Opens the Windows page where the person can pick Conet FR for ftp links.</summary>
    public static void OpenDefaultAppsSettings()
    {
        try
        {
            Process.Start(new ProcessStartInfo("ms-settings:defaultapps") { UseShellExecute = true });
        }
        catch
        {
            // Ignore - the app still works, they just won't see the settings page pop up.
        }
    }
}
