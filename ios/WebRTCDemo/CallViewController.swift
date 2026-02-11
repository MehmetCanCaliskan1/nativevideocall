import UIKit
import SocketIO
import WebRTC
import AVFoundation

let SERVER_URL = "http://10.246.26.152:443"

class CallViewController: UIViewController, WebRTCClientDelegate {
    var targetSocketId: String?
    var isCurrentUserHost: Bool = false
    func didReceiveMessage(message: String) {
        
    }
    
    func onDataChannelMessage(message: String) {
        
    }
    //bekleme
    private let waitingOverlay = UIView()
    private let waitingLabel = UILabel()
    private let waitingSpinner = UIActivityIndicatorView(style: .large)

    private func setupWaitingOverlay() {
        waitingOverlay.backgroundColor = UIColor(red: 0.11, green: 0.13, blue: 0.18, alpha: 1.0)
        waitingOverlay.frame = view.bounds
        waitingOverlay.isHidden = true
        
        waitingSpinner.color = .white
        waitingSpinner.startAnimating()
        waitingSpinner.center = waitingOverlay.center
        
        waitingLabel.text = "Host onayı bekleniyor...\nLütfen ayrılmayın."
        waitingLabel.numberOfLines = 0
        waitingLabel.textColor = .white
        waitingLabel.textAlignment = .center
        waitingLabel.font = .systemFont(ofSize: 18, weight: .medium)
        waitingLabel.frame = CGRect(x: 20, y: waitingOverlay.center.y + 40, width: view.bounds.width - 40, height: 100)
        
        waitingOverlay.addSubview(waitingSpinner)
        waitingOverlay.addSubview(waitingLabel)
        view.addSubview(waitingOverlay)
    }
    
    
    
    
    
    
    
    
    
    var roomId: String = ""
    let manager = SocketManager(socketURL: URL(string: SERVER_URL)!, config: [.log(true), .compress])
    var socket: SocketIOClient!
    var webRTCClient: WebRTCClient!
    var useCustomCapturer: Bool = false
    
    // UI State
    private var videoEnabled = true
    private var audioEnabled = true
    private var peersConnected = false
    
    // UI Elements
    private let remoteVideoContainer = UIView()
    private let gradientOverlayTop = UIView()
    private let gradientOverlayBottom = UIView()
    
    // Top Bar
    private let topBar = UIView()
    private let topBarStack = UIStackView()
    private let centerInfoStack = UIStackView()
    private let roomIdLabel = UILabel()
    private let statusLabel = UILabel()
    private let statusContainer = UIView()
    private let connectionDot = UIView()
    private let switchCameraButton = UIButton(type: .system)
    private let spacerView = UIView()
    
    // Local Video
    private let localVideoContainer = UIView()
    private let localMuteIndicator = UIImageView()
    private let hostLabel=UILabel()
    
    // Bottom Controls
    private let bottomControlsContainer = UIView()
    private let primaryControlsContainer = UIView()
    private let glassPanelView = UIView()
    private let primaryControlsStack = UIStackView()
    
  
    
    // Primary Control Containers
    private let muteContainer = UIView()
    private let muteButton = UIButton(type: .system)
    private let muteLabel = UILabel()
    
    private let endContainer = UIView()
    private let endButton = UIButton(type: .system)
    private let endLabel = UILabel()
    
    private let videoContainer = UIView()
    private let videoButton = UIButton(type: .system)
    private let videoLabel = UILabel()
    
    // Dragging
    private var initialLocalVideoFrame: CGRect = .zero
    private var localVideoTopConstraint: NSLayoutConstraint!
    private var localVideoLeadingConstraint: NSLayoutConstraint?
    private var localVideoTrailingConstraint: NSLayoutConstraint?
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        // Hide navigation bar and disable swipe back gesture
        navigationController?.setNavigationBarHidden(true, animated: false)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = false
        
        #if targetEnvironment(simulator)
            useCustomCapturer = false
        #endif
        
        socket = manager.defaultSocket
        
        setupWaitingOverlay()
        
        setupSocketHandlers()
        setupUI()
        
        socket.connect()
    }
    
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
    }
    
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        socket.disconnect()
        webRTCClient?.disconnect()
    }
    
    // MARK: - Socket Setup
    private func setupSocketHandlers() {
        socket.on(clientEvent: .connect) { [weak self] data, ack in
            guard let self = self else { return }
            print("socket connected")
            
            let payload: [String: Any] = [
                "roomId": self.roomId,
                "username": "iOS User"
            ]
            
            // Backend uyumlu 'join-room'
            self.socket.emit("join-room", payload)
            
            self.webRTCClient = WebRTCClient()
            self.webRTCClient.delegate = self
            self.webRTCClient.setup(videoTrack: true, audioTrack: true, customFrameCapturer: self.useCustomCapturer)
            self.setupVideoViews()
        }
        
        socket.on(clientEvent: .disconnect) { data, ack in
            print("socket disconnected")
        }
        // Host iseniz, odaya girmek isteyen biri olduğunda tetiklenir
        socket.on("join-request") { [weak self] data, _ in
            guard let self = self,
                  let payload = data as? [[String: Any]],
                  let requesterId = payload[0]["socketId"] as? String,
                  let requesterName = payload[0]["username"] as? String else { return }
            
            let alert = UIAlertController(
                title: "Katılım İsteği",
                message: "\(requesterName) odaya katılmak istiyor. Onaylıyor musunuz?",
                preferredStyle: .alert
            )
            
            alert.addAction(UIAlertAction(title: "Onayla", style: .default) { _ in
                // Sunucuya onay gönder
                self.socket.emit("handle-join-request", [
                    "decision": "approve",
                    "requesterId": requesterId,
                    "requesterName": requesterName
                ])
            })
            
            alert.addAction(UIAlertAction(title: "Reddet", style: .destructive) { _ in
                // Sunucuya red gönder
                self.socket.emit("handle-join-request", [
                    "decision": "reject",
                    "requesterId": requesterId,
                    "requesterName": requesterName
                ])
            })
            
            self.present(alert, animated: true)
        }
        socket.on("join-rejected") { [weak self] _, _ in
            let alert = UIAlertController(title: "Reddedildi", message: "Odaya giriş isteğiniz Host tarafından reddedildi.", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Tamam", style: .default) { _ in
                self?.navigationController?.popViewController(animated: true)
            })
            self?.present(alert, animated: true)
        }
        // Backend onaylayınca
        socket.on("room-joined") { [weak self] data, ack in
            guard let self = self,
                  let args = data as? [[String: Any]],
                  let firstArg = args.first else { return }
            
            // Bekleme ekranını kapat
            DispatchQueue.main.async {
                self.waitingOverlay.isHidden = true
                let isHost = firstArg["isHost"] as? Bool ?? false
                self.isCurrentUserHost = isHost
                                self.hostLabel.isHidden = false
                                
                                if isHost {
                                    self.hostLabel.text = "HOST"
                                    self.hostLabel.backgroundColor = .systemOrange
                                } else {
                                    self.hostLabel.text = "GUEST"
                                    self.hostLabel.backgroundColor = .systemBlue
                                }
            }

            if let users = firstArg["users"] as? [[String: Any]] {
                // Listedeki diğer kullanıcıyı hedef seç (Kendimiz hariç olan)
                if let peer = users.first(where: { ($0["socketId"] as? String) != self.socket.sid }) {
                    self.targetSocketId = peer["socketId"] as? String
                    
                    // Eğer biz Host değilsek, Offer'ı (bağlantı isteğini) biz başlatırız
                    let isHost = firstArg["isHost"] as? Bool ?? false
                    if !isHost {
                        self.webRTCClient.connect(onSuccess: { offerSDP in
                            self.sendSDP(sessionDescription: offerSDP)
                        })
                    }
                }
            }
        }
        socket.on("webrtc-offer") { [weak self] data, ack in
            guard let payload = data as? [[String: Any]],
                  let fromId = payload[0]["from"] as? String, // Teklifi gönderen kişinin gerçek ID'si
                  let offerData = payload[0]["offer"] as? [String: Any],
                  let sdp = offerData["sdp"] as? String else { return }
            
            print("Offer alındı, gönderen: \(fromId)")
            
            // BURASI KRİTİK: Gelen ID'yi değişkene atıyoruz ki Answer doğru yere gitsin
            self?.targetSocketId = fromId
            
            let offerSDP = RTCSessionDescription(type: .offer, sdp: sdp)
            self?.webRTCClient.receiveOffer(offerSDP: offerSDP, onCreateAnswer: { answerSDP in
                self?.sendSDP(sessionDescription: answerSDP)
            })
        }
        
        socket.on("webrtc-answer") { [weak self] data, ack in
            guard let payload = data as? [[String: Any]],
                  let answerData = payload[0]["answer"] as? [String: Any],
                  let sdp = answerData["sdp"] as? String else { return }
            
            print("Answer alındı")
            
            let answerSDP = RTCSessionDescription(type: .answer, sdp: sdp)
            self?.webRTCClient.receiveAnswer(answerSDP: answerSDP)
        }
        
        socket.on("webrtc-ice") { [weak self] data, ack in
            guard let payload = data as? [[String: Any]],
                  let candidateWrap = payload[0]["candidate"] as? [String: Any],
                  let candidateSdp = candidateWrap["candidate"] as? String,
                  let sdpMLineIndex = candidateWrap["sdpMLineIndex"] as? Int32 else { return }
            
            print("ICE Candidate alındı")
            
            self?.webRTCClient.receiveCandidate(
                candidate: RTCIceCandidate(
                    sdp: candidateSdp,
                    sdpMLineIndex: sdpMLineIndex,
                    sdpMid: candidateWrap["sdpMid"] as? String
                )
            )
        }
        socket.on("user-disconnected") { [weak self] data, _ in
            guard let self = self else { return }
            
            // Ayrılan kişinin ismini al
            var leaverName = "Misafir"
            if let args = data as? [[String: Any]],
               let firstArg = args.first,
               let username = firstArg["username"] as? String {
                leaverName = username
            }
            
            print("\(leaverName) odadan ayrıldı.")
            
            DispatchQueue.main.async {
                
                // Eğer ben HOST DEĞİLSEM
                // Odayı kapat ve çıkış yap.
                if !self.isCurrentUserHost {
                    let alert = UIAlertController(
                        title: "Görüşme Sonlandı",
                        message: "Host odadan ayrıldığı için görüşme sonlandırıldı.",
                        preferredStyle: .alert
                    )
                    
                    alert.addAction(UIAlertAction(title: "Tamam", style: .destructive) { _ in
                        // WebRTC ve Socket bağlantılarını temizle
                        self.socket.disconnect()
                        self.webRTCClient?.disconnect()
                        // Ekranı kapat
                        self.navigationController?.popViewController(animated: true)
                    })
                    
                    self.present(alert, animated: true)
                    return
                }
                
                
                // Eğer ben Host isem ve Guest çıktıysa
                if self.isCurrentUserHost {
                    self.showToast(message: "\(leaverName) odadan ayrıldı.", duration: 4.0)
                }
                
                // Bağlantı UI durumunu güncelle
                self.statusLabel.text = "Disconnected"
                self.connectionDot.isHidden = true
                self.peersConnected = false
                self.connectionDot.layer.removeAllAnimations()
              
            }
        }
        socket.on("waiting-approval") { [weak self] _, _ in
            DispatchQueue.main.async {
                self?.waitingOverlay.isHidden = false
                self?.statusLabel.text = "Waiting for Host..."
            }
        }
    }
    
    // MARK: - UI Setup
    private func setupUI() {
        view.backgroundColor = UIColor(red: 0.11, green: 0.13, blue: 0.18, alpha: 1.0)
        
        setupRemoteVideo()
        setupGradients()
        setupTopBar()
        setupLocalVideo()
        setupBottomControls()
        
        setupConstraints()
    }
    
    private func setupRemoteVideo() {
        remoteVideoContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(remoteVideoContainer)
    }
    
    private func setupGradients() {
        // Top gradient
        gradientOverlayTop.translatesAutoresizingMaskIntoConstraints = false
        let topGradient = CAGradientLayer()
        topGradient.colors = [
            UIColor.black.withAlphaComponent(0.6).cgColor,
            UIColor.clear.cgColor
        ]
        topGradient.locations = [0, 1]
        topGradient.frame = CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: 200)
        gradientOverlayTop.layer.addSublayer(topGradient)
        view.addSubview(gradientOverlayTop)
        
        // Bottom gradient
        gradientOverlayBottom.translatesAutoresizingMaskIntoConstraints = false
        let bottomGradient = CAGradientLayer()
        bottomGradient.colors = [
            UIColor.clear.cgColor,
            UIColor.black.withAlphaComponent(0.6).cgColor
        ]
        bottomGradient.locations = [0, 1]
        bottomGradient.frame = CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: 250)
        gradientOverlayBottom.layer.addSublayer(bottomGradient)
        view.addSubview(gradientOverlayBottom)
    }
    
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        
        if let topGradient = gradientOverlayTop.layer.sublayers?.first as? CAGradientLayer {
            topGradient.frame = gradientOverlayTop.bounds
        }
        if let bottomGradient = gradientOverlayBottom.layer.sublayers?.first as? CAGradientLayer {
            bottomGradient.frame = gradientOverlayBottom.bounds
        }
    }
    
    private func setupTopBar() {
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)
        
        spacerView.translatesAutoresizingMaskIntoConstraints = false
        
        // Room ID label
        roomIdLabel.text = "Room: \(roomId)"
        roomIdLabel.font = UIFont.systemFont(ofSize: 18, weight: .bold)
        roomIdLabel.textColor = .white
        roomIdLabel.textAlignment = .center
        
        // Status container
        statusContainer.translatesAutoresizingMaskIntoConstraints = false
        
        connectionDot.backgroundColor = UIColor(red: 0.13, green: 0.80, blue: 0.47, alpha: 1.0)
        connectionDot.layer.cornerRadius = 4
        connectionDot.isHidden = true
        connectionDot.translatesAutoresizingMaskIntoConstraints = false
        statusContainer.addSubview(connectionDot)
        
        statusLabel.text = "Connecting..."
        statusLabel.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        statusLabel.textColor = UIColor.white.withAlphaComponent(0.8)
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusContainer.addSubview(statusLabel)
        
        NSLayoutConstraint.activate([
            connectionDot.leadingAnchor.constraint(equalTo: statusContainer.leadingAnchor),
            connectionDot.centerYAnchor.constraint(equalTo: statusContainer.centerYAnchor),
            connectionDot.widthAnchor.constraint(equalToConstant: 8),
            connectionDot.heightAnchor.constraint(equalToConstant: 8),
            
            statusLabel.leadingAnchor.constraint(equalTo: connectionDot.trailingAnchor, constant: 8),
            statusLabel.centerYAnchor.constraint(equalTo: statusContainer.centerYAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: statusContainer.trailingAnchor),
            statusLabel.topAnchor.constraint(equalTo: statusContainer.topAnchor),
            statusLabel.bottomAnchor.constraint(equalTo: statusContainer.bottomAnchor)
        ])
        
        // Center info stack
        centerInfoStack.axis = .vertical
        centerInfoStack.alignment = .center
        centerInfoStack.spacing = 4
        centerInfoStack.addArrangedSubview(roomIdLabel)
        centerInfoStack.addArrangedSubview(statusContainer)
        centerInfoStack.translatesAutoresizingMaskIntoConstraints = false
        
        // Switch camera button
        let switchCameraButtonConfig = UIImage.SymbolConfiguration(pointSize: 18, weight: .regular)
        switchCameraButton.setImage(UIImage(systemName: "arrow.triangle.2.circlepath.camera.fill", withConfiguration: switchCameraButtonConfig), for: .normal)
        switchCameraButton.tintColor = .white
        switchCameraButton.backgroundColor = .clear
        switchCameraButton.addTarget(self, action: #selector(switchCameraTapped), for: .touchUpInside)
        switchCameraButton.translatesAutoresizingMaskIntoConstraints = false
        
        // Top bar stack
        topBarStack.axis = .horizontal
        topBarStack.alignment = .center
        topBarStack.distribution = .equalSpacing
        topBarStack.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(topBarStack)
        
        topBarStack.addArrangedSubview(spacerView)
        topBarStack.addArrangedSubview(centerInfoStack)
        topBarStack.addArrangedSubview(switchCameraButton)
        
        NSLayoutConstraint.activate([
            spacerView.widthAnchor.constraint(equalToConstant: 40),
            spacerView.heightAnchor.constraint(equalToConstant: 40)
        ])
    }
    
    private func setupLocalVideo() {
        localVideoContainer.backgroundColor = UIColor(red: 0.26, green: 0.29, blue: 0.33, alpha: 1.0)
        localVideoContainer.layer.cornerRadius = 12
        localVideoContainer.layer.borderWidth = 2
        localVideoContainer.layer.borderColor = UIColor.white.withAlphaComponent(0.2).cgColor
        localVideoContainer.clipsToBounds = true
        localVideoContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(localVideoContainer)
        
        // Add mute indicator
        localMuteIndicator.image = UIImage(systemName: "mic.slash.fill")
        localMuteIndicator.tintColor = .white
        localMuteIndicator.backgroundColor = UIColor.red.withAlphaComponent(0.9)
        localMuteIndicator.layer.cornerRadius = 10
        localMuteIndicator.clipsToBounds = true
        localMuteIndicator.contentMode = .center
        localMuteIndicator.isHidden = true
        localMuteIndicator.translatesAutoresizingMaskIntoConstraints = false
        localVideoContainer.addSubview(localMuteIndicator)
        
                hostLabel.text = ""
                hostLabel.font = .systemFont(ofSize: 10, weight: .bold)
                hostLabel.textColor = .white
                hostLabel.textAlignment = .center
                hostLabel.layer.cornerRadius = 4
                hostLabel.clipsToBounds = true
                hostLabel.isHidden = true
                hostLabel.translatesAutoresizingMaskIntoConstraints = false
                localVideoContainer.addSubview(hostLabel)
        
        
        
        NSLayoutConstraint.activate([
            localMuteIndicator.widthAnchor.constraint(equalToConstant: 20),
            localMuteIndicator.heightAnchor.constraint(equalToConstant: 20),
            localMuteIndicator.trailingAnchor.constraint(equalTo: localVideoContainer.trailingAnchor, constant: -8),
            localMuteIndicator.bottomAnchor.constraint(equalTo: localVideoContainer.bottomAnchor, constant: -8),
            
            
            
            hostLabel.leadingAnchor.constraint(equalTo: localVideoContainer.leadingAnchor, constant: 6),
                        hostLabel.topAnchor.constraint(equalTo: localVideoContainer.topAnchor, constant: 6),
                        
                        hostLabel.widthAnchor.constraint(equalToConstant: 45),
                        
                        hostLabel.heightAnchor.constraint(equalToConstant: 18)
        ])
        
        // Setup dragging
        let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        localVideoContainer.addGestureRecognizer(panGesture)
    }
    
    private func setupBottomControls() {
            bottomControlsContainer.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(bottomControlsContainer)
            
            // --- PRIMARY STACK (Glass Panel) ---
            glassPanelView.backgroundColor = UIColor(red: 0.06, green: 0.1, blue: 0.13, alpha: 0.75)
            glassPanelView.layer.cornerRadius = 24
            glassPanelView.layer.borderWidth = 1
            glassPanelView.layer.borderColor = UIColor.white.withAlphaComponent(0.05).cgColor
            glassPanelView.translatesAutoresizingMaskIntoConstraints = false
            bottomControlsContainer.addSubview(glassPanelView)
            
            // Primary controls stack (Mute, End, Video)
            primaryControlsStack.axis = .horizontal
            primaryControlsStack.distribution = .equalSpacing
            primaryControlsStack.spacing = 40
            primaryControlsStack.translatesAutoresizingMaskIntoConstraints = false
            glassPanelView.addSubview(primaryControlsStack)
            
            // Mute
            setupPrimaryControl(
                container: muteContainer,
                button: muteButton,
                label: muteLabel,
                icon: "mic.fill",
                title: "Mute",
                size: 48,
                action: #selector(muteButtonTapped)
            )
            primaryControlsStack.addArrangedSubview(muteContainer)
            
            // End Call
            setupPrimaryControl(
                container: endContainer,
                button: endButton,
                label: endLabel,
                icon: "phone.down.fill",
                title: "End",
                size: 56,
                backgroundColor: .systemRed,
                action: #selector(endButtonTapped)
            )
            endLabel.font = UIFont.systemFont(ofSize: 10, weight: .bold)
            endLabel.textColor = .white
            primaryControlsStack.addArrangedSubview(endContainer)
            
            // Video Toggle
            setupPrimaryControl(
                container: videoContainer,
                button: videoButton,
                label: videoLabel,
                icon: "video.fill",
                title: "Video",
                size: 48,
                action: #selector(videoButtonTapped)
            )
            primaryControlsStack.addArrangedSubview(videoContainer)
        }
    
    private func setupSecondaryControl(container: UIView, button: UIButton, label: UILabel, icon: String, title: String, action: Selector) {
        container.translatesAutoresizingMaskIntoConstraints = false
        
        let config = UIImage.SymbolConfiguration(pointSize: 18, weight: .regular)
        button.setImage(UIImage(systemName: icon, withConfiguration: config), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor(red: 0.06, green: 0.1, blue: 0.13, alpha: 0.75)
        button.layer.cornerRadius = 28
        button.layer.borderWidth = 1
        button.layer.borderColor = UIColor.white.withAlphaComponent(0.05).cgColor
        button.contentEdgeInsets = UIEdgeInsets(top: 14, left: 14, bottom: 14, right: 14)
        button.addTarget(self, action: action, for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(button)
        
        label.text = title
        label.font = UIFont.systemFont(ofSize: 10, weight: .medium)
        label.textColor = UIColor.white.withAlphaComponent(0.8)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(label)
        
        NSLayoutConstraint.activate([
            button.topAnchor.constraint(equalTo: container.topAnchor),
            button.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            button.widthAnchor.constraint(equalToConstant: 56),
            button.heightAnchor.constraint(equalToConstant: 56),
            
            label.topAnchor.constraint(equalTo: button.bottomAnchor, constant: 8),
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])
    }
    
    private func setupPrimaryControl(container: UIView, button: UIButton, label: UILabel, icon: String, title: String, size: CGFloat, backgroundColor: UIColor = UIColor.white.withAlphaComponent(0.1), action: Selector) {
        container.translatesAutoresizingMaskIntoConstraints = false
        
        let config = UIImage.SymbolConfiguration(pointSize: 18, weight: .regular)
        button.setImage(UIImage(systemName: icon, withConfiguration: config), for: .normal)
        button.tintColor = .white
        button.backgroundColor = backgroundColor
        button.layer.cornerRadius = size / 2
        button.addTarget(self, action: action, for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(button)
        
        label.text = title
        label.font = UIFont.systemFont(ofSize: 10, weight: .medium)
        label.textColor = UIColor.white.withAlphaComponent(0.7)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(label)
        
        NSLayoutConstraint.activate([
            button.topAnchor.constraint(equalTo: container.topAnchor),
            button.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            button.widthAnchor.constraint(equalToConstant: size),
            button.heightAnchor.constraint(equalToConstant: size),
            
            label.topAnchor.constraint(equalTo: button.bottomAnchor, constant: 4),
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])
    }
    private func setupConstraints() {
            NSLayoutConstraint.activate([
                // Remote video (full screen)
                remoteVideoContainer.topAnchor.constraint(equalTo: view.topAnchor),
                remoteVideoContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                remoteVideoContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                remoteVideoContainer.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                
                // Gradient overlays
                gradientOverlayTop.topAnchor.constraint(equalTo: view.topAnchor),
                gradientOverlayTop.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                gradientOverlayTop.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                gradientOverlayTop.heightAnchor.constraint(equalToConstant: 200),
                
                gradientOverlayBottom.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                gradientOverlayBottom.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                gradientOverlayBottom.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                gradientOverlayBottom.heightAnchor.constraint(equalToConstant: 250),
                
                // Top bar
                topBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
                topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
                
                topBarStack.topAnchor.constraint(equalTo: topBar.topAnchor, constant: 16),
                topBarStack.leadingAnchor.constraint(equalTo: topBar.leadingAnchor),
                topBarStack.trailingAnchor.constraint(equalTo: topBar.trailingAnchor),
                topBarStack.bottomAnchor.constraint(equalTo: topBar.bottomAnchor, constant: -16),
                
                // Local video sizes
                localVideoContainer.widthAnchor.constraint(equalToConstant: 112),
                localVideoContainer.heightAnchor.constraint(equalToConstant: 160),
                
                // Bottom controls container
                bottomControlsContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
                bottomControlsContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
                bottomControlsContainer.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
                
                // Glass panel (ARTIK SECONDARY STACK YOK, DOĞRUDAN CONTAINER'A BAĞLI)
                glassPanelView.topAnchor.constraint(equalTo: bottomControlsContainer.topAnchor),
                glassPanelView.centerXAnchor.constraint(equalTo: bottomControlsContainer.centerXAnchor),
                glassPanelView.bottomAnchor.constraint(equalTo: bottomControlsContainer.bottomAnchor),
                glassPanelView.widthAnchor.constraint(equalTo: bottomControlsContainer.widthAnchor, multiplier: 0.95),
                
                // Primary controls stack
                primaryControlsStack.topAnchor.constraint(equalTo: glassPanelView.topAnchor, constant: 32),
                primaryControlsStack.centerXAnchor.constraint(equalTo: glassPanelView.centerXAnchor),
                primaryControlsStack.bottomAnchor.constraint(equalTo: glassPanelView.bottomAnchor, constant: -32)
            ])
            
            // Setup local video positioning constraints separately
            localVideoTopConstraint = localVideoContainer.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 16)
            localVideoTrailingConstraint = localVideoContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16)
            
            localVideoTopConstraint.isActive = true
            localVideoTrailingConstraint?.isActive = true
        }
    
    private func setupVideoViews() {
        guard let webRTCClient = webRTCClient else { return }
        
        // Setup remote video
        let remoteVideoView = webRTCClient.remoteVideoView()
        webRTCClient.setupRemoteViewFrame(frame: view.bounds)
        remoteVideoView.translatesAutoresizingMaskIntoConstraints = false
        remoteVideoContainer.addSubview(remoteVideoView)
        
        NSLayoutConstraint.activate([
            remoteVideoView.topAnchor.constraint(equalTo: remoteVideoContainer.topAnchor),
            remoteVideoView.leadingAnchor.constraint(equalTo: remoteVideoContainer.leadingAnchor),
            remoteVideoView.trailingAnchor.constraint(equalTo: remoteVideoContainer.trailingAnchor),
            remoteVideoView.bottomAnchor.constraint(equalTo: remoteVideoContainer.bottomAnchor)
        ])
        
        // Setup local video
        let localVideoView = webRTCClient.localVideoView()
        webRTCClient.setupLocalViewFrame(frame: CGRect(x: 0, y: 0, width: 112, height: 160))
        localVideoView.translatesAutoresizingMaskIntoConstraints = false
        
        if let rtcVideoView = localVideoView.subviews.last {
            rtcVideoView.layer.cornerRadius = 12
            rtcVideoView.clipsToBounds = true
        }
        
        localVideoContainer.addSubview(localVideoView)
        
        NSLayoutConstraint.activate([
            localVideoView.topAnchor.constraint(equalTo: localVideoContainer.topAnchor),
            localVideoView.leadingAnchor.constraint(equalTo: localVideoContainer.leadingAnchor),
            localVideoView.trailingAnchor.constraint(equalTo: localVideoContainer.trailingAnchor),
            localVideoView.bottomAnchor.constraint(equalTo: localVideoContainer.bottomAnchor)
        ])
        
        localVideoContainer.layoutIfNeeded()
        localVideoContainer.bringSubviewToFront(localMuteIndicator)
    }
    
    // MARK: - Actions
    @objc private func switchCameraTapped() {
        webRTCClient?.switchCamera()
    }
    
    @objc private func muteButtonTapped() {
        webRTCClient?.toggleAudio(enable: !audioEnabled)
        audioEnabled.toggle()
        
        let config = UIImage.SymbolConfiguration(pointSize: 20, weight: .regular)
        muteButton.setImage(UIImage(systemName: audioEnabled ? "mic.fill" : "mic.slash.fill", withConfiguration: config), for: .normal)
        localMuteIndicator.isHidden = audioEnabled
    }
    
    @objc private func videoButtonTapped() {
        webRTCClient?.toggleVideo(enable: !videoEnabled)
        videoEnabled.toggle()
        
        let config = UIImage.SymbolConfiguration(pointSize: 20, weight: .regular)
        videoButton.setImage(UIImage(systemName: videoEnabled ? "video.fill" : "video.slash.fill", withConfiguration: config), for: .normal)
        
        localVideoContainer.backgroundColor = videoEnabled ? .clear : .black
    }
    
    @objc private func endButtonTapped() {
        navigationController?.popViewController(animated: true)
    }
    
    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: view)
        
        switch gesture.state {
        case .began:
            initialLocalVideoFrame = localVideoContainer.frame
            localVideoTrailingConstraint?.isActive = false
            if localVideoLeadingConstraint == nil {
                localVideoLeadingConstraint = localVideoContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor)
            }
            localVideoLeadingConstraint?.constant = initialLocalVideoFrame.minX
            localVideoLeadingConstraint?.isActive = true
            
        case .changed:
            var newX = initialLocalVideoFrame.origin.x + translation.x
            var newY = initialLocalVideoFrame.origin.y + translation.y
            
            let maxX = view.bounds.width - localVideoContainer.bounds.width
            newX = max(0, min(newX, maxX))
            
            let topBarBottom = topBar.frame.maxY
            let bottomControlsTop = bottomControlsContainer.frame.minY - 16
            newY = max(topBarBottom, min(newY, bottomControlsTop - localVideoContainer.bounds.height))
            
            localVideoLeadingConstraint?.constant = newX
            localVideoTopConstraint.constant = newY - topBar.frame.maxY
            
        case .ended:
            snapToEdge()
            
        default:
            break
        }
    }
    
    private func snapToEdge() {
        let centerX = localVideoContainer.frame.midX
        let screenCenter = view.bounds.width / 2
        
        if centerX < screenCenter {
            localVideoLeadingConstraint?.isActive = false
            localVideoTrailingConstraint?.isActive = false
            localVideoLeadingConstraint = localVideoContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16)
            localVideoLeadingConstraint?.isActive = true
        } else {
            localVideoLeadingConstraint?.isActive = false
            localVideoTrailingConstraint?.isActive = false
            localVideoTrailingConstraint = localVideoContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16)
            localVideoTrailingConstraint?.isActive = true
        }
        
        UIView.animate(withDuration: 0.3, delay: 0, options: .curveEaseOut) {
            self.view.layoutIfNeeded()
        }
    }
    
    // MARK: - Helpers
    private func sendSDP(sessionDescription: RTCSessionDescription) {
        // 1. targetSocketId'nin dolu olduğunu kontrol et
        guard let targetId = targetSocketId else {
            print("Hata: targetSocketId nil, sinyal gönderilemedi!")
            return
        }
        
        var type = ""
        if sessionDescription.type == .offer {
            type = "offer"
        } else if sessionDescription.type == .answer {
            type = "answer"
        }
        
        let sdpPayload: [String: Any] = [
            "type": type,
            "sdp": sessionDescription.sdp
        ]
        
        // 2. BURASI KRİTİK: "to" karşısına tırnaksız targetId yazılmalı
        let payload: [String: Any] = [
            "to": targetId,
            type: sdpPayload
        ]
        
        print("Sinyal gönderiliyor: \(self.socket.sid ?? "unknown") -> \(targetId)")
        socket.emit("webrtc-\(type)", payload)
    }

    private func sendCandidate(iceCandidate: RTCIceCandidate) {
        guard let targetId = targetSocketId else { return }
        
        let candidate: [String: Any] = [
            "candidate": iceCandidate.sdp,
            "sdpMid": iceCandidate.sdpMid ?? "",
            "sdpMLineIndex": iceCandidate.sdpMLineIndex
        ]
        
        let payload: [String: Any] = [
            "to": targetId, // Tırnak olmamalı
            "candidate": candidate
        ]
        
        socket.emit("webrtc-ice", payload)
    }
    func showToast(message: String, duration: TimeInterval = 3.0) {
        let toastLabel = UILabel()
        toastLabel.text = message
        toastLabel.textAlignment = .center
        toastLabel.font = UIFont.systemFont(ofSize: 14)
        toastLabel.numberOfLines = 0
        toastLabel.textColor = .white
        toastLabel.backgroundColor = UIColor.darkGray.withAlphaComponent(0.75)
        toastLabel.layer.cornerRadius = 20
        toastLabel.clipsToBounds = true
        
        let maxSize = CGSize(width: view.bounds.width - 40, height: view.bounds.height)
        var expectedSize = toastLabel.sizeThatFits(maxSize)
        expectedSize.width = min(maxSize.width, expectedSize.width)
        expectedSize.height = min(maxSize.height, expectedSize.height)
        
        toastLabel.frame = CGRect(x: 0, y: 0, width: expectedSize.width + 40, height: expectedSize.height + 20)
        toastLabel.center = CGPoint(x: view.center.x, y: view.frame.height - 160)
        
        view.addSubview(toastLabel)
        
        toastLabel.alpha = 0.0
        UIView.animate(withDuration: 0.5, animations: {
            toastLabel.alpha = 1.0
        }) { _ in
            UIView.animate(withDuration: 0.5, delay: duration, options: .curveEaseOut, animations: {
                toastLabel.alpha = 0.0
            }) { _ in
                toastLabel.removeFromSuperview()
            }
        }
    }
}

// MARK: - WebRTC Delegate
extension CallViewController {
    func didGenerateCandidate(iceCandidate: RTCIceCandidate) {
        sendCandidate(iceCandidate: iceCandidate)
    }
    
    func didIceConnectionStateChanged(iceConnectionState: RTCIceConnectionState) {
        print("ICE Connection State: \(iceConnectionState)")
    }
    
    func didConnectWebRTC() {
        DispatchQueue.main.async {
            self.peersConnected = true
            self.statusLabel.text = "Connected"
            self.connectionDot.isHidden = false
            
            let pulseAnimation = CABasicAnimation(keyPath: "opacity")
            pulseAnimation.fromValue = 1.0
            pulseAnimation.toValue = 0.3
            pulseAnimation.duration = 1.0
            pulseAnimation.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            pulseAnimation.autoreverses = true
            pulseAnimation.repeatCount = .infinity
            self.connectionDot.layer.add(pulseAnimation, forKey: "pulse")
        }
    }
    
    func didDisconnectWebRTC() {
        DispatchQueue.main.async {
            self.peersConnected = false
            self.statusLabel.text = "Disconnected"
            self.connectionDot.isHidden = true
            self.connectionDot.layer.removeAllAnimations()
        }
    }
    
    func onDataChannelStateChange(state: RTCDataChannelState) {
    }
    
    func onPeersConnectionStatusChange(connected: Bool) {
    }
}
