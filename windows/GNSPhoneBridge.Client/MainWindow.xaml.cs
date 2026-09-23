using System.IO;
using System.Windows;
using GNSPhoneBridge.Client.Services;

namespace GNSPhoneBridge.Client;

public partial class MainWindow : Window
{
    private const string AppDownloadUrl = "https://github.com/elboricuacuba-source/conet-fr/releases/latest/download/ConetFR.apk";

    private readonly PairingService _pairing = new();
    private CancellationTokenSource? _pairingCts;
    private string? _lastFtpUrl;

    public MainWindow()
    {
        InitializeComponent();

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

        ScanPanel.Visibility = Visibility.Collapsed;
        BrowsePanel.Visibility = Visibility.Visible;
        ConnectedText.Text = $"Conectado a {phone.Host}";
        StatusText.Text = $"  —  Conectado a {phone.Host}";

        OpenInExplorer(ftpUrl);
        CreateDocumentsShortcut(ftpUrl);
    }

    private void OpenInExplorer(string ftpUrl)
    {
        // Launch explorer.exe directly with the ftp:// URL as its argument - if we instead
        // ShellExecute the URL itself, Windows resolves it through the "ftp" protocol handler
        // (now Conet FR itself, once picked in Settings), which only knows how to handle
        // individual files, not the folder root.
        FileOpenHandler.OpenInExplorer(ftpUrl);
    }

    private void CreateDocumentsShortcut(string ftpUrl)
    {
        try
        {
            var documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            var path = Path.Combine(documents, "Mi telefono (Conet FR).lnk");
            var explorerPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe");

            // A plain .url internet shortcut opens through the OS's default "ftp" protocol handler,
            // which on many PCs is the web browser, not File Explorer. A .lnk that targets
            // explorer.exe directly (with the ftp URL as its argument) always opens Explorer.
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
            // Not critical - the folder that just opened in Explorer is already usable.
        }
    }

    private void ReopenButton_Click(object sender, RoutedEventArgs e)
    {
        if (_lastFtpUrl != null) OpenInExplorer(_lastFtpUrl);
    }

    private void RescanButton_Click(object sender, RoutedEventArgs e)
    {
        _pairingCts?.Cancel();
        ShowQrAndWaitForPhone();
    }

    private void FixAssociationButton_Click(object sender, RoutedEventArgs e)
    {
        ProtocolRegistration.OpenDefaultAppsSettings();
        MessageBox.Show(this,
            "En la página que se abrió, busca \"FTP\" (o \"Elegir aplicaciones predeterminadas por protocolo\") " +
            "y selecciona Conet FR. Después de eso, las fotos y música se abrirán bien con doble clic.",
            "Conet FR", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void Cleanup()
    {
        _pairingCts?.Cancel();
        _pairing.Dispose();
    }
}
