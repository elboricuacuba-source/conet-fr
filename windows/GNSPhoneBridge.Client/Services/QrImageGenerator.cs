using System.Drawing.Imaging;
using System.IO;
using System.Windows.Media.Imaging;
using ZXing;
using ZXing.Common;
using ZXing.Windows.Compatibility;

namespace GNSPhoneBridge.Client.Services;

public static class QrImageGenerator
{
    public static BitmapImage Generate(string content, int size = 320)
    {
        var writer = new BarcodeWriter
        {
            Format = BarcodeFormat.QR_CODE,
            Options = new EncodingOptions
            {
                Width = size,
                Height = size,
                Margin = 1,
            },
        };

        using var bitmap = writer.Write(content);
        using var mem = new MemoryStream();
        bitmap.Save(mem, ImageFormat.Png);
        mem.Position = 0;

        var image = new BitmapImage();
        image.BeginInit();
        image.StreamSource = mem;
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.EndInit();
        image.Freeze();
        return image;
    }
}
