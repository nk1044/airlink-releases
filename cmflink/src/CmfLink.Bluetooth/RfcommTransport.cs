using CmfLink.Core;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace CmfLink.Bluetooth;

public sealed class ServiceNotFoundException(string message) : Exception(message);

/// <summary>
/// The control channel over a Windows RFCOMM stream socket. The channel number comes from
/// the buds' SDP record every time, since it differs between models (15 on some, 16 on others).
/// </summary>
public sealed class RfcommTransport : ITransport
{
    private const uint ReadChunk = 1024;

    private readonly BluetoothDevice _device;
    private readonly Guid _serviceId;
    private StreamSocket? _socket;
    private DataWriter? _writer;
    private CancellationTokenSource? _readCts;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private int _closed;

    public event Action<byte[]>? DataReceived;
    public event Action<Exception?>? Closed;

    /// <summary>"channel 16" style, once open. For logs and the probe.</summary>
    public string? Endpoint { get; private set; }

    public RfcommTransport(BluetoothDevice device, Guid serviceId)
    {
        _device = device;
        _serviceId = serviceId;
    }

    public async Task OpenAsync(CancellationToken ct)
    {
        var id = RfcommServiceId.FromUuid(_serviceId);
        // Cached first: no radio traffic. Fall back to a live SDP query only if Windows hasn't seen the record.
        var result = await _device.GetRfcommServicesForIdAsync(id, BluetoothCacheMode.Cached).AsTask(ct);
        if (result.Error != BluetoothError.Success || result.Services.Count == 0)
            result = await _device.GetRfcommServicesForIdAsync(id, BluetoothCacheMode.Uncached).AsTask(ct);
        if (result.Error != BluetoothError.Success)
            throw new ServiceNotFoundException($"SDP query failed: {result.Error}");
        if (result.Services.Count == 0)
            throw new ServiceNotFoundException($"{_device.Name} has no control service ({_serviceId})");

        using var service = result.Services[0];
        var socket = new StreamSocket();
        socket.Control.KeepAlive = false;
        try
        {
            await socket.ConnectAsync(service.ConnectionHostName, service.ConnectionServiceName,
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication).AsTask(ct);
        }
        catch
        {
            socket.Dispose();
            throw;
        }

        Endpoint = $"{service.ConnectionHostName.CanonicalName} {service.ConnectionServiceName}";
        _socket = socket;
        _writer = new DataWriter(socket.OutputStream);
        _readCts = new CancellationTokenSource();
        _ = ReadLoopAsync(socket.InputStream, _readCts.Token);
    }

    public async Task WriteAsync(byte[] data, CancellationToken ct)
    {
        var writer = _writer ?? throw new InvalidOperationException("Not open");
        await _writeLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            writer.WriteBytes(data);
            await writer.StoreAsync().AsTask(ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            Close(e);
            throw new IOException("Write to the buds failed", e);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>Waits in LoadAsync between messages, so an idle channel costs no CPU.</summary>
    private async Task ReadLoopAsync(IInputStream input, CancellationToken ct)
    {
        Exception? error = null;
        try
        {
            using var reader = new DataReader(input) { InputStreamOptions = InputStreamOptions.Partial };
            while (!ct.IsCancellationRequested)
            {
                uint count = await reader.LoadAsync(ReadChunk).AsTask(ct).ConfigureAwait(false);
                if (count == 0) break; // the buds closed the channel
                var buffer = new byte[count];
                reader.ReadBytes(buffer);
                DataReceived?.Invoke(buffer);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception e)
        {
            error = e;
        }
        Close(ct.IsCancellationRequested ? null : error);
    }

    private void Close(Exception? error)
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        _readCts?.Cancel();
        try { _writer?.DetachStream(); } catch (Exception) { }
        _writer?.Dispose();
        _socket?.Dispose();
        Closed?.Invoke(error);
    }

    public ValueTask DisposeAsync()
    {
        Close(null);
        _readCts?.Dispose();
        return ValueTask.CompletedTask;
    }
}
