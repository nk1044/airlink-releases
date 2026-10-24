namespace CmfLink.Protocol;

/// <summary>CRC-16/MODBUS: reflected polynomial 0xA001, initial value 0xFFFF, no final XOR.</summary>
public static class Crc16
{
    public static ushort Compute(ReadOnlySpan<byte> data)
    {
        int crc = 0xFFFF;
        foreach (byte b in data)
        {
            crc ^= b;
            for (int i = 0; i < 8; i++)
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
        }
        return (ushort)crc;
    }
}
