using System.Diagnostics;
using System.IO;
using System.Windows;
using GNSPhoneBridge.Client.Services;

namespace GNSPhoneBridge.Client;

public partial class MainWindow : Window
{
    private const string AppDownloadUrl = "https://github.com/elboricuacuba-source/conet-fr/releases/latest/download/ConetFR.apk";

    private readonly PairingService _pairing = new();
    private CancellationTokenSource? _pairingCts;
    private string? _lastHttpUrl;

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

        var httpUrl = $"http://{phone.Host}:{phone.HttpPort}/";
        _lastHttpUrl = httpUrl;

        ScanPanel.Visibility = Visibility.Collapsed;
        BrowsePanel.Visibility = Visibility.Visible;
        ConnectedText.Text = $"Conectado a {phone.Host}";
        StatusText.Text = $"  —  Conectado a {phone.Host}";

        OpenInBrowser(httpUrl);
        CreateDocumentsShortcut(httpUrl);
    }

    private void OpenInBrowser(string httpUrl)
    {
        try
        {
            // http:// is handled natively by whatever the default browser is - no special
            // handling needed here, unlike ftp:// which has quirks with per-file opens.
            Process.Start(new ProcessStartInfo(httpUrl) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"No se pudo abrir el navegador: {ex.Message}", "Error",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void CreateDocumentsShortcut(string httpUrl)
    {
        try
        {
            var documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            var path = Path.Combine(documents, "Mi telefono (Conet FR).url");
            File.WriteAllText(path, $"[InternetShortcut]\r\nURL={httpUrl}\r\n");
        }
        catch
        {
            // Not critical - the browser tab that just opened is already usable.
        }
    }

    private void ReopenButton_Click(object sender, RoutedEventArgs e)
    {
        if (_lastHttpUrl != null) OpenInBrowser(_lastHttpUrl);
    }

    private void RescanButton_Click(object sender, RoutedEventArgs e)
    {
        _pairingCts?.Cancel();
        ShowQrAndWaitForPhone();
    }

    private void Cleanup()
    {
        _pairingCts?.Cancel();
        _pairing.Dispose();
    }
}
