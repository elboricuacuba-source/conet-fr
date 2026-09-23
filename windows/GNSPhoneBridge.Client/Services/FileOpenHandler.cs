using System.Diagnostics;
using System.IO;
using FluentFTP;

namespace GNSPhoneBridge.Client.Services;

/// <summary>
/// Handles being launched with an ftp:// URL as its argument - which is what
/// happens when Windows resolves a double-click on a file inside Explorer's
/// ftp folder view through us (once picked as the ftp protocol handler in
/// Settings). Downloads that one file locally and hands it to the correct
/// native app, then exits. Never shows a window - the person just sees their
/// photo/song open, exactly like opening any other file.
/// </summary>
public static class FileOpenHandler
{
    public static bool CanHandle(string[] args) =>
        args.Length > 0 && args[0].StartsWith("ftp://", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Now that we're a registered ftp handler, Windows can hand us the whole
    /// folder URL too (not just individual files) - e.g. when something asks
    /// to open the root itself. We only know how to download files, so any
    /// directory-shaped request should just be forwarded to Explorer instead.
    /// </summary>
    public static bool IsDirectoryRequest(string[] args)
    {
        if (!CanHandle(args)) return false;
        try
        {
            var path = new Uri(args[0]).AbsolutePath;
            return string.IsNullOrEmpty(path) || path.EndsWith("/");
        }
        catch
        {
            return false;
        }
    }

    public static void OpenInExplorer(string ftpUrl)
    {
        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", ftpUrl) { UseShellExecute = true });
        }
        catch
        {
            // Nothing sensible to do here - there's no window to report this in.
        }
    }

    public static async Task HandleAsync(string[] args)
    {
        try
        {
            var uri = new Uri(args[0]);
            var userInfo = uri.UserInfo.Split(':', 2);
            if (userInfo.Length != 2) return;

            var user = Uri.UnescapeDataString(userInfo[0]);
            var pass = Uri.UnescapeDataString(userInfo[1]);
            var port = uri.IsDefaultPort ? 21 : uri.Port;
            var remotePath = uri.AbsolutePath;
            var fileName = Path.GetFileName(remotePath);
            if (string.IsNullOrEmpty(fileName)) return;

            var tempDir = Path.Combine(Path.GetTempPath(), "ConetFR");
            Directory.CreateDirectory(tempDir);
            var localPath = Path.Combine(tempDir, fileName);

            using var ftp = new AsyncFtpClient(uri.Host, user, pass, port);
            await ftp.AutoConnect();
            var status = await ftp.DownloadFile(localPath, remotePath, FtpLocalExists.Overwrite);
            if (status == FtpStatus.Success)
            {
                Process.Start(new ProcessStartInfo(localPath) { UseShellExecute = true });
            }
        }
        catch
        {
            // Silent by design - this path never shows UI, so there's nowhere to report an error.
        }
    }
}
