using CmfLink.Protocol;

namespace CmfLink.App;

internal static class Labels
{
    public static string Noise(NoiseMode mode) => mode switch
    {
        NoiseMode.NoiseCancellation => "Noise cancellation",
        NoiseMode.Transparency => "Transparency",
        _ => "Off",
    };

    /// <summary>Short form for the segmented control, where three have to fit in 330 px.</summary>
    public static string NoiseShort(NoiseMode mode) => mode == NoiseMode.NoiseCancellation ? "ANC" : Noise(mode);

    public static string Preset(EqPreset preset) => preset switch
    {
        EqPreset.Dirac => "Dirac",
        EqPreset.Pop => "Pop",
        EqPreset.Rock => "Rock",
        EqPreset.Classical => "Classical",
        EqPreset.Electronic => "Electronic",
        EqPreset.EnhanceVocals => "Vocals",
        EqPreset.Custom => "Custom",
        _ => preset.ToString(),
    };

    public static string Tap(TapType type) => type switch
    {
        TapType.DoubleTap => "Double tap",
        TapType.TripleTap => "Triple tap",
        TapType.TapAndHold => "Tap & hold",
        TapType.DoubleTapAndHold => "Double tap & hold",
        _ => type.ToString(),
    };

    public static string Action(TapAction action) => action switch
    {
        TapAction.None => "No action",
        TapAction.PlayPause => "Play / pause",
        TapAction.NextTrack => "Next track",
        TapAction.PreviousTrack => "Previous track",
        TapAction.VoiceAssistant => "Voice assistant",
        TapAction.VolumeUp => "Volume up",
        TapAction.VolumeDown => "Volume down",
        TapAction.NoiseControlAll => "ANC → Transp. → Off",
        TapAction.NoiseControlAncTransparency => "ANC ↔ Transparency",
        TapAction.NoiseControlAncOff => "ANC ↔ Off",
        TapAction.NoiseControlTransparencyOff => "Transparency ↔ Off",
        _ => $"0x{(byte)action:X2}",
    };
}
