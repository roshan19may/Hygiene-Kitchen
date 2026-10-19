import Foundation

/// Free cloud storage for photos and clips in demo mode, using the kitchen's own
/// Cloudinary account (same as the Android app). The QR carries only short IDs.
/// Setup in Cloudinary: Settings > Upload > Upload presets > Add, Signing mode "Unsigned".
final class CloudMedia {
    private let defaults = UserDefaults.standard
    private let prefix = "cloud_media."

    var cloudName: String { defaults.string(forKey: prefix + "cloudName") ?? "" }
    var preset: String { defaults.string(forKey: prefix + "preset") ?? "" }
    var isConfigured: Bool { !cloudName.isEmpty && !preset.isEmpty }

    func saveSettings(cloudName: String, preset: String) {
        defaults.set(cloudName.trimmingCharacters(in: .whitespaces), forKey: prefix + "cloudName")
        defaults.set(preset.trimmingCharacters(in: .whitespaces), forKey: prefix + "preset")
    }

    func forgetAll() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(prefix) { defaults.removeObject(forKey: key) }
    }

    func cachedId(_ localId: String) -> String? { defaults.string(forKey: prefix + "id:" + localId) }

    /// Uploads a file once; later calls return the cached Cloudinary ID.
    func ensureUploaded(localId: String, file: URL, video: Bool) async throws -> String {
        if let id = cachedId(localId) { return id }
        guard FileManager.default.fileExists(atPath: file.path) else {
            throw AppError("A photo or clip is missing from this phone. Please record it again.")
        }
        let id = try await upload(file, video: video, cloud: cloudName, uploadPreset: preset)
        defaults.set(id, forKey: prefix + "id:" + localId)
        return id
    }

    /// Uploads a tiny test image to check the cloud name and preset.
    func testConnection(cloudName: String, preset: String) async throws {
        let url = AppFiles.newTemp("png")
        // 8x8 green PNG
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAFElEQVR4nGPkj/FiwAaYsIoOWgkAkssAxVkMvgAAAAAASUVORK5CYII=")!
        try png.write(to: url)
        _ = try await upload(url, video: false, cloud: cloudName.trimmingCharacters(in: .whitespaces),
                             uploadPreset: preset.trimmingCharacters(in: .whitespaces), contentType: "image/png")
    }

    private func upload(_ file: URL, video: Bool, cloud: String, uploadPreset: String, contentType: String? = nil) async throws -> String {
        guard !cloud.isEmpty, !uploadPreset.isEmpty else { throw AppError("Cloud storage is not set up") }
        let boundary = "----kitchenlens\(nowMs())"
        guard let endpoint = URL(string: "https://api.cloudinary.com/v1_1/\(cloud)/\(video ? "video" : "image")/upload") else {
            throw AppError("Check the cloud name in Kitchen details.")
        }
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!)
        }
        field("upload_preset", uploadPreset)
        field("public_id", "kl" + Self.shortId())
        let type = contentType ?? (video ? "video/mp4" : "image/jpeg")
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(file.lastPathComponent)\"\r\nContent-Type: \(type)\r\n\r\n".data(using: .utf8)!)
        body.append(try Data(contentsOf: file))
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)

        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.timeoutInterval = 120
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.upload(for: req, from: body)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(code) else {
            let msg = (json?["error"] as? [String: Any])?["message"] as? String ?? ""
            if code == 401 || msg.lowercased().contains("preset") {
                throw AppError("Cloud storage rejected the upload preset. Check it exists and is set to Unsigned.")
            }
            if code == 404 { throw AppError("Cloud name not found. Check the cloud name in Kitchen details.") }
            throw AppError("Upload failed (\(code)) \(msg.prefix(120))")
        }
        guard let id = json?["public_id"] as? String, !id.isEmpty else { throw AppError("Upload failed: no file ID returned") }
        return id
    }

    static func shortId() -> String {
        let chars = Array("abcdefghijkmnpqrstuvwxyz23456789")
        return String((0..<8).map { _ in chars.randomElement()! })
    }
}
