#if canImport(UIKit)
import AVFoundation
import HappyCore
import HappyIcons
import HappyLayout
import UIKit

/// The screen that can load a session's pictures, found up the responder
/// chain from the view that shows one.
@MainActor
protocol ChatAttachmentProviding: AnyObject {
    var chatAttachments: ChatAttachmentImages? { get }
}

/// useAttachmentImage for one session of one account: its pictures,
/// downloaded and decrypted by `load`, decoded off the main thread at no
/// more than FileView draws, and kept in the account's own
/// `AttachmentPictures`, never another account's. Their thumbhashes are
/// `ThumbHashImages`', which the view sets while it is configured.
@MainActor
final class ChatAttachmentImages: NSObject {
    private let pictures: AttachmentPictures
    private let sessionId: String
    private let load: @Sendable (_ ref: String) async throws -> Data
    /// The account's outbox files, where a picture not yet sent is kept.
    private let files: URL?

    init(
        pictures: AttachmentPictures,
        sessionId: String,
        files: URL? = nil,
        load: @escaping @Sendable (_ ref: String) async throws -> Data
    ) {
        self.pictures = pictures
        self.sessionId = sessionId
        self.files = files
        self.load = load
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(memoryWarning), name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    }

    @objc private func memoryWarning() {
        pictures.removeAll()
    }

    func cached(_ ref: String) -> UIImage? {
        pictures.cached(key(ref)) as? UIImage
    }

    /// The picture, or nil when it couldn't be downloaded, decrypted or
    /// read.
    func image(_ ref: String) async -> UIImage? {
        let load = self.load
        let picture = await pictures.picture(key(ref)) {
            guard let data = try? await load(ref), let image = UIImage(data: data) else { return nil }
            let decoded = image.preparingThumbnail(of: FileViewLayout.decodedSize(image.size)) ?? image
            return (picture: decoded as AttachmentPictures.Picture, cost: ChatAttachmentImages.cost(decoded))
        }
        return picture as? UIImage
    }

    private func key(_ ref: String) -> AttachmentPictures.Key {
        AttachmentPictures.Key(sessionId: sessionId, ref: ref)
    }

    // MARK: Outbox

    func cachedLocal(_ localId: String) -> UIImage? {
        pictures.cached(key(UploadProgressBoard.outboxKey(localId))) as? UIImage
    }

    /// A picture still in the outbox, from this device's copy: decoded off
    /// the main thread at no more than FileView draws, a recording by its
    /// first frame. Nil when there is no copy or nothing in it can be drawn.
    func local(_ localId: String) async -> UIImage? {
        guard let files else { return nil }
        let file = files.appendingPathComponent(localId)
        let picture = await pictures.picture(key(UploadProgressBoard.outboxKey(localId))) {
            guard let image = await Self.drawable(file) else { return nil }
            let decoded = image.preparingThumbnail(of: FileViewLayout.decodedSize(image.size)) ?? image
            return (picture: decoded as AttachmentPictures.Picture, cost: ChatAttachmentImages.cost(decoded))
        }
        return picture as? UIImage
    }

    /// The outbox keeps its copies without an extension, so a recording is
    /// told apart only by failing to be a picture.
    private nonisolated static func drawable(_ file: URL) async -> UIImage? {
        if let image = UIImage(contentsOfFile: file.path) { return image }
        let asset = AVURLAsset(url: file, options: [AVURLAssetOverrideMIMETypeKey: "video/quicktime"])
        let frames = AVAssetImageGenerator(asset: asset)
        frames.appliesPreferredTrackTransform = true
        frames.maximumSize = CGSize(width: 3 * FileViewLayout.Metrics.maxImageWidth, height: 3 * FileViewLayout.Metrics.maxImageHeight)
        guard let (frame, _) = try? await frames.image(at: .zero) else { return nil }
        return UIImage(cgImage: frame)
    }

    // MARK: Markdown images

    /// Fetched without cookies or a disk cache, so nothing of the account's
    /// is sent with it and nothing it fetches outlives the account's cache.
    private nonisolated static let web: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }()

    func cachedWebImage(_ url: URL) -> UIImage? {
        pictures.cached(.web(url.absoluteString)) as? UIImage
    }

    /// RenderImageBlock's `<Image source={{ uri }}>`: an http(s) or base64
    /// `data:` picture, decoded off the main thread at no more than it is
    /// drawn; nil when it can't be had.
    func webImage(_ url: URL) async -> UIImage? {
        let picture = await pictures.picture(.web(url.absoluteString)) {
            guard let data = await Self.fetch(url), let image = UIImage(data: data) else { return nil }
            let decoded = image.preparingThumbnail(of: MarkdownLayout.decodedImageSize(image.size)) ?? image
            return (picture: decoded as AttachmentPictures.Picture, cost: ChatAttachmentImages.cost(decoded))
        }
        return picture as? UIImage
    }

    private nonisolated static func fetch(_ url: URL) async -> Data? {
        switch url.scheme?.lowercased() {
        case "http", "https":
            guard let (data, response) = try? await web.data(from: url) else { return nil }
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
            return data
        case "data":
            // data:[<type>][;base64],<payload>
            let string = url.absoluteString
            guard let comma = string.firstIndex(of: ","), string[..<comma].hasSuffix(";base64") else { return nil }
            return Data(base64Encoded: String(string[string.index(after: comma)...]), options: .ignoreUnknownCharacters)
        default:
            return nil
        }
    }

    /// The decoded bitmap's bytes.
    nonisolated static func cost(_ image: UIImage) -> Int {
        if let cgImage = image.cgImage { return cgImage.bytesPerRow * cgImage.height }
        return Int(image.size.width * image.scale * image.size.height * image.scale * 4)
    }
}

/// FileView's picture: the thumbhash from the first frame, then the picture
/// faded in over it (expo-image's `transition`, here the app's `ImageFade`;
/// `contentFit="cover"`), rounded 8. One that couldn't be loaded keeps its
/// thumbhash under an alert badge. It loads once it is on screen, from the
/// screen up its responder chain, which is also where it looks for a
/// picture already kept: it holds none of its own.
///
/// One still in the outbox is this device's copy, dimmed under Telegram's
/// ring, as the agent chat's sending picture is (`ChatMediaView`): the
/// share of its bytes written fills the ring a frame at a time
/// (`UploadRings`), and a failed one shows the ring's retry arrow.
final class ChatAttachmentView: UIView, UploadRingTile {
    private let placeholder = UIImageView()
    private let picture = UIImageView()
    private let badge = UIView()
    private let badgeIcon = UIImageView()
    /// Still going to the server: the picture dimmed under its ring.
    private let dim = UIView()
    private(set) var ring: UploadRingView?
    private var attachment: ChatAttachment?
    private var task: Task<Void, Never>?
    /// The board key this tile shows the ring of, if any.
    private var following: String?

    /// The thumbhash under the picture, for tests.
    var placeholderImage: UIImage? { placeholder.image }
    var pictureImage: UIImage? { picture.image }

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        layer.cornerRadius = FileViewLayout.Metrics.radius
        layer.cornerCurve = .continuous
        isUserInteractionEnabled = false
        for view in [placeholder, picture] {
            view.contentMode = .scaleAspectFill
            view.clipsToBounds = true
            addSubview(view)
        }
        badge.backgroundColor = UIColor(Palette.surfaceHigh)
        badge.layer.cornerRadius = FileViewLayout.Metrics.badgeSize / 2
        badge.isHidden = true
        badgeIcon.contentMode = .center
        badgeIcon.image = .icon(.ionicons("alert-circle-outline"), size: FileViewLayout.Metrics.badgeIconSize)
        badgeIcon.tintColor = UIColor(Palette.textSecondary)
        badge.addSubview(badgeIcon)
        addSubview(badge)
        dim.isUserInteractionEnabled = false
        dim.backgroundColor = UIColor(Palette.uploadDim)
        dim.isHidden = true
        addSubview(dim)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    /// Nil lets go of the picture.
    func apply(_ attachment: ChatAttachment?) {
        guard attachment != self.attachment else { return }
        // The same copy, only how it is sending (or the ref it was given)
        // changed: the picture stays as it is.
        if let attachment, let local = attachment.local, local == self.attachment?.local {
            self.attachment = attachment
            follow(attachment.upload)
            return
        }
        task?.cancel()
        task = nil
        self.attachment = attachment
        // Now, not once it is on screen: its first frame shows it.
        placeholder.image = ThumbHashImages.image(attachment?.thumbhash)
        picture.layer.removeAllAnimations()
        picture.image = nil
        picture.alpha = 1
        badge.isHidden = true
        follow(attachment?.upload)
        loadIfNeeded()
    }

    // MARK: Uploading

    /// Shows `upload`'s ring and asks `UploadRings` for its frames.
    private func follow(_ upload: ChatUpload?) {
        if let following, following != upload?.key { UploadRings.shared.forget(self, key: following) }
        following = upload?.key
        guard let upload else {
            ring?.isHidden = true
            dim.isHidden = true
            return
        }
        UploadRings.shared.show(self, key: upload.key)
        setUpload(UploadRings.shared.phase(upload.key), animated: false)
    }

    /// Frames `UploadRings` moved its ring in, for tests.
    private(set) var ringApplies = 0

    /// Where its bytes are: what `UploadRings` applies, a frame at a time.
    func setUpload(_ phase: UploadPhase?, animated: Bool) {
        guard let upload = attachment?.upload else { return }
        if animated { ringApplies += 1 }
        let ring = self.ring ?? {
            let ring = UploadRingView()
            addSubview(ring)
            self.ring = ring
            setNeedsLayout()
            return ring
        }()
        let state: UploadRingView.State
        if upload.failed { state = .failed }
        else {
            switch phase {
            case nil, .preparing: state = .waiting
            case .uploading(let share): state = .progress(share)
            case .done: state = .done
            }
        }
        ring.set(state, animated: animated)
        if state != .done { dim.isHidden = false; dim.alpha = 1 }
        else if animated, !dim.isHidden {
            UIView.animate(withDuration: 0.25) { self.dim.alpha = 0 } completion: { _ in
                if self.ring?.state == .done { self.dim.isHidden = true }
            }
        } else {
            dim.isHidden = true
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        loadIfNeeded()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        placeholder.frame = bounds
        picture.frame = bounds
        let badgeFrame = FileViewLayout.badge(in: bounds.size)
        badge.frame = badgeFrame
        badgeIcon.frame = badge.bounds
        dim.frame = bounds
        ring?.frame = UploadRingLayout.disc(in: bounds.size)
    }

    private func loadIfNeeded() {
        guard window != nil, let attachment, let images = source() else { return }
        guard task == nil, picture.image == nil, badge.isHidden else { return }
        if let local = attachment.local {
            if let image = images.cachedLocal(local) {
                picture.image = image
                return
            }
            task = Task { @MainActor [weak self] in
                let image = await images.local(local)
                // No copy to draw: the thumbhash stays, with no alarm.
                guard !Task.isCancelled, let self, self.attachment?.local == local, let image else { return }
                self.picture.image = image
                ImageFade.fadeIn(self.picture)
            }
            return
        }
        // Before the first frame it is drawn in, so no fade.
        if let image = images.cached(attachment.ref) {
            picture.image = image
            return
        }
        task = Task { @MainActor [weak self] in
            let image = await images.image(attachment.ref)
            guard !Task.isCancelled, let self, self.attachment == attachment else { return }
            guard let image else {
                // `error && !uri`.
                self.badge.isHidden = false
                return
            }
            self.picture.image = image
            ImageFade.fadeIn(self.picture)
        }
    }

    private func source() -> ChatAttachmentImages? {
        var responder: UIResponder? = self
        while let current = responder {
            if let provider = current as? ChatAttachmentProviding { return provider.chatAttachments }
            responder = current.next
        }
        return nil
    }
}
/// RenderImageBlock's picture: `resizeMode="contain"` in its box, which
/// the plan draws in `surfaceHighest` behind it and rounds 12. Nothing more
/// when it can't be loaded, as React Native's Image shows nothing. It
/// loads once it is on screen, from the screen up its responder chain.
final class ChatWebImageView: UIImageView {
    private var url: URL?
    private var task: Task<Void, Never>?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentMode = .scaleAspectFit
        clipsToBounds = true
        layer.cornerRadius = MarkdownLayout.Metrics.imageRadius
        isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    /// Nil lets go of the picture.
    func apply(_ url: URL?, label: String?) {
        accessibilityLabel = label
        guard url != self.url else { return }
        task?.cancel()
        task = nil
        self.url = url
        image = nil
        loadIfNeeded()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        loadIfNeeded()
    }

    private func loadIfNeeded() {
        guard task == nil, window != nil, let url, image == nil, let images = source() else { return }
        if let cached = images.cachedWebImage(url) {
            image = cached
            return
        }
        task = Task { @MainActor [weak self] in
            let image = await images.webImage(url)
            guard !Task.isCancelled, let self, self.url == url else { return }
            self.image = image
        }
    }

    private func source() -> ChatAttachmentImages? {
        var responder: UIResponder? = self
        while let current = responder {
            if let provider = current as? ChatAttachmentProviding { return provider.chatAttachments }
            responder = current.next
        }
        return nil
    }
}
#endif
