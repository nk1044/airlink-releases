namespace CmfLink.Bluetooth;

/// <summary>
/// Only one client can hold the buds' control channel. The app and the probe share an
/// exclusively opened lock file, so the second one can say who has it instead of failing
/// to connect. Windows releases the file if the holder crashes.
/// </summary>
public sealed class ChannelLock : IDisposable
{
    private static readonly string LockPath = Path.Combine(Path.GetTempPath(), "CmfLink.control-channel.lock");

    private readonly FileStream _file;

    private ChannelLock(FileStream file) => _file = file;

    /// <summary>Null when another process holds the channel. <paramref name="holder"/> is then its name.</summary>
    public static ChannelLock? TryAcquire(string ownerName, out string? holder)
    {
        holder = null;
        try
        {
            var file = new FileStream(LockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.Read);
            file.SetLength(0);
            using (var writer = new StreamWriter(file, leaveOpen: true)) writer.Write(ownerName);
            file.Flush();
            return new ChannelLock(file);
        }
        catch (IOException)
        {
            try
            {
                // The holder has the file open for writing, so allow that while we read.
                using var file = new FileStream(LockPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                holder = new StreamReader(file).ReadToEnd().Trim();
            }
            catch (IOException) { }
            return null;
        }
    }

    public void Dispose() => _file.Dispose();
}
