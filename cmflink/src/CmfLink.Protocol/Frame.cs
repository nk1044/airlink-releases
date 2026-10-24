using System.Buffers.Binary;

namespace CmfLink.Protocol;

/// <summary>
/// One frame of the Nothing / CMF control protocol (see PROTOCOL.md §2):
/// <code>55 | ctl lo ctl hi | cmd lo cmd hi | len lo len hi | seq | payload… | crc lo crc hi</code>
/// The CRC (CRC-16/MODBUS) is present when <c>ctl</c> has bit 0x20. Requests cover header and payload,
/// as the official app does; the buds' replies and pushes cover the payload only.
/// </summary>
public sealed class Frame
{
    public const byte StartOfFrame = 0x55;
    public const int HeaderLength = 8;
    public const int CrcLength = 2;

    public const ushort ControlCrc = 0x0020;
    public const ushort ControlCounter = 0x0040;
    public const ushort ControlStatusMask = 0x001F;
    public const ushort ControlDeviceTypeMask = 0x0F00;
    public const int DeviceTypeTwsHeadset = 1;

    /// <summary>What every request carries: TWS headset, CRC present, sequence counter in use.</summary>
    public const ushort RequestControl = 0x0100 | ControlCounter | ControlCrc;

    public ushort Control { get; }
    public ushort Command { get; }
    public byte Sequence { get; }
    public byte[] Payload { get; }

    /// <summary>False only for a decoded frame whose CRC didn't match. Encoded frames are always valid.</summary>
    public bool CrcValid { get; }

    /// <summary>The bytes as received, for decoded frames. Null for frames built here.</summary>
    public byte[]? Raw { get; }

    public Frame(ushort control, ushort command, byte sequence, byte[]? payload = null, bool crcValid = true, byte[]? raw = null)
    {
        Raw = raw;
        Control = control;
        Command = command;
        Sequence = sequence;
        Payload = payload ?? [];
        CrcValid = crcValid;
    }

    public static Frame Request(ushort command, byte sequence, params byte[] payload) =>
        new(RequestControl, command, sequence, payload);

    /// <summary>0 means OK. Replies to a refused request carry a non-zero status.</summary>
    public int Status => Control & ControlStatusMask;
    public bool HasCrc => (Control & ControlCrc) != 0;
    public int DeviceType => (Control & ControlDeviceTypeMask) >> 8;

    /// <summary>A reply clears bit 15 of the request's command: 0xC007 → 0x4007.</summary>
    public static ushort ReplyCommand(ushort request) => (ushort)(request & 0x7FFF);

    public byte[] Encode()
    {
        int length = HeaderLength + Payload.Length + (HasCrc ? CrcLength : 0);
        var bytes = new byte[length];
        bytes[0] = StartOfFrame;
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(1), Control);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(3), Command);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(5), (ushort)Payload.Length);
        bytes[7] = Sequence;
        Payload.CopyTo(bytes, HeaderLength);
        if (HasCrc)
        {
            int end = HeaderLength + Payload.Length;
            BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(end), Crc16.Compute(bytes.AsSpan(0, end)));
        }
        return bytes;
    }

    public override string ToString() =>
        $"ctl={Control:X4} cmd={Command:X4} seq={Sequence:X2} [{Hex.Format(Payload)}]" + (CrcValid ? "" : " CRC-MISMATCH");
}
