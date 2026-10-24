using System.Runtime.InteropServices.WindowsRuntime;
using CmfLink.Core;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;

namespace CmfLink.Bluetooth;

public sealed record PairedDevice(string Id, string Name, ulong Address, bool IsConnected)
{
    public string AddressText => string.Join(":", BitConverter.GetBytes(Address).Take(6).Reverse().Select(b => b.ToString("X2")));
}

public sealed record RfcommServiceInfo(string ServiceId, string? Name, string Host, string ServiceName);

/// <summary>Paired-device lookup. None of this pages or scans: it reads what Windows already knows.</summary>
public static class BluetoothDevices
{
    private const string IsConnectedProperty = "System.Devices.Aep.IsConnected";
    private const string AddressProperty = "System.Devices.Aep.DeviceAddress";

    /// <summary>Classic Bluetooth devices paired with this PC.</summary>
    public static async Task<IReadOnlyList<PairedDevice>> PairedAsync()
    {
        var infos = await DeviceInformation.FindAllAsync(
            BluetoothDevice.GetDeviceSelectorFromPairingState(true),
            [IsConnectedProperty, AddressProperty]);
        return infos.Select(ToPaired).ToList();
    }

    /// <summary>Paired devices that some profile in <see cref="DeviceRegistry"/> claims.</summary>
    public static async Task<IReadOnlyList<(PairedDevice Device, DeviceProfile Profile)>> PairedBudsAsync()
    {
        var result = new List<(PairedDevice, DeviceProfile)>();
        foreach (var device in await PairedAsync())
        {
            if (DeviceRegistry.Match(device.Name) is { } profile) result.Add((device, profile));
        }
        return result;
    }

    /// <summary>
    /// RFCOMM services from the device's SDP record. Only call this on a connected device:
    /// an uncached query on a disconnected one would page it.
    /// </summary>
    public static async Task<IReadOnlyList<RfcommServiceInfo>> RfcommServicesAsync(BluetoothDevice device, BluetoothCacheMode mode)
    {
        var result = await device.GetRfcommServicesAsync(mode);
        var list = new List<RfcommServiceInfo>();
        foreach (var service in result.Services)
        {
            string? name = null;
            try
            {
                var attributes = await service.GetSdpRawAttributesAsync(mode);
                if (attributes.TryGetValue(0x0100, out var raw)) name = SdpText(raw.ToArray());
            }
            catch (Exception) { }
            list.Add(new RfcommServiceInfo(service.ServiceId.Uuid.ToString(), name, service.ConnectionHostName.CanonicalName, service.ConnectionServiceName));
            service.Dispose();
        }
        return list;
    }

    internal static PairedDevice ToPaired(DeviceInformation info)
    {
        bool connected = info.Properties.TryGetValue(IsConnectedProperty, out var c) && c is true;
        ulong address = 0;
        if (info.Properties.TryGetValue(AddressProperty, out var a) && a is string text)
            address = Convert.ToUInt64(text.Replace(":", "").Replace("-", ""), 16);
        return new PairedDevice(info.Id, info.Name, address, connected);
    }

    /// <summary>An SDP text element: 0x25 (text, 1-byte length) or 0x26 (2-byte length), then the bytes.</summary>
    private static string? SdpText(byte[] raw)
    {
        if (raw.Length < 2) return null;
        return raw[0] switch
        {
            0x25 => System.Text.Encoding.UTF8.GetString(raw, 2, Math.Min(raw[1], raw.Length - 2)).TrimEnd('\0'),
            0x26 when raw.Length >= 3 => System.Text.Encoding.UTF8.GetString(raw, 3, Math.Min(raw[1] << 8 | raw[2], raw.Length - 3)).TrimEnd('\0'),
            _ => null,
        };
    }
}
