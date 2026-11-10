import Foundation

internal enum NativeRouting {
    static func environment(in profile: Data?) -> ApnsEnvironment? {
        guard let profile,
            let start = profile.range(of: Data("<?xml".utf8)),
            let end = profile.range(of: Data("</plist>".utf8), in: start.lowerBound..<profile.endIndex),
            let plist = try? PropertyListSerialization.propertyList(
                from: profile[start.lowerBound..<end.upperBound], format: nil) as? [String: Any],
            let entitlements = plist["Entitlements"] as? [String: Any]
        else { return nil }
        switch entitlements["aps-environment"] as? String {
        case "development": return .sandbox
        case "production": return .production
        default: return nil
        }
    }

    static func environment(in bundle: Bundle) -> ApnsEnvironment? {
        let profile = bundle.url(forResource: "embedded", withExtension: "mobileprovision")
            .flatMap { try? Data(contentsOf: $0) }
        return environment(in: profile)
    }
}
