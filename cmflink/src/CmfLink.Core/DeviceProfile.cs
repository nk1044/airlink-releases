using CmfLink.Protocol;

namespace CmfLink.Core;

/// <summary>
/// Everything model-specific. Each model is one subclass in Profiles/, registered in <see cref="DeviceRegistry"/>.
/// </summary>
public abstract class DeviceProfile
{
    /// <summary>The control service every Nothing / CMF model exposes over RFCOMM. Its channel differs per model.</summary>
    public static readonly Guid ControlService = new("aeac4a03-dff5-498f-843a-34487cf133eb");

    public abstract string Id { get; }
    public abstract string DisplayName { get; }

    /// <summary>Bluetooth names this profile claims. Case and spaces are ignored.</summary>
    public abstract IReadOnlyList<string> Names { get; }

    /// <summary>In the order the UI shows them.</summary>
    public abstract IReadOnlyList<NoiseMode> NoiseModes { get; }
    public abstract byte NoiseValue(NoiseMode mode);
    public abstract NoiseMode? NoiseModeFromValue(byte value);

    /// <summary>In the order the UI shows them. Empty when the model has no presets.</summary>
    public virtual IReadOnlyList<EqPreset> EqPresets => [];

    public virtual bool SupportsUltraBass => false;
    public virtual bool SupportsLowLatency => false;
    public virtual bool SupportsInEarDetection => false;
    public virtual bool SupportsDualConnection => false;

    /// <summary>The bands the custom preset edits, with gain 0. Empty when there is no custom EQ.</summary>
    public virtual IReadOnlyList<EqBand> CustomEqBands => [];
    public virtual float CustomEqMaxGain => 6f;

    public virtual IReadOnlyList<TapType> TapTypes => [];
    /// <summary>The actions a gesture can be bound to. Not every action fits every gesture.</summary>
    public virtual IReadOnlyList<TapAction> TapActions(TapType type) => [];

    /// <summary>
    /// Whether the buds accept this mode right now. Models without wear detection can't tell,
    /// so they always say yes and rely on the buds' reply.
    /// </summary>
    public virtual bool CanSetNoiseMode(NoiseMode mode, bool? leftInEar, bool? rightInEar) => true;

    public bool Matches(string bluetoothName)
    {
        var name = Normalize(bluetoothName);
        return Names.Any(n => Normalize(n) == name);
    }

    private static string Normalize(string s) => new string(s.Where(c => !char.IsWhiteSpace(c)).ToArray()).ToLowerInvariant();
}

public static class DeviceRegistry
{
    public static readonly IReadOnlyList<DeviceProfile> All = [new Profiles.CmfBuds2a()];

    public static DeviceProfile? Match(string bluetoothName) => All.FirstOrDefault(p => p.Matches(bluetoothName));
}
