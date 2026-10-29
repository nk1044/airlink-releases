namespace CmfLink.Protocol;

/// <summary>Component ids used by battery reports, gestures and find-my-buds.</summary>
public enum Component : byte
{
    Left = 0x02,
    Right = 0x03,
    Case = 0x04,
    /// <summary>Single-unit devices (Ear (stick)) report this instead of left and right.</summary>
    Stereo = 0x06,
}

public readonly record struct BatteryLevel(int Percent, bool Charging);

/// <summary>Noise-control modes. The wire values are per model and live in the device profile.</summary>
public enum NoiseMode
{
    Off,
    Transparency,
    NoiseCancellation,
}

/// <summary>EQ presets ("listening modes"). Values are what goes on the wire with 0xF01D.</summary>
public enum EqPreset : byte
{
    Rock = 0x01,
    Electronic = 0x02,
    Pop = 0x03,
    EnhanceVocals = 0x04,
    Classical = 0x05,
    Custom = 0x06,
    Dirac = 0x07,
}

public static class UltraBass
{
    public const int MinLevel = 1;
    public const int MaxLevel = 5;
}

public enum TapType : byte
{
    DoubleTap = 0x02,
    TripleTap = 0x03,
    TapAndHold = 0x07,
    DoubleTapAndHold = 0x09,
}

public enum TapAction : byte
{
    None = 0x01,
    PlayPause = 0x02,
    PreviousTrack = 0x08,
    NextTrack = 0x09,
    NoiseControlAll = 0x0A,
    VoiceAssistant = 0x0B,
    VolumeUp = 0x12,
    VolumeDown = 0x13,
    NoiseControlAncOff = 0x14,
    NoiseControlTransparencyOff = 0x15,
    NoiseControlAncTransparency = 0x16,
}

/// <summary>One tap binding. <see cref="Button"/> is 0x01 on every model seen so far.</summary>
public readonly record struct Gesture(Component Bud, byte Button, TapType Type, TapAction Action);

public sealed record DualDevice(string Mac, string Name, bool IsSelf, bool IsConnected)
{
    public byte[] MacBytes => Hex.Parse(Mac);
}
