import Foundation
import UIKit
import Darwin

struct MiraNativeStatus {
    let backendName: String
    let ptyLifecycle: String
    let fridaLifecycle: String

    @MainActor
    static var current: MiraNativeStatus {
        let backend = String(cString: mira_pty_ios_backend_name())
        let relay = String(cString: mira_ios_relay_status())
        return MiraNativeStatus(
            backendName: backend,
            ptyLifecycle: relay,
            fridaLifecycle: MiraFridaLoader.statusText
        )
    }

    static var installId: String {
        String(cString: mira_ios_relay_install_id())
    }

    @discardableResult
    @MainActor
    static func startRelay(url: String) -> Bool {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        let deviceName = UIDevice.current.name
        let deviceModel = modelName()
        let hardwareModel = hardwareIdentifier()
        let osVersion = UIDevice.current.systemVersion
        let home = NSHomeDirectory()
        return trimmed.withCString { relayCString in
            deviceName.withCString { deviceCString in
                deviceModel.withCString { modelCString in
                    hardwareModel.withCString { hardwareCString in
                        osVersion.withCString { osVersionCString in
                            home.withCString { homeCString in
                                mira_ios_relay_start_with_device_info(
                                    relayCString,
                                    deviceCString,
                                    homeCString,
                                    modelCString,
                                    hardwareCString,
                                    osVersionCString
                                ) == 0
                            }
                        }
                    }
                }
            }
        }
    }

    static func stopRelay() {
        mira_ios_relay_stop()
    }

    @discardableResult
    static func sendControlJSON(_ json: String) -> Bool {
        json.withCString { pointer in
            mira_ios_relay_send_control_json(pointer) == 0
        }
    }

    @MainActor
    private static func modelName() -> String {
        let identifier = hardwareIdentifier()
        if let mapped = modelNames[identifier] { return mapped }

        if ["i386", "x86_64", "arm64"].contains(identifier) {
            let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? ""
            if let mapped = modelNames[simulated] { return "\(mapped) Simulator" }
            return "iOS Simulator"
        }

        return identifier.isEmpty ? UIDevice.current.model : identifier
    }

    private static func hardwareIdentifier() -> String {
        var size: size_t = 0
        guard sysctlbyname("hw.machine", nil, &size, nil, 0) == 0, size > 0 else {
            return ""
        }

        var machine = [CChar](repeating: 0, count: Int(size))
        guard sysctlbyname("hw.machine", &machine, &size, nil, 0) == 0 else {
            return ""
        }
        let buffer = machine.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
        return String(decoding: buffer, as: UTF8.self)
    }

    private static let modelNames: [String: String] = [
        "iPhone8,1": "iPhone 6s",
        "iPhone8,2": "iPhone 6s Plus",
        "iPhone8,4": "iPhone SE",
        "iPhone9,1": "iPhone 7",
        "iPhone9,3": "iPhone 7",
        "iPhone9,2": "iPhone 7 Plus",
        "iPhone9,4": "iPhone 7 Plus",
        "iPhone10,1": "iPhone 8",
        "iPhone10,4": "iPhone 8",
        "iPhone10,2": "iPhone 8 Plus",
        "iPhone10,5": "iPhone 8 Plus",
        "iPhone10,3": "iPhone X",
        "iPhone10,6": "iPhone X",
        "iPhone11,2": "iPhone XS",
        "iPhone11,4": "iPhone XS Max",
        "iPhone11,6": "iPhone XS Max",
        "iPhone11,8": "iPhone XR",
        "iPhone12,1": "iPhone 11",
        "iPhone12,3": "iPhone 11 Pro",
        "iPhone12,5": "iPhone 11 Pro Max",
        "iPhone12,8": "iPhone SE 2",
        "iPhone13,1": "iPhone 12 mini",
        "iPhone13,2": "iPhone 12",
        "iPhone13,3": "iPhone 12 Pro",
        "iPhone13,4": "iPhone 12 Pro Max",
        "iPhone14,4": "iPhone 13 mini",
        "iPhone14,5": "iPhone 13",
        "iPhone14,2": "iPhone 13 Pro",
        "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,6": "iPhone SE 3",
        "iPhone14,7": "iPhone 14",
        "iPhone14,8": "iPhone 14 Plus",
        "iPhone15,2": "iPhone 14 Pro",
        "iPhone15,3": "iPhone 14 Pro Max",
        "iPhone15,4": "iPhone 15",
        "iPhone15,5": "iPhone 15 Plus",
        "iPhone16,1": "iPhone 15 Pro",
        "iPhone16,2": "iPhone 15 Pro Max",
        "iPhone17,3": "iPhone 16",
        "iPhone17,4": "iPhone 16 Plus",
        "iPhone17,1": "iPhone 16 Pro",
        "iPhone17,2": "iPhone 16 Pro Max",
        "iPhone17,5": "iPhone 16e",
    ]
}
