using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace GNSPhoneBridge.Client.Services;

public record PhoneConnectionInfo(string Host, int Port, int HttpPort, string Username, string Password);

/// <summary>
/// Small side-channel the phone calls back on right after it scans this PC's
/// QR code, handing over its own server addresses so we can connect to it.
/// </summary>
public class PairingService : IDisposable
{
    private const string Protocol = "GNSBRIDGE2";
    private readonly TcpListener _listener;

    public int Port { get; }
    public string Token { get; private set; }

    public PairingService(int port = 5057)
    {
        Port = port;
        _listener = new TcpListener(IPAddress.Any, port);
        Token = GenerateToken();
    }

    private static string GenerateToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(4)).ToLowerInvariant();

    /// <summary>Call before showing a fresh QR code so an old scan can't be replayed.</summary>
    public string RotateToken()
    {
        Token = GenerateToken();
        return Token;
    }

    public string BuildQrContent(string localIp) => $"{Protocol}|{localIp}|{Port}|{Token}";

    /// <summary>Waits until a phone pairs successfully, or the token returns null on cancellation.</summary>
    public async Task<PhoneConnectionInfo?> WaitForPairingAsync(CancellationToken token)
    {
        _listener.Start();
        try
        {
            while (!token.IsCancellationRequested)
            {
                using var client = await _listener.AcceptTcpClientAsync(token);
                using var stream = client.GetStream();
                using var reader = new StreamReader(stream, Encoding.UTF8);
                using var writer = new StreamWriter(stream, Encoding.UTF8) { AutoFlush = true, NewLine = "\n" };

                var line = await reader.ReadLineAsync(token);
                var parsed = Parse(line);
                if (parsed == null)
                {
                    await writer.WriteLineAsync("NO");
                    continue;
                }

                await writer.WriteLineAsync("OK");
                return parsed;
            }
        }
        catch (OperationCanceledException)
        {
            // Expected when the caller cancels while waiting.
        }
        finally
        {
            _listener.Stop();
        }
        return null;
    }

    private PhoneConnectionInfo? Parse(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        var parts = line.Trim().Split('|');
        if (parts.Length != 7 || parts[0] != Protocol) return null;
        if (parts[1] != Token) return null;
        if (!int.TryParse(parts[3], out var port)) return null;
        if (!int.TryParse(parts[4], out var httpPort)) return null;
        return new PhoneConnectionInfo(parts[2], port, httpPort, parts[5], parts[6]);
    }

    public void Dispose()
    {
        try { _listener.Stop(); } catch { /* already stopped */ }
    }
}
