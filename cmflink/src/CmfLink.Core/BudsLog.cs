using System.Diagnostics;

namespace CmfLink.Core;

/// <summary>
/// Tiny logger. Messages go to the debugger, plus <see cref="Sink"/> when someone listens
/// (the probe prints them; the app writes a rolling file only when logging is turned on).
/// </summary>
public static class BudsLog
{
    public static Action<string>? Sink { get; set; }

    /// <summary>Hex-dump every frame in and out. Off by default.</summary>
    public static bool LogFrames { get; set; }

    public static void Info(string message) => Write("info", message);
    public static void Warn(string message) => Write("warn", message);
    public static void Error(string message) => Write("error", message);

    public static void Frame(string direction, string text)
    {
        if (LogFrames) Write(direction, text);
    }

    private static void Write(string level, string message)
    {
        var line = $"{DateTime.Now:HH:mm:ss.fff} {level,-5} {message}";
        Debug.WriteLine(line);
        Sink?.Invoke(line);
    }
}
