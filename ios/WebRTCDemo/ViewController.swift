import UIKit

class ViewController: UIViewController {
    
    private let dimOverlay = UIView()
    private let scrollView = UIScrollView()
    private let contentView = UIView()
    
    private let glassPanelContainer = UIView()
    private let titleLabel = UILabel()
    private let roomIdTextField = UITextField()
    private let joinButton = UIButton(type: .system)
    private let orDividerContainer = UIView()
    private let leftLine = UIView()
    private let orLabel = UILabel()
    private let rightLine = UIView()
    private let randomButton = UIButton(type: .system)
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        navigationController?.setNavigationBarHidden(true, animated: false)
        
        setupUI()
        generateRandomId()
        
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardWillShow(_:)), name: UIResponder.keyboardWillShowNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardWillHide(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
    }
    
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
    }
    
    private func setupUI() {
        view.backgroundColor = .white
        
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.showsVerticalScrollIndicator = false
        view.addSubview(scrollView)
        
        contentView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(contentView)
        
        glassPanelContainer.backgroundColor = .white
        glassPanelContainer.layer.cornerRadius = 32
        glassPanelContainer.layer.borderWidth = 1
        glassPanelContainer.layer.borderColor = UIColor(white: 0.9, alpha: 1.0).cgColor
        
        glassPanelContainer.layer.shadowColor = UIColor.black.cgColor
        glassPanelContainer.layer.shadowOffset = CGSize(width: 0, height: 10)
        glassPanelContainer.layer.shadowRadius = 20
        glassPanelContainer.layer.shadowOpacity = 0.08
        
        glassPanelContainer.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(glassPanelContainer)
        
        titleLabel.text = "Hoş Geldiniz"
        titleLabel.font = UIFont.systemFont(ofSize: 32, weight: .heavy)
        titleLabel.textColor = .black
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        glassPanelContainer.addSubview(titleLabel)
        
        roomIdTextField.backgroundColor = UIColor(red: 0.96, green: 0.96, blue: 0.98, alpha: 1.0)
        roomIdTextField.textColor = .black
        roomIdTextField.font = UIFont.systemFont(ofSize: 22, weight: .semibold)
        roomIdTextField.textAlignment = .center
        roomIdTextField.attributedPlaceholder = NSAttributedString(
            string: "Oda Numarası Girin",
            attributes: [NSAttributedString.Key.foregroundColor: UIColor.gray.withAlphaComponent(0.6)]
        )
        roomIdTextField.keyboardType = .numberPad
        roomIdTextField.layer.cornerRadius = 18
        roomIdTextField.layer.borderWidth = 1
        roomIdTextField.layer.borderColor = UIColor(white: 0.9, alpha: 1.0).cgColor
        roomIdTextField.delegate = self
        roomIdTextField.translatesAutoresizingMaskIntoConstraints = false
        glassPanelContainer.addSubview(roomIdTextField)
        
        joinButton.setTitle("Odaya Katıl", for: .normal)
        joinButton.titleLabel?.font = UIFont.systemFont(ofSize: 20, weight: .bold)
        joinButton.setTitleColor(.white, for: .normal)
        joinButton.backgroundColor = UIColor(red: 0.20, green: 0.45, blue: 1.00, alpha: 1.0)
        joinButton.layer.cornerRadius = 18
        joinButton.layer.shadowColor = UIColor(red: 0.20, green: 0.45, blue: 1.00, alpha: 0.3).cgColor
        joinButton.layer.shadowOffset = CGSize(width: 0, height: 4)
        joinButton.layer.shadowRadius = 8
        joinButton.layer.shadowOpacity = 0.4
        
        joinButton.addTarget(self, action: #selector(joinButtonTapped), for: .touchUpInside)
        joinButton.translatesAutoresizingMaskIntoConstraints = false
        glassPanelContainer.addSubview(joinButton)
        
        orDividerContainer.translatesAutoresizingMaskIntoConstraints = false
        glassPanelContainer.addSubview(orDividerContainer)
        
        leftLine.backgroundColor = UIColor(white: 0.9, alpha: 1.0)
        leftLine.translatesAutoresizingMaskIntoConstraints = false
        orDividerContainer.addSubview(leftLine)
        
        orLabel.text = "veya"
        orLabel.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        orLabel.textColor = .gray
        orLabel.textAlignment = .center
        orLabel.translatesAutoresizingMaskIntoConstraints = false
        orDividerContainer.addSubview(orLabel)
        
        rightLine.backgroundColor = UIColor(white: 0.9, alpha: 1.0)
        rightLine.translatesAutoresizingMaskIntoConstraints = false
        orDividerContainer.addSubview(rightLine)
        
        randomButton.setTitle("Oda Oluştur", for: .normal)
        randomButton.titleLabel?.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        randomButton.setTitleColor(.black, for: .normal)
        randomButton.tintColor = .black
        randomButton.backgroundColor = .white
        randomButton.layer.cornerRadius = 18
        randomButton.layer.borderWidth = 1
        randomButton.layer.borderColor = UIColor(white: 0.85, alpha: 1.0).cgColor
        randomButton.addTarget(self, action: #selector(randomButtonTapped), for: .touchUpInside)
        randomButton.translatesAutoresizingMaskIntoConstraints = false
        glassPanelContainer.addSubview(randomButton)
        
        setupConstraints()
    }
    
    private func setupConstraints() {
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            
            contentView.topAnchor.constraint(equalTo: scrollView.topAnchor),
            contentView.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor),
            contentView.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor),
            contentView.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor),
            contentView.widthAnchor.constraint(equalTo: scrollView.widthAnchor),
            contentView.heightAnchor.constraint(greaterThanOrEqualTo: scrollView.heightAnchor),
            
            glassPanelContainer.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            glassPanelContainer.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            glassPanelContainer.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.90),
            glassPanelContainer.heightAnchor.constraint(greaterThanOrEqualToConstant: 500),
            
            titleLabel.topAnchor.constraint(equalTo: glassPanelContainer.topAnchor, constant: 60),
            titleLabel.leadingAnchor.constraint(equalTo: glassPanelContainer.leadingAnchor, constant: 24),
            titleLabel.trailingAnchor.constraint(equalTo: glassPanelContainer.trailingAnchor, constant: -24),
            
            roomIdTextField.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 50),
            roomIdTextField.leadingAnchor.constraint(equalTo: glassPanelContainer.leadingAnchor, constant: 32),
            roomIdTextField.trailingAnchor.constraint(equalTo: glassPanelContainer.trailingAnchor, constant: -32),
            roomIdTextField.heightAnchor.constraint(equalToConstant: 64),
            
            joinButton.topAnchor.constraint(equalTo: roomIdTextField.bottomAnchor, constant: 24),
            joinButton.leadingAnchor.constraint(equalTo: glassPanelContainer.leadingAnchor, constant: 32),
            joinButton.trailingAnchor.constraint(equalTo: glassPanelContainer.trailingAnchor, constant: -32),
            joinButton.heightAnchor.constraint(equalToConstant: 64),
            
            orDividerContainer.topAnchor.constraint(equalTo: joinButton.bottomAnchor, constant: 32),
            orDividerContainer.leadingAnchor.constraint(equalTo: glassPanelContainer.leadingAnchor, constant: 40),
            orDividerContainer.trailingAnchor.constraint(equalTo: glassPanelContainer.trailingAnchor, constant: -40),
            orDividerContainer.heightAnchor.constraint(equalToConstant: 24),
            
            leftLine.leadingAnchor.constraint(equalTo: orDividerContainer.leadingAnchor),
            leftLine.centerYAnchor.constraint(equalTo: orDividerContainer.centerYAnchor),
            leftLine.heightAnchor.constraint(equalToConstant: 1),
            leftLine.trailingAnchor.constraint(equalTo: orLabel.leadingAnchor, constant: -16),
            
            orLabel.centerXAnchor.constraint(equalTo: orDividerContainer.centerXAnchor),
            orLabel.centerYAnchor.constraint(equalTo: orDividerContainer.centerYAnchor),
            
            rightLine.leadingAnchor.constraint(equalTo: orLabel.trailingAnchor, constant: 16),
            rightLine.trailingAnchor.constraint(equalTo: orDividerContainer.trailingAnchor),
            rightLine.centerYAnchor.constraint(equalTo: orDividerContainer.centerYAnchor),
            rightLine.heightAnchor.constraint(equalToConstant: 1),
            
            randomButton.topAnchor.constraint(equalTo: orDividerContainer.bottomAnchor, constant: 32),
            randomButton.leadingAnchor.constraint(equalTo: glassPanelContainer.leadingAnchor, constant: 32),
            randomButton.trailingAnchor.constraint(equalTo: glassPanelContainer.trailingAnchor, constant: -32),
            randomButton.heightAnchor.constraint(equalToConstant: 64),
            randomButton.bottomAnchor.constraint(equalTo: glassPanelContainer.bottomAnchor, constant: -60),
        ])
    }
    
    private func generateRandomId() {
        let randomId = Int.random(in: 100000...999999)
        roomIdTextField.text = String(randomId)
    }
    
    @objc private func joinButtonTapped() {
        guard let roomId = roomIdTextField.text, !roomId.isEmpty else { return }
        
        roomIdTextField.resignFirstResponder()
        
        let callVC = CallViewController()
        callVC.roomId = roomId
        navigationController?.pushViewController(callVC, animated: true)
    }
    
    @objc private func randomButtonTapped() {
        generateRandomId()
    }
    
    @objc private func keyboardWillShow(_ notification: Notification) {
        guard let keyboardFrame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let keyboardHeight = keyboardFrame.height
        
        scrollView.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: keyboardHeight, right: 0)
        scrollView.scrollIndicatorInsets = scrollView.contentInset
    }
    
    @objc private func keyboardWillHide(_ notification: Notification) {
        scrollView.contentInset = .zero
        scrollView.scrollIndicatorInsets = .zero
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
    }
}

extension ViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }
}
