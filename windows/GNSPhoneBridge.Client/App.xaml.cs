using System.Windows;
using System.Windows.Threading;

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
    }
}
