namespace CmfLink.Core;

/// <summary>A byte pipe to the buds' control channel. The Bluetooth layer implements it; tests fake it.</summary>
public interface ITransport : IAsyncDisposable
{
    /// <summary>Raised on a background thread for every chunk read. A chunk may hold part of a frame or several.</summary>
    event Action<byte[]>? DataReceived;

    /// <summary>Raised once when the channel closes, with the error if it failed.</summary>
    event Action<Exception?>? Closed;

    Task OpenAsync(CancellationToken ct);
    Task WriteAsync(byte[] data, CancellationToken ct);
}
