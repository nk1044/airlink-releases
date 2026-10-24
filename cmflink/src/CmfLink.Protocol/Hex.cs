namespace CmfLink.Protocol;

public static class Hex
{
    /// <summary>"55 60 01 …" style, for logs and the probe.</summary>
    public static string Format(ReadOnlySpan<byte> bytes)
    {
        if (bytes.IsEmpty) return "";
        var chars = new char[bytes.Length * 3 - 1];
        for (int i = 0; i < bytes.Length; i++)
        {
            if (i > 0) chars[i * 3 - 1] = ' ';
            chars[i * 3] = Digit(bytes[i] >> 4);
            chars[i * 3 + 1] = Digit(bytes[i] & 0xF);
        }
        return new string(chars);
    }

    /// <summary>Parses "556001", "55 60 01" or "55-60-01". Throws on odd length or bad digits.</summary>
    public static byte[] Parse(string text)
    {
        var clean = new string(text.Where(Uri.IsHexDigit).ToArray());
        if (clean.Length % 2 != 0) throw new FormatException("odd number of hex digits");
        return Convert.FromHexString(clean);
    }

    private static char Digit(int v) => (char)(v < 10 ? '0' + v : 'A' + v - 10);
}
