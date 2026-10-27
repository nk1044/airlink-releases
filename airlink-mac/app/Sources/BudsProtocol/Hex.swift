/// Hex formatting without Foundation.
public enum Hex {
    private static let digits = Array("0123456789ABCDEF".utf8)

    public static func byte(_ b: UInt8) -> String {
        String(decoding: [digits[Int(b >> 4)], digits[Int(b & 0x0F)]], as: UTF8.self)
    }

    public static func word(_ w: UInt16) -> String {
        "0x" + byte(UInt8(w >> 8)) + byte(UInt8(w & 0xFF))
    }

    public static func string<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        var out: [UInt8] = []
        for b in bytes {
            if !out.isEmpty { out.append(0x20) }
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Parses `"AA 07 00"`, `"aa0700"` or `"0xAA,0x07"` style input.
    public static func parse(_ text: String) -> [UInt8]? {
        var out: [UInt8] = []
        for token in text.lowercased().split(whereSeparator: { " ,:\n\t".contains($0) }) {
            var digits = Substring(token)
            if digits.hasPrefix("0x") { digits = digits.dropFirst(2) }
            guard digits.count % 2 == 0, !digits.isEmpty else { return nil }
            var index = digits.startIndex
            while index < digits.endIndex {
                let next = digits.index(index, offsetBy: 2)
                guard let value = UInt8(digits[index..<next], radix: 16) else { return nil }
                out.append(value)
                index = next
            }
        }
        return out
    }
}
