using CmfLink.Protocol;

namespace CmfLink.Core.Profiles;

/// <summary>CMF Buds 2a (model B185). Values are in PROTOCOL.md with where each came from.</summary>
public sealed class CmfBuds2a : DeviceProfile
{
    public override string Id => "cmf-buds-2a";
    public override string DisplayName => "CMF Buds 2a";
    public override IReadOnlyList<string> Names => ["CMF Buds 2a"];

    public override IReadOnlyList<NoiseMode> NoiseModes =>
        [NoiseMode.NoiseCancellation, NoiseMode.Transparency, NoiseMode.Off];

    public override byte NoiseValue(NoiseMode mode) => mode switch
    {
        NoiseMode.NoiseCancellation => 0x01,
        NoiseMode.Transparency => 0x07,
        _ => 0x05,
    };

    public override NoiseMode? NoiseModeFromValue(byte value) => value switch
    {
        // 02–04 are the mid / low / adaptive strengths of other models. The 2a has one strength.
        0x01 or 0x02 or 0x03 or 0x04 => NoiseMode.NoiseCancellation,
        0x05 => NoiseMode.Off,
        0x07 => NoiseMode.Transparency,
        _ => null,
    };

    public override IReadOnlyList<EqPreset> EqPresets =>
    [
        EqPreset.Dirac, EqPreset.Pop, EqPreset.Rock, EqPreset.Classical,
        EqPreset.Electronic, EqPreset.EnhanceVocals, EqPreset.Custom,
    ];

    public override bool SupportsUltraBass => true;
    public override bool SupportsLowLatency => true;
    public override bool SupportsDualConnection => true;

    /// <summary>Bass, mid and treble, as the official app defines them for this model.</summary>
    public override IReadOnlyList<EqBand> CustomEqBands =>
    [
        new(EqFilter.LowShelf, 0, 140f, 0.8f),
        new(EqFilter.Peak, 0, 980f, 0.7f),
        new(EqFilter.HighShelf, 0, 6900f, 1.0f),
    ];

    public override IReadOnlyList<TapType> TapTypes =>
        [TapType.DoubleTap, TapType.TripleTap, TapType.TapAndHold, TapType.DoubleTapAndHold];

    /// <summary>Per gesture, as the official app offers them for this model (ear-web's B185 config).</summary>
    public override IReadOnlyList<TapAction> TapActions(TapType type) => type switch
    {
        TapType.DoubleTap => [TapAction.PlayPause, TapAction.NextTrack, TapAction.PreviousTrack, TapAction.VoiceAssistant, TapAction.None],
        TapType.TripleTap => [TapAction.NextTrack, TapAction.PreviousTrack, TapAction.VoiceAssistant, TapAction.None],
        TapType.TapAndHold =>
        [
            TapAction.NoiseControlAll, TapAction.NoiseControlAncTransparency, TapAction.NoiseControlAncOff,
            TapAction.NoiseControlTransparencyOff, TapAction.VoiceAssistant, TapAction.None,
        ],
        TapType.DoubleTapAndHold => [TapAction.VolumeUp, TapAction.VolumeDown, TapAction.VoiceAssistant, TapAction.None],
        _ => [],
    };
}
