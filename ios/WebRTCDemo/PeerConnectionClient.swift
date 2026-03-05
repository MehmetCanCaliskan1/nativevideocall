import Foundation
import WebRTC
import AVFoundation

protocol WebRTCClientDelegate: AnyObject {
    func didGenerateCandidate(iceCandidate: RTCIceCandidate)
    func didIceConnectionStateChanged(iceConnectionState: RTCIceConnectionState)
    func didConnectWebRTC()
    func didDisconnectWebRTC()
    func onPeersConnectionStatusChange(connected: Bool)
    func didReceiveRemoteStream(stream: RTCMediaStream)
}

class WebRTCClient: NSObject, RTCPeerConnectionDelegate, RTCVideoViewDelegate {
    
    // MARK: - Properties
    private var peerConnectionFactory: RTCPeerConnectionFactory!
    
    // SFU modunda iki ayrı PeerConnection
    private var sendPC: RTCPeerConnection?
    private var recvPC: RTCPeerConnection?
    
    private var videoCapturer: RTCVideoCapturer!
    private var localVideoTrack: RTCVideoTrack!
    private var localAudioTrack: RTCAudioTrack!
    private var localRenderView: RTCMTLVideoView?
    private var localView: UIView!
    private var remoteRenderView: RTCMTLVideoView?
    private var remoteView: UIView!
    private var remoteStream: RTCMediaStream?
    
    private var channels: (video: Bool, audio: Bool) = (false, false)
    private var useFrontCamera = true
    private var videoSource: RTCVideoSource?
    private var isSwitchingCamera = false
    
    weak var delegate: WebRTCClientDelegate?
    public private(set) var isConnected: Bool = false
    
    // Mediasoup state
    private var routerRtpCapabilities: [String: Any]?
    private var sendTransportParams: [String: Any]?
    private var recvTransportParams: [String: Any]?
    
    // MARK: - Init & Deinit
    override init() {
        super.init()
        print("WebRTC Client initialize")
    }
    
    deinit {
        print("WebRTC Client Deinit")
        self.peerConnectionFactory = nil
        self.sendPC = nil
        self.recvPC = nil
    }
    
    // MARK: - View Accessors
    func localVideoView() -> UIView {
        return localView
    }
    
    func remoteVideoView() -> UIView {
        return remoteView
    }
    
    // MARK: - Setup
    func setup(videoTrack: Bool, audioTrack: Bool, customFrameCapturer: Bool) {
        print("WebRTC Client Setup")
        self.channels.video = videoTrack
        self.channels.audio = audioTrack
        
        let videoEncoderFactory = RTCDefaultVideoEncoderFactory()
        let videoDecoderFactory = RTCDefaultVideoDecoderFactory()
        
        self.peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: videoEncoderFactory,
            decoderFactory: videoDecoderFactory
        )
        
        setupView()
        setupLocalTracks()
        
        if self.channels.video {
            startCaptureLocalVideo(
                cameraPositon: .front,
                videoWidth: 640,
                videoHeight: 640 * 16 / 9,
                videoFps: 30
            )
            self.localVideoTrack?.add(self.localRenderView!)
        }
    }
    
    func setupLocalViewFrame(frame: CGRect) {
        localView.frame = frame
        localRenderView?.frame = localView.frame
    }
    
    func setupRemoteViewFrame(frame: CGRect) {
        remoteView.frame = frame
        remoteRenderView?.frame = remoteView.frame
    }
    
    // MARK: - SFU Connect Flow
    
    /// Mediasoup SFU akışını başlatır
    /// Sunucudan gelen transport parametreleriyle send ve recv PC oluşturur
    func startSFUConnection(
        sendTransportParams: [String: Any],
        recvTransportParams: [String: Any],
        routerRtpCapabilities: [String: Any]
    ) {
        self.routerRtpCapabilities = routerRtpCapabilities
        self.sendTransportParams = sendTransportParams
        self.recvTransportParams = recvTransportParams
        
        // Send PeerConnection oluştur
        createSendPeerConnection()
    }
    
    private func createSendPeerConnection() {
        sendPC = setupPeerConnection()
        sendPC?.delegate = self
        
        if self.channels.video {
            sendPC?.add(localVideoTrack, streamIds: ["stream0"])
        }
        if self.channels.audio {
            sendPC?.add(localAudioTrack, streamIds: ["stream0"])
        }
        
        // Offer oluştur
        sendPC?.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { [weak self] sdp, err in
            guard let self = self, let offerSDP = sdp else {
                print("Send offer oluşturulamadı: \(err?.localizedDescription ?? "unknown")")
                return
            }
            
            self.sendPC?.setLocalDescription(offerSDP) { err in
                if let error = err {
                    print("Send local SDP ayarlanamadı: \(error)")
                    return
                }
                
                // Notify delegate that send PC is ready with local SDP
                print("Send PeerConnection offer oluşturuldu")
            }
        }
    }
    
    /// Consumer için recv PeerConnection oluşturur
    func createRecvPeerConnection() {
        if recvPC != nil { return }
        
        recvPC = setupPeerConnection()
        recvPC?.delegate = self
        print("Recv PeerConnection oluşturuldu")
    }
    
    /// Offer SDP'sini döner (send transport bağlantısı için)
    func getSendLocalSDP() -> RTCSessionDescription? {
        return sendPC?.localDescription
    }
        
    /// Sunucudan gelen answer SDP'sini send PC'ye ayarlar
    func setSendRemoteDescription(_ sdp: RTCSessionDescription, completion: @escaping (Error?) -> Void) {
        sendPC?.setRemoteDescription(sdp, completionHandler: completion)
    }
    
    /// Send PC'deki sender'ların RTP parametrelerini döner
    func getSendRtpParameters(kind: String) -> [String: Any]? {
        guard let senders = sendPC?.senders else { return nil }
        guard let sender = senders.first(where: { $0.track?.kind == kind }) else { return nil }
        
        let params = sender.parameters
        
        var codecs: [[String: Any]] = []
        for codec in params.codecs {
            var codecDict: [String: Any] = [
                "mimeType": codec.name,
                "clockRate": codec.clockRate,
                "payloadType": codec.payloadType
            ]
            if let channels = codec.numChannels {
                codecDict["channels"] = channels
            }
            codecDict["parameters"] = codec.parameters
            codecs.append(codecDict)
        }
        
        var encodings: [[String: Any]] = []
        for encoding in params.encodings {
            var encDict: [String: Any] = [:]
            if let ssrc = encoding.ssrc {
                encDict["ssrc"] = ssrc
            }
            encodings.append(encDict)
        }
        
        var headerExtensions: [[String: Any]] = []
        for ext in params.headerExtensions {
            headerExtensions.append([
                "uri": ext.uri,
                "id": ext.id
            ])
        }
        
        return [
            "codecs": codecs,
            "encodings": encodings,
            "headerExtensions": headerExtensions
        ]
    }
    
    func disconnect() {
        sendPC?.close()
        sendPC = nil
        recvPC?.close()
        recvPC = nil
    }
    
    // MARK: - Private Setup Methods
    private func setupPeerConnection() -> RTCPeerConnection? {
        let rtcConf = RTCConfiguration()
        rtcConf.iceServers = [
            RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"]),
            RTCIceServer(
                urlStrings: ["turns:global.relay.metered.ca:443?transport=tcp"],
                username: "cbed5ed13e67a0e746979a5b",
                credential: "Cy6b78fOFK5r8GZ5"
            ),
            RTCIceServer(
                urlStrings: ["turn:global.relay.metered.ca:80?transport=tcp"],
                username: "cbed5ed13e67a0e746979a5b",
                credential: "Cy6b78fOFK5r8GZ5"
            ),
            RTCIceServer(
                urlStrings: ["turn:global.relay.metered.ca:80"],
                username: "cbed5ed13e67a0e746979a5b",
                credential: "Cy6b78fOFK5r8GZ5"
            )
        ]
        
        rtcConf.sdpSemantics = .unifiedPlan
        rtcConf.bundlePolicy = .maxBundle
        rtcConf.rtcpMuxPolicy = .require
        rtcConf.tcpCandidatePolicy = .enabled
        rtcConf.iceTransportPolicy = .all
        
        let mediaConstraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: ["DtlsSrtpKeyAgreement": "true"]
        )
        
        guard let pc = self.peerConnectionFactory.peerConnection(
            with: rtcConf,
            constraints: mediaConstraints,
            delegate: self
        ) else {
            print("❌ HATA: PeerConnection oluşturulamadı.")
            return nil
        }
        
        return pc
    }
    
    private func setupView() {
        localRenderView = RTCMTLVideoView()
        localRenderView!.delegate = self
        localRenderView!.videoContentMode = .scaleAspectFill
        localView = UIView()
        localView.addSubview(localRenderView!)
        
        remoteRenderView = RTCMTLVideoView()
        remoteRenderView?.delegate = self
        remoteRenderView!.videoContentMode = .scaleAspectFill
        remoteView = UIView()
        remoteView.addSubview(remoteRenderView!)
    }
    
    private func setupLocalTracks() {
        if self.channels.video {
            self.localVideoTrack = createVideoTrack()
        }
        if self.channels.audio {
            self.localAudioTrack = createAudioTrack()
        }
    }
    
    private func createAudioTrack() -> RTCAudioTrack {
        let audioConstrains = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let audioSource = self.peerConnectionFactory.audioSource(with: audioConstrains)
        let audioTrack = self.peerConnectionFactory.audioTrack(with: audioSource, trackId: "audio0")
        return audioTrack
    }
    
    private func createVideoTrack() -> RTCVideoTrack {
        let source = self.peerConnectionFactory.videoSource()
        self.videoSource = source
        
        #if targetEnvironment(simulator)
            print("Running on simulator: Using File Capturer")
            self.videoCapturer = RTCFileVideoCapturer(delegate: source)
        #else
            print("Running on device: Using Camera Capturer")
            self.videoCapturer = RTCCameraVideoCapturer(delegate: source)
        #endif
        
        return self.peerConnectionFactory.videoTrack(with: source, trackId: "video0")
    }
    
    private func startCaptureLocalVideo(cameraPositon: AVCaptureDevice.Position, videoWidth: Int, videoHeight: Int?, videoFps: Int) {
        if let capturer = self.videoCapturer as? RTCCameraVideoCapturer {
            var targetDevice: AVCaptureDevice?
            var targetFormat: AVCaptureDevice.Format?
            
            let devices = RTCCameraVideoCapturer.captureDevices()
            devices.forEach { (device) in
                if device.position == cameraPositon {
                    targetDevice = device
                }
            }
            
            guard let device = targetDevice else { return }
            
            let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
            formats.forEach { (format) in
                for _ in format.videoSupportedFrameRateRanges {
                    let description = format.formatDescription as CMFormatDescription
                    let dimensions = CMVideoFormatDescriptionGetDimensions(description)
                    
                    if dimensions.width == videoWidth && dimensions.height == videoHeight ?? 0 {
                        targetFormat = format
                    } else if dimensions.width == videoWidth {
                        targetFormat = format
                    }
                }
            }
            
            if let format = targetFormat {
                capturer.startCapture(with: device, format: format, fps: videoFps)
            }
            
        } else if let capturer = self.videoCapturer as? RTCFileVideoCapturer {
            if Bundle.main.path(forResource: "sample", ofType: "mp4") != nil {
                capturer.startCapturing(fromFileNamed: "sample.mp4") { err in
                    print(err as Any)
                }
            } else {
                print("Simulator video file (sample.mp4) not found in bundle.")
            }
        }
    }
    
    // MARK: - Connection State Handlers
    private func onConnected() {
        self.isConnected = true
        DispatchQueue.main.async {
            self.remoteRenderView?.isHidden = false
            self.delegate?.didConnectWebRTC()
        }
    }
    
    private func onDisConnected() {
        self.isConnected = false
        DispatchQueue.main.async {
            self.sendPC?.close()
            self.sendPC = nil
            self.recvPC?.close()
            self.recvPC = nil
            self.remoteRenderView?.isHidden = true
            self.delegate?.didDisconnectWebRTC()
        }
    }
}

// MARK: - RTCPeerConnectionDelegate
extension WebRTCClient {
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        switch newState {
        case .connected, .completed:
            if !self.isConnected {
                self.onConnected()
                self.delegate?.onPeersConnectionStatusChange(connected: true)
            }
        default:
            if self.isConnected && (newState == .disconnected || newState == .failed || newState == .closed) {
                self.onDisConnected()
                delegate?.onPeersConnectionStatusChange(connected: false)
            }
        }
        
        DispatchQueue.main.async {
            self.delegate?.didIceConnectionStateChanged(iceConnectionState: newState)
        }
    }
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        // SFU modunda ICE candidate'ler sunucu transport'ları tarafından yönetilir
        self.delegate?.didGenerateCandidate(iceCandidate: candidate)
    }
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        print("Did add remote stream")
        self.remoteStream = stream
        
        if let track = stream.videoTracks.first {
            print("Remote video track found")
            track.add(remoteRenderView!)
        }
        
        if let audioTrack = stream.audioTracks.first {
            print("Remote audio track found")
            audioTrack.source.volume = 10
        }
        
        // Notify delegate about remote stream
        delegate?.didReceiveRemoteStream(stream: stream)
    }
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        print("Did remove remote stream")
    }
    
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        print("Data channel opened but ignored")
    }
}

// MARK: - RTCVideoViewDelegate
extension WebRTCClient {
    func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        let isLandscape = size.width > size.height
        var renderView: RTCMTLVideoView?
        var parentView: UIView?
        
        if videoView.isEqual(localRenderView) {
            renderView = localRenderView
            parentView = localView
        } else if videoView.isEqual(remoteRenderView!) {
            renderView = remoteRenderView
            parentView = remoteView
        }
        
        guard let _renderView = renderView, let _parentView = parentView else { return }
        
        if isLandscape {
            let ratio = size.height / size.width
            _renderView.frame = CGRect(x: 0, y: 0, width: _parentView.frame.width, height: _parentView.frame.width * ratio)
            _renderView.center.y = _parentView.frame.height / 2
        } else {
            let ratio = size.width / size.height
            _renderView.frame = CGRect(x: 0, y: 0, width: _parentView.frame.height * ratio, height: _parentView.frame.height)
            _renderView.center.x = _parentView.frame.width / 2
        }
    }
}

// MARK: - Public Control Methods
extension WebRTCClient {
    func toggleVideo(enable: Bool) {
        localVideoTrack.isEnabled = enable
    }
    
    func toggleAudio(enable: Bool) {
        localAudioTrack.isEnabled = enable
    }
    
    func switchCamera() {
        guard !isSwitchingCamera else { return }
        guard let capturer = self.videoCapturer as? RTCCameraVideoCapturer else { return }
        
        isSwitchingCamera = true
        capturer.stopCapture { [weak self] in
            guard let self = self else { return }
            self.useFrontCamera.toggle()
            let newPosition: AVCaptureDevice.Position = self.useFrontCamera ? .front : .back
            
            self.startCaptureLocalVideo(
                cameraPositon: newPosition,
                videoWidth: 640,
                videoHeight: 640 * 16 / 9,
                videoFps: 30
            )
            self.isSwitchingCamera = false
        }
    }
}
