using System.Windows;
using GNSPhoneBridge.Client.Services;

namespace GNSPhoneBridge.Client;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(args.Exception.ToString(), "Conet FR - Error",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            MessageBox.Show(args.ExceptionObject.ToString(), "Conet FR - Error fatal",
                MessageBoxButton.OK, MessageBoxImage.Error);
        };

        ProtocolRegistration.EnsureRegistered();

        if (FileOpenHandler.CanHandle(e.Args))
        {
            // Launched by Explorer resolving a double-clicked file's ftp:// link to us -
            // download it and open it with the right native app, no window at all.
            _ = RunFileOpenAndShutdown(e.Args);
            return;
        }

        new MainWindow().Show();
    }

    private async Task RunFileOpenAndShutdown(string[] args)
    {
        await FileOpenHandler.HandleAsync(args);
        Shutdown();
    }
}
