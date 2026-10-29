using System.Buffers.Binary;

namespace CmfLink.Protocol;

/// <summary>
/// Reassembles frames from an RFCOMM byte stream. Reads can split a frame or carry several,
/// so bytes are buffered until a whole frame is there. Anything before a start byte is skipped.
/// </summary>
public sealed class FrameDecoder
{
    /// <summary>No reply seen so far comes close; anything bigger means we're out of sync.</summary>
    public const int MaxPayload = 1024;

    private readonly List<byte> _buffer = new(256);

    /// <summary>Bytes discarded while looking for a start of frame.</summary>
    public int SkippedBytes { get; private set; }
    public int CrcErrors { get; private set; }

    /// <summary>Drop frames with a bad CRC and resync after their start byte, instead of passing them on.</summary>
    public bool RejectBadCrc { get; init; } = true;

    public List<Frame> Feed(ReadOnlySpan<byte> data)
    {
        foreach (byte b in data) _buffer.Add(b);
        var frames = new List<Frame>();
        Span<byte> header = stackalloc byte[Frame.HeaderLength];

        while (true)
        {
            int start = _buffer.IndexOf(Frame.StartOfFrame);
            if (start < 0)
            {
                SkippedBytes += _buffer.Count;
                _buffer.Clear();
                break;
            }
            if (start > 0)
            {
                SkippedBytes += start;
                _buffer.RemoveRange(0, start);
            }
            if (_buffer.Count < Frame.HeaderLength) break;

            for (int i = 0; i < header.Length; i++) header[i] = _buffer[i];
            ushort control = BinaryPrimitives.ReadUInt16LittleEndian(header[1..]);
            ushort command = BinaryPrimitives.ReadUInt16LittleEndian(header[3..]);
            int length = BinaryPrimitives.ReadUInt16LittleEndian(header[5..]);
            byte sequence = header[7];

            bool hasCrc = (control & Frame.ControlCrc) != 0;
            bool plausible = length <= MaxPayload
                && (control & Frame.ControlDeviceTypeMask) >> 8 == Frame.DeviceTypeTwsHeadset;
            if (!plausible)
            {
                Resync();
                continue;
            }

            int total = Frame.HeaderLength + length + (hasCrc ? Frame.CrcLength : 0);
            if (_buffer.Count < total) break;

            var raw = new byte[total];
            _buffer.CopyTo(0, raw, 0, total);
            bool crcValid = true;
            if (hasCrc)
            {
                // The buds' own frames cover only the payload; frames like ours cover header and payload.
                int end = Frame.HeaderLength + length;
                ushort stored = BinaryPrimitives.ReadUInt16LittleEndian(raw.AsSpan(end));
                crcValid = stored == Crc16.Compute(raw.AsSpan(Frame.HeaderLength, length))
                    || stored == Crc16.Compute(raw.AsSpan(0, end));
            }
            if (!crcValid)
            {
                CrcErrors++;
                if (RejectBadCrc)
                {
                    Resync();
                    continue;
                }
            }

            _buffer.RemoveRange(0, total);
            frames.Add(new Frame(control, command, sequence, raw[Frame.HeaderLength..(Frame.HeaderLength + length)], crcValid, raw));
        }
        return frames;
    }

    public void Reset() => _buffer.Clear();

    /// <summary>The start byte at index 0 was false; look for the next one.</summary>
    private void Resync()
    {
        _buffer.RemoveAt(0);
        SkippedBytes++;
    }
}
