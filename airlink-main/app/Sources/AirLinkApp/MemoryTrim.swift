import Darwin

/// Returns freed heap pages to the system. Called after launch, when the panel closes and when
/// the buds go away, so the idle footprint is what is live rather than the launch peak.
enum MemoryTrim {
    static func now() {
        malloc_zone_pressure_relief(nil, 0)
    }
}
