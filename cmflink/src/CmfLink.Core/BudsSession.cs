using CmfLink.Protocol;

namespace CmfLink.Core;

public sealed class BudsTimeoutException(ushort command)
    : TimeoutException($"No reply to {Commands.Name(command)}")
{
    public ushort Command { get; } = command;
}

public sealed class BudsRefusedException(ushort command, int status)
    : Exception($"The buds refused {Commands.Name(command)} (status 0x{status:X2})")
{
    public int Status { get; } = status;
}

/// <summary>
/// One open control channel. Keeps a single request in flight, matches its reply, and
/// hands every decoded message (replies and pushes alike) to <see cref="MessageReceived"/>.
/// </summary>
public sealed class BudsSession : IAsyncDisposable
{
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(2.5);

    private readonly ITransport _transport;
    private readonly FrameDecoder _decoder;
    private readonly SemaphoreSlim _inFlight = new(1, 1);
    private readonly Lock _lock = new();
    private TaskCompletionSource<Frame>? _pending;
    private ushort _pendingReply;
    private byte _sequence;
    private bool _closed;

    /// <summary>Raised on a background thread.</summary>
    public event Action<BudsMessage>? MessageReceived;
    /// <summary>Raised on a background thread for every frame, decoded or not. The probe prints these.</summary>
    public event Action<Frame>? FrameReceived;
    public event Action<Frame>? FrameSent;
    /// <summary>Raised once, on a background thread, when the channel closes.</summary>
    public event Action<Exception?>? Closed;

    /// <param name="rejectBadCrc">False lets the probe show frames whose CRC doesn't match.</param>
    public BudsSession(ITransport transport, bool rejectBadCrc = true)
    {
        _decoder = new FrameDecoder { RejectBadCrc = rejectBadCrc };
        _transport = transport;
        _transport.DataReceived += OnData;
        _transport.Closed += OnClosed;
    }

    public int CrcErrors => _decoder.CrcErrors;

    public Task OpenAsync(CancellationToken ct) => _transport.OpenAsync(ct);

    /// <summary>Sends a request and waits for its reply. Throws <see cref="BudsTimeoutException"/> or <see cref="BudsRefusedException"/>.</summary>
    public async Task<Frame> RequestAsync(ushort command, byte[]? payload = null, TimeSpan? timeout = null, CancellationToken ct = default)
    {
        await _inFlight.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var tcs = new TaskCompletionSource<Frame>(TaskCreationOptions.RunContinuationsAsynchronously);
            Frame frame;
            lock (_lock)
            {
                if (_closed) throw new ObjectDisposedException(nameof(BudsSession));
                // 1…250, like the official app.
                _sequence = (byte)(_sequence >= 250 ? 1 : _sequence + 1);
                frame = Frame.Request(command, _sequence, payload ?? []);
                _pending = tcs;
                _pendingReply = Frame.ReplyCommand(command);
            }

            BudsLog.Frame("out", $"{Commands.Name(command)}: {Hex.Format(frame.Encode())}");
            FrameSent?.Invoke(frame);
            await _transport.WriteAsync(frame.Encode(), ct).ConfigureAwait(false);

            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(timeout ?? DefaultTimeout);
            try
            {
                var reply = await tcs.Task.WaitAsync(timeoutCts.Token).ConfigureAwait(false);
                if (reply.Status != 0) throw new BudsRefusedException(command, reply.Status);
                return reply;
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new BudsTimeoutException(command);
            }
        }
        finally
        {
            lock (_lock) _pending = null;
            _inFlight.Release();
        }
    }

    /// <summary>Sends without waiting for a reply. Used by the probe's raw mode and for fire-and-forget writes.</summary>
    public async Task SendAsync(ushort command, byte[]? payload = null, CancellationToken ct = default)
    {
        Frame frame;
        lock (_lock)
        {
            _sequence = (byte)(_sequence >= 250 ? 1 : _sequence + 1);
            frame = Frame.Request(command, _sequence, payload ?? []);
        }
        BudsLog.Frame("out", $"{Commands.Name(command)}: {Hex.Format(frame.Encode())}");
        FrameSent?.Invoke(frame);
        await _transport.WriteAsync(frame.Encode(), ct).ConfigureAwait(false);
    }

    private void OnData(byte[] data)
    {
        List<Frame> frames;
        lock (_lock) frames = _decoder.Feed(data);

        foreach (var frame in frames)
        {
            BudsLog.Frame("in", $"{Commands.Name(frame.Command)}: {frame}");
            FrameReceived?.Invoke(frame);

            // Message first, then the reply: whoever awaits the request then sees the state it carried.
            try
            {
                if (Parsers.Parse(frame) is { } message) MessageReceived?.Invoke(message);
            }
            catch (Exception e)
            {
                BudsLog.Warn($"Couldn't parse {frame}: {e.Message}");
            }

            TaskCompletionSource<Frame>? match = null;
            lock (_lock)
            {
                if (_pending != null && frame.Command == _pendingReply)
                    match = _pending;
            }
            match?.TrySetResult(frame);
        }
    }

    private void OnClosed(Exception? error)
    {
        TaskCompletionSource<Frame>? pending;
        lock (_lock)
        {
            if (_closed) return;
            _closed = true;
            pending = _pending;
        }
        pending?.TrySetException(error ?? new IOException("The control channel closed"));
        Closed?.Invoke(error);
    }

    public async ValueTask DisposeAsync()
    {
        _transport.DataReceived -= OnData;
        await _transport.DisposeAsync().ConfigureAwait(false);
        OnClosed(null);
        _transport.Closed -= OnClosed;
    }
}
