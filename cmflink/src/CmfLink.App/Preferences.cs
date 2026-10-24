using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;

namespace CmfLink.App;

/// <summary>App settings, in %LOCALAPPDATA%\CmfLink\settings.json.</summary>
internal sealed class Preferences
{
    public bool ShowBatteryInTray { get; set; } = true;
    public bool AlwaysShowIcon { get; set; }
    public bool LowBatteryAlert { get; set; } = true;
    public int LowBatteryThreshold { get; set; } = 15;
    public bool NoiseHotkey { get; set; }
    public bool LogToFile { get; set; }
    /// <summary>The case's last reported level, kept across runs since the buds rarely report it.</summary>
    public int? LastCasePercent { get; set; }
    public DateTime? LastCaseAt { get; set; }
    /// <summary>Set after the first run turned on launch at login, so turning it off sticks.</summary>
    public bool LoginItemInitialized { get; set; }

    public static readonly string Folder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CmfLink");
    private static readonly string FilePath = Path.Combine(Folder, "settings.json");

    public static Preferences Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize(File.ReadAllText(FilePath), PreferencesJson.Default.Preferences) ?? new();
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { }
        return new();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, PreferencesJson.Default.Preferences));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }
}

[JsonSerializable(typeof(Preferences))]
[JsonSourceGenerationOptions(WriteIndented = true)]
internal sealed partial class PreferencesJson : JsonSerializerContext;

/// <summary>Launch at login through the per-user Run key. No admin rights, and Task Manager's Startup tab can turn it off.</summary>
internal static class LoginItem
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "CMF Link";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string;
        }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue(Name, $"\"{Environment.ProcessPath}\" --background");
            else key.DeleteValue(Name, throwOnMissingValue: false);
        }
    }
}
