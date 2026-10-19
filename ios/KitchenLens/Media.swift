import UIKit
import AVFoundation
import Vision
import CoreImage
import CoreImage.CIFilterBuiltins
import ImageIO

/// Folders inside the app's private storage.
enum AppFiles {
    static var support: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static func folder(_ name: String) -> URL {
        let u = support.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static var temp: URL { folder("tmp") }
    static func newTemp(_ ext: String) -> URL { temp.appendingPathComponent(UUID().uuidString + "." + ext) }
}

enum ImageTools {
    static let maxSide: CGFloat = 1280
    static let jpegQuality: CGFloat = 0.78

    /// Shrinks to at most 1280 px (about 150–250 KB, quick to upload on 4G), burns in the date
    /// and time, saves a JPEG. Returns the file and a fingerprint of the scene for duplicate checks.
    /// Heavy work: call it off the main thread (Task.detached) so the screen never freezes.
    static func process(_ image: UIImage, takenAt: Date = Date(), maxSide: CGFloat = ImageTools.maxSide) throws -> (URL, String) {
        let resized = resize(image, maxSide: maxSide)
        let hash = dHash(resized)
        let stamped = stamp(resized, at: takenAt)
        guard let data = stamped.jpegData(compressionQuality: jpegQuality) else { throw AppError("Could not save the photo") }
        let url = AppFiles.newTemp("jpg")
        try data.write(to: url)
        return (url, hash)
    }

    /// Small preview copy (~20 KB, 360 px) uploaded next to each photo for instant thumbnails.
    static func thumbnail(_ photo: URL, maxPixel: CGFloat = 360) -> URL? {
        guard let img = downsample(photo, maxPixel: maxPixel), let data = img.jpegData(compressionQuality: 0.7) else { return nil }
        let out = AppFiles.newTemp("jpg")
        return (try? data.write(to: out)) != nil ? out : nil
    }

    /// Decodes only as many pixels as needed (fast, low memory), using ImageIO.
    static func downsample(_ url: URL, maxPixel: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    private static let previewCache = NSCache<NSString, UIImage>()

    /// Cached small version of a local photo for on-screen thumbnails and previews.
    static func preview(_ url: URL, maxPixel: CGFloat = 300) -> UIImage? {
        let key = "\(url.path)#\(Int(maxPixel))" as NSString
        if let hit = previewCache.object(forKey: key) { return hit }
        guard let img = downsample(url, maxPixel: maxPixel) else { return nil }
        previewCache.setObject(img, forKey: key)
        return img
    }

    static func resize(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let size = image.size
        let scale = min(1, maxSide / max(size.width, size.height))
        let target = CGSize(width: floor(size.width * scale), height: floor(size.height * scale))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))   // also fixes rotation
        }
    }

    /// Date-time bar along the bottom, visible wherever the photo is shown.
    static func stamp(_ image: UIImage, at date: Date) -> UIImage {
        let size = image.size
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            image.draw(at: .zero)
            let fontSize = min(64, max(14, size.width * 0.034))
            let pad = fontSize * 0.5
            let barH = fontSize + pad * 2
            UIColor(white: 0, alpha: 0.59).setFill()
            ctx.fill(CGRect(x: 0, y: size.height - barH, width: size.width, height: barH))
            let text = "KitchenLens · " + Dates.stamp(date)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.monospacedSystemFont(ofSize: fontSize, weight: .bold),
                .foregroundColor: UIColor.white,
            ]
            (text as NSString).draw(at: CGPoint(x: pad, y: size.height - barH + pad * 0.8), withAttributes: attrs)
        }
    }

    /// 64-bit difference hash; near-identical photos differ in only a few bits.
    static func dHash(_ image: UIImage) -> String {
        guard let cg = image.cgImage else { return "0" }
        var pixels = [UInt8](repeating: 0, count: 9 * 8)
        let ok = pixels.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: 9, height: 8, bitsPerComponent: 8, bytesPerRow: 9,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: 9, height: 8))
            return true
        }
        guard ok else { return "0" }
        var hash: UInt64 = 0
        var bit: UInt64 = 0
        for y in 0..<8 {
            for x in 0..<8 {
                if pixels[y * 9 + x] > pixels[y * 9 + x + 1] { hash |= (1 << bit) }
                bit += 1
            }
        }
        return String(hash, radix: 16)
    }

    static func isDuplicate(_ hash: String, recent: [String], maxBits: Int = 5) -> Bool {
        guard let h = UInt64(hash, radix: 16) else { return false }
        return recent.contains { r in UInt64(r, radix: 16).map { ($0 ^ h).nonzeroBitCount <= maxBits } ?? false }
    }
}

/// On-device text recognition (Apple Vision): reads the bill date and supplier GST number.
enum BillReader {
    struct Scan { let date: String?; let gstin: String? }

    static func read(_ url: URL) async -> Scan {
        guard let image = UIImage(contentsOfFile: url.path)?.cgImage else { return Scan(date: nil, gstin: nil) }
        let text: String = await Task.detached(priority: .userInitiated) { () -> String in
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false
            try? VNImageRequestHandler(cgImage: image).perform([request])
            let lines = request.results?.compactMap { $0.topCandidates(1).first?.string } ?? []
            return lines.joined(separator: "\n")
        }.value
        return Scan(date: Dates.pickBillDate(text, now: nowMs()), gstin: Gstin.find(in: text))
    }
}

enum VideoTools {
    static let maxVideoSide: CGFloat = 1280
    static let videoBitRate = 1_500_000   // same as Android: about 4 MB for a 20 s clip

    /// Re-encodes the clip WITHOUT its sound track (recipe talk is never kept) at 720p and
    /// 1.5 Mbit/s, so a 20 s clip is about 4 MB instead of 15–30 MB: much faster to upload
    /// and to play on the customer's phone. Returns the new file and its length in seconds.
    static func removeAudio(_ input: URL) async throws -> (URL, Int) {
        let asset = AVURLAsset(url: input)
        let duration = try await asset.load(.duration)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw AppError("The clip has no video") }
        let seconds = Int(CMTimeGetSeconds(duration).rounded())
        do {
            let url = try await transcode(asset: asset, track: track)
            try? FileManager.default.removeItem(at: input)
            return (url, seconds)
        } catch {
            // Fallback: Apple's standard 720p export (bigger file, but always works)
            let url = try await export(track: track, duration: duration)
            try? FileManager.default.removeItem(at: input)
            return (url, seconds)
        }
    }

    private final class Flag: @unchecked Sendable { var started = false; var done = false }

    private static func transcode(asset: AVAsset, track: AVAssetTrack) async throws -> URL {
        let natural = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let scale = min(1, maxVideoSide / max(natural.width, natural.height))
        let w = Int((natural.width * scale / 2).rounded()) * 2
        let h = Int((natural.height * scale / 2).rounded()) * 2

        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        ])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw AppError("Could not read the clip") }
        reader.add(output)

        let url = AppFiles.newTemp("mp4")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: w, AVVideoHeightKey: h,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: videoBitRate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                AVVideoMaxKeyFrameIntervalKey: 60,
            ],
        ])
        input.transform = transform
        input.expectsMediaDataInRealTime = false
        guard writer.canAdd(input) else { throw AppError("Could not prepare the clip") }
        writer.add(input)

        guard reader.startReading(), writer.startWriting() else {
            throw AppError("Could not prepare the clip: \(writer.error?.localizedDescription ?? reader.error?.localizedDescription ?? "")")
        }
        let flag = Flag()
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            input.requestMediaDataWhenReady(on: DispatchQueue(label: "kitchenlens.video")) {
                while input.isReadyForMoreMediaData && !flag.done {
                    if let buffer = output.copyNextSampleBuffer() {
                        if !flag.started {
                            writer.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(buffer))
                            flag.started = true
                        }
                        if !input.append(buffer) { flag.done = true }
                    } else {
                        flag.done = true
                    }
                    if flag.done {
                        input.markAsFinished()
                        cont.resume()
                        return
                    }
                }
            }
        }
        guard reader.status != .failed, flag.started else {
            writer.cancelWriting()
            throw AppError("Could not read the clip")
        }
        await writer.finishWriting()
        guard writer.status == .completed else {
            throw AppError("Could not save the clip: \(writer.error?.localizedDescription ?? "unknown error")")
        }
        return url
    }

    private static func export(track: AVAssetTrack, duration: CMTime) async throws -> URL {
        let composition = AVMutableComposition()
        guard let out = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw AppError("Could not prepare the clip")
        }
        try out.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: track, at: .zero)
        out.preferredTransform = try await track.load(.preferredTransform)
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPreset1280x720) else {
            throw AppError("Could not prepare the clip")
        }
        let url = AppFiles.newTemp("mp4")
        exporter.outputURL = url
        exporter.outputFileType = .mp4
        exporter.shouldOptimizeForNetworkUse = true
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            exporter.exportAsynchronously { cont.resume() }
        }
        guard exporter.status == .completed else {
            throw AppError("Could not save the clip: \(exporter.error?.localizedDescription ?? "unknown error")")
        }
        return url
    }
}

/// QR seal label for 58 mm thermal printers (384 dots wide), same layout as Android.
enum QrLabel {
    static let message = ["We care your Hygiene;", "scan the barcode for the proof."]

    static func qrImage(_ content: String, size: CGFloat) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(content.utf8)
        filter.correctionLevel = content.count > 300 ? "L" : "M"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func label(url: String, code: String, kitchenName: String, tempText: String) -> UIImage {
        let size = CGSize(width: 384, height: 590)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let center = NSMutableParagraphStyle()
            center.alignment = .center
            func draw(_ s: String, y: CGFloat, font: UIFont) {
                let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.black, .paragraphStyle: center]
                (s as NSString).draw(in: CGRect(x: 4, y: y, width: size.width - 8, height: font.lineHeight + 4), withAttributes: attrs)
            }
            var titleSize: CGFloat = 26
            while titleSize > 14 && message.contains(where: {
                ($0 as NSString).size(withAttributes: [.font: UIFont.boldSystemFont(ofSize: titleSize)]).width > size.width - 16
            }) { titleSize -= 1 }
            draw(message[0], y: 8, font: .boldSystemFont(ofSize: titleSize))
            draw(message[1], y: 40, font: .boldSystemFont(ofSize: titleSize))
            ctx.cgContext.interpolationQuality = .none
            qrImage(url, size: 352)?.draw(in: CGRect(x: 16, y: 80, width: 352, height: 352))
            let spaced = stride(from: 0, to: code.count, by: 4).map { i -> String in
                let s = code.index(code.startIndex, offsetBy: i)
                let e = code.index(s, offsetBy: min(4, code.count - i))
                return String(code[s..<e])
            }.joined(separator: " ")
            draw(spaced, y: 438, font: .monospacedSystemFont(ofSize: 34, weight: .bold))
            draw(String(kitchenName.prefix(28)), y: 484, font: .systemFont(ofSize: 20))
            draw(tempText, y: 512, font: .systemFont(ofSize: 20))
            draw("Seal must be intact on delivery", y: 544, font: .systemFont(ofSize: 20))
        }
    }

    /// Kitchen hygiene badge for Swiggy / Zomato menu photos, Instagram and Google listings.
    /// The QR opens the kitchen's public page with its live 30-day rating.
    static func badge(url: String, kitchenName: String, headline: String, detail: String) -> UIImage {
        let size = CGSize(width: 720, height: 1000)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let green = UIColor(red: 23 / 255, green: 107 / 255, blue: 75 / 255, alpha: 1)
        let ink = UIColor(red: 23 / 255, green: 43 / 255, blue: 36 / 255, alpha: 1)
        let muted = UIColor(red: 98 / 255, green: 115 / 255, blue: 107 / 255, alpha: 1)
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            green.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(x: 30, y: 30, width: size.width - 60, height: size.height - 60), cornerRadius: 36).fill()
            let center = NSMutableParagraphStyle()
            center.alignment = .center
            func draw(_ s: String, y: CGFloat, size fs: CGFloat, bold: Bool, color: UIColor) {
                var f = fs
                while f > 18 && (s as NSString).size(withAttributes: [.font: bold ? UIFont.boldSystemFont(ofSize: f) : UIFont.systemFont(ofSize: f)]).width > size.width - 100 { f -= 1 }
                let font = bold ? UIFont.boldSystemFont(ofSize: f) : UIFont.systemFont(ofSize: f)
                (s as NSString).draw(in: CGRect(x: 50, y: y, width: size.width - 100, height: font.lineHeight + 6),
                                     withAttributes: [.font: font, .foregroundColor: color, .paragraphStyle: center])
            }
            draw("KITCHENLENS HYGIENE RATING", y: 70, size: 30, bold: true, color: muted)
            draw(kitchenName, y: 120, size: 52, bold: true, color: ink)
            draw(headline, y: 195, size: 56, bold: true, color: UIColor(red: 16 / 255, green: 84 / 255, blue: 58 / 255, alpha: 1))
            draw(detail, y: 272, size: 28, bold: false, color: muted)
            ctx.cgContext.interpolationQuality = .none
            qrImage(url, size: 460)?.draw(in: CGRect(x: (size.width - 460) / 2, y: 340, width: 460, height: 460))
            draw("Scan to see our kitchen's hygiene proof", y: 825, size: 30, bold: true, color: ink)
            draw("Rating from recorded hygiene evidence · not an FSSAI rating", y: 880, size: 22, bold: false, color: muted)
        }
    }
}
