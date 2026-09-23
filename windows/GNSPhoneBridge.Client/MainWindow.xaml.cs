using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using FluentFTP;
using GNSPhoneBridge.Client.Models;
using GNSPhoneBridge.Client.Services;

namespace GNSPhoneBridge.Client;

public partial class MainWindow : Window
{
    private const string AppDownloadUrl = "https://github.com/elboricuacuba-source/conet-fr/releases/latest/download/ConetFR.apk";

    private readonly PairingService _pairing = new();
    private readonly ObservableCollection<FtpEntry> _entries = new();

    private AsyncFtpClient? _ftp;
    private string _currentPath = "/";
    private CancellationTokenSource? _pairingCts;
    private string? _lastFtpUrl;

    public MainWindow()
    {
        InitializeComponent();
        FileList.ItemsSource = _entries;

        DownloadQrImage.Source = QrImageGenerator.Generate(AppDownloadUrl, size: 150);

        Loaded += (_, _) => ShowQrAndWaitForPhone();
        Closed += (_, _) => Cleanup();
    }

    private void ShowQrAndWaitForPhone()
    {
        ScanPanel.Visibility = Visibility.Visible;
        BrowsePanel.Visibility = Visibility.Collapsed;

        var localIp = NetworkUtils.GetLocalIPv4();
        if (localIp == null)
        {
            StatusText.Text = "  —  No se detectó una red Wi-Fi. Conéctate a la misma red que el teléfono.";
            return;
        }

        _pairing.RotateToken();
        var qrContent = _pairing.BuildQrContent(localIp);
        QrImage.Source = QrImageGenerator.Generate(qrContent);
        StatusText.Text = $"  —  Esperando al teléfono... ({localIp}:{_pairing.Port})";

        _pairingCts = new CancellationTokenSource();
        _ = WaitForPhoneAsync(_pairingCts.Token);
    }

    private async Task WaitForPhoneAsync(CancellationToken token)
    {
        // Everything below resumes on the UI thread: this method was started from a UI-thread
        // event handler and nothing here uses ConfigureAwait(false), so the WPF dispatcher's
        // synchronization context marshals every continuation back automatically.
        PhoneConnectionInfo? phone;
        try
        {
            phone = await _pairing.WaitForPairingAsync(token);
        }
        catch (Exception ex)
        {
            StatusText.Text = $"  —  Error esperando al teléfono: {ex.Message}";
            return;
        }

        if (phone == null || token.IsCancellationRequested) return;

        var ftpUrl = $"ftp://{Uri.EscapeDataString(phone.Username)}:{Uri.EscapeDataString(phone.Password)}" +
                     $"@{phone.Host}:{phone.Port}/";
        _lastFtpUrl = ftpUrl;

        StatusText.Text = $"  —  Conectando a {phone.Host}...";

        try
        {
            var ftp = new AsyncFtpClient(phone.Host, phone.Username, phone.Password, phone.Port);
            await ftp.AutoConnect(token);
            _ftp = ftp;

            ScanPanel.Visibility = Visibility.Collapsed;
            BrowsePanel.Visibility = Visibility.Visible;
            StatusText.Text = $"  —  Conectado a {phone.Host}";

            CreateDocumentsShortcut(ftpUrl);
            await NavigateTo("/");
        }
        catch (Exception ex)
        {
            StatusText.Text = $"  —  No se pudo conectar al teléfono: {ex.Message}";
        }
    }

    private async Task NavigateTo(string path)
    {
        if (_ftp == null) return;
        try
        {
            var items = await _ftp.GetListing(path);
            _currentPath = path;
            PathText.Text = path;
            BackButton.IsEnabled = path != "/";

            _entries.Clear();
            foreach (var item in items.OrderByDescending(i => i.Type == FtpObjectType.Directory).ThenBy(i => i.Name))
            {
                _entries.Add(new FtpEntry
                {
                    Name = item.Name,
                    FullPath = item.FullName,
                    IsDirectory = item.Type == FtpObjectType.Directory,
                    Size = item.Size,
                    Modified = item.Modified,
                });
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"No se pudo listar la carpeta: {ex.Message}", "Error",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private async void FileList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (FileList.SelectedItem is not FtpEntry entry) return;

        if (entry.IsDirectory)
        {
            await NavigateTo(entry.FullPath);
            return;
        }

        await DownloadAndOpen(entry);
    }

    private async Task DownloadAndOpen(FtpEntry entry)
    {
        if (_ftp == null) return;

        // Explorer's ftp:// namespace resolves an individual file's "open" action through the
        // OS's registered "ftp" protocol handler (often the web browser) instead of downloading
        // it and handing it to the right local app. Downloading it ourselves and opening the
        // LOCAL copy sidesteps that entirely - it's just a normal file at that point.
        var tempDir = Path.Combine(Path.GetTempPath(), "ConetFR");
        Directory.CreateDirectory(tempDir);
        var localPath = Path.Combine(tempDir, entry.Name);

        StatusText.Text = $"  —  Abriendo {entry.Name}...";
        try
        {
            var status = await _ftp.DownloadFile(localPath, entry.FullPath, FtpLocalExists.Overwrite);
            if (status == FtpStatus.Success)
            {
                Process.Start(new ProcessStartInfo(localPath) { UseShellExecute = true });
                StatusText.Text = $"  —  Conectado";
            }
            else
            {
                StatusText.Text = $"  —  No se pudo abrir {entry.Name}";
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = $"  —  Error al abrir: {ex.Message}";
        }
    }

    private void OpenInExplorer(string ftpUrl)
    {
        try
        {
            // Launch explorer.exe directly with the ftp:// URL as its argument - if we instead
            // ShellExecute the URL itself, Windows resolves it through the "ftp" protocol handler,
            // which on many PCs is the default web browser instead of File Explorer. Explorer is
            // still handy here for dragging several files in or out at once.
            Process.Start(new ProcessStartInfo("explorer.exe", ftpUrl) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"No se pudo abrir el Explorador: {ex.Message}", "Error",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void CreateDocumentsShortcut(string ftpUrl)
    {
        try
        {
            var documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            var path = Path.Combine(documents, "Mi telefono (Conet FR).lnk");
            var explorerPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe");

            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            dynamic shell = Activator.CreateInstance(shellType)!;
            dynamic shortcut = shell.CreateShortcut(path);
            shortcut.TargetPath = explorerPath;
            shortcut.Arguments = ftpUrl;
            shortcut.Description = "Abrir los archivos del teléfono (Conet FR)";
            shortcut.Save();
        }
        catch
        {
            // Not critical - the in-app file list already works without it.
        }
    }

    private void ReopenButton_Click(object sender, RoutedEventArgs e)
    {
        if (_lastFtpUrl != null) OpenInExplorer(_lastFtpUrl);
    }

    private async void BackButton_Click(object sender, RoutedEventArgs e)
    {
        if (_currentPath == "/") return;
        var parent = _currentPath.TrimEnd('/');
        parent = parent.Contains('/') ? parent[..(parent.LastIndexOf('/') + 1)] : "/";
        if (string.IsNullOrEmpty(parent)) parent = "/";
        await NavigateTo(parent);
    }

    private async void RescanButton_Click(object sender, RoutedEventArgs e)
    {
        _pairingCts?.Cancel();
        if (_ftp != null)
        {
            try { await _ftp.Disconnect(); } catch { /* ignore */ }
            _ftp = null;
        }
        _entries.Clear();
        ShowQrAndWaitForPhone();
    }

    private void Cleanup()
    {
        _pairingCts?.Cancel();
        _pairing.Dispose();
        _ftp?.Dispose();
    }
}
