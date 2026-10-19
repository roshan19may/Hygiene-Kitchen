import SwiftUI
import UIKit
import SafariServices
import AVFoundation

enum Brand {
    static let leaf = Color(red: 0.059, green: 0.361, blue: 0.290)
    static let leafLight = Color(red: 0.867, green: 0.937, blue: 0.910)
    static let turmeric = Color(red: 0.949, green: 0.757, blue: 0.306)
    static let chilli = Color(red: 0.753, green: 0.224, blue: 0.169)
    static let amber = Color(red: 0.851, green: 0.510, blue: 0.169)
    static let ok = Color(red: 0.118, green: 0.557, blue: 0.353)
}

/// Wraps chips onto as many lines as needed.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > width, x > 0 { x = 0; y += rowH + spacing; rowH = 0 }
            x += s.width + spacing
            rowH = max(rowH, s.height)
            maxX = max(maxX, x)
        }
        return CGSize(width: min(maxX, width), height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            rowH = max(rowH, s.height)
        }
    }
}

struct ChipPicker: View {
    let label: String
    let options: [String]
    @Binding var selected: Set<String>
    var multi = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.subheadline.weight(.semibold))
            FlowLayout {
                ForEach(options, id: \.self) { opt in
                    let on = selected.contains(opt)
                    Button {
                        if multi { if on { selected.remove(opt) } else { selected.insert(opt) } } else { selected = [opt] }
                    } label: {
                        HStack(spacing: 4) {
                            if on { Image(systemName: "checkmark") }
                            Text(opt)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(on ? Brand.leafLight : Color(.secondarySystemBackground))
                        .foregroundColor(on ? Brand.leaf : .primary)
                        .overlay(Capsule().stroke(on ? Brand.leaf : Color(.separator), lineWidth: 1))
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

struct ErrorText: View {
    let text: String?
    var body: some View {
        if let t = text { Text(t).font(.footnote).foregroundColor(Brand.chilli) }
    }
}

struct DemoBanner: View {
    var body: some View {
        Text("Demo mode: records are kept on this iPhone. To show photos and clips when customers scan a seal, connect free cloud storage in Kitchen details (shop icon).")
            .font(.footnote)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Brand.turmeric.opacity(0.25))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct Card<Content: View>: View {
    var tint: Color = Color(.secondarySystemGroupedBackground)
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint)
            .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

/// Reset button for the navigation bar: clears what was entered on the screen.
struct ResetButton: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) { Label("Reset", systemImage: "arrow.counterclockwise") }
    }
}

/// Opens a web page inside the app (customer report preview, privacy policy).
struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

/// Share sheet (print a QR label with a thermal-printer app, AirDrop, WhatsApp, …).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Camera (no gallery uploads, so every photo and clip is captured live)

enum CaptureMode { case photo, video }

enum CaptureResult {
    case photo(UIImage)
    case video(URL)
}

struct CameraView: UIViewControllerRepresentable {
    let mode: CaptureMode
    let hint: String
    let onDone: (CaptureResult?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else { return picker }
        picker.sourceType = .camera
        picker.cameraDevice = .rear
        if mode == .video {
            picker.mediaTypes = ["public.movie"]
            picker.cameraCaptureMode = .video
            picker.videoMaximumDuration = 20
            picker.videoQuality = .typeHigh   // re-encoded to 720p at 1.5 Mbit/s after recording (VideoTools)
        } else {
            picker.mediaTypes = ["public.image"]
            picker.cameraCaptureMode = .photo
        }
        picker.cameraOverlayView = context.coordinator.overlay(hint: hint, bounds: UIScreen.main.bounds)
        return picker
    }

    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onDone: (CaptureResult?) -> Void
        private var timer: Timer?
        init(onDone: @escaping (CaptureResult?) -> Void) { self.onDone = onDone }
        deinit { timer?.invalidate() }

        /// Live date-time and the hint over the camera (does not block the camera controls).
        func overlay(hint: String, bounds: CGRect) -> UIView {
            let view = UIView(frame: bounds)
            view.isUserInteractionEnabled = false
            let clock = UILabel(frame: CGRect(x: 16, y: 110, width: bounds.width - 32, height: 30))
            clock.font = .monospacedSystemFont(ofSize: 15, weight: .bold)
            clock.textColor = .white
            clock.backgroundColor = UIColor(white: 0, alpha: 0.6)
            clock.layer.cornerRadius = 8
            clock.clipsToBounds = true
            clock.textAlignment = .center
            let tip = UILabel(frame: CGRect(x: 16, y: 146, width: bounds.width - 32, height: 64))
            tip.font = .systemFont(ofSize: 13)
            tip.textColor = .white
            tip.numberOfLines = 3
            tip.backgroundColor = UIColor(white: 0, alpha: 0.45)
            tip.layer.cornerRadius = 8
            tip.clipsToBounds = true
            tip.textAlignment = .center
            tip.text = hint
            view.addSubview(clock)
            view.addSubview(tip)
            let tick = { clock.text = Dates.stamp(Date()) }
            tick()
            timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in tick() }
            return view
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            timer?.invalidate()
            if let url = info[.mediaURL] as? URL {
                // Move out of the picker's temporary location before it is cleaned up
                let dest = AppFiles.newTemp(url.pathExtension.isEmpty ? "mov" : url.pathExtension)
                try? FileManager.default.copyItem(at: url, to: dest)
                onDone(.video(dest))
            } else if let image = info[.originalImage] as? UIImage {
                onDone(.photo(image))
            } else {
                onDone(nil)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            timer?.invalidate()
            onDone(nil)
        }
    }
}

extension View {
    /// Full-screen camera; the result arrives in `onDone` (nil when cancelled).
    func camera(isPresented: Binding<Bool>, mode: CaptureMode, hint: String, onDone: @escaping (CaptureResult?) -> Void) -> some View {
        fullScreenCover(isPresented: isPresented) {
            Group {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    CameraView(mode: mode, hint: hint) { r in isPresented.wrappedValue = false; onDone(r) }
                        .ignoresSafeArea()
                } else {
                    VStack(spacing: 16) {
                        Text("This device has no camera. Use an iPhone to record hygiene proof.").multilineTextAlignment(.center)
                        Button("Close") { isPresented.wrappedValue = false; onDone(nil) }
                    }.padding()
                }
            }
        }
    }
}

/// Photos taken here: tap a thumbnail to remove it.
struct Thumb: View {
    let url: URL
    var onRemove: (() -> Void)? = nil
    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Small cached copy: decoding the full photo on every redraw made screens slow
            if let img = ImageTools.preview(url, maxPixel: 252) {
                Image(uiImage: img).resizable().scaledToFill().frame(width: 84, height: 84).clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark.circle.fill").font(.title3).foregroundStyle(.white, .black.opacity(0.6))
                }.padding(3)
            }
        }
    }
}
