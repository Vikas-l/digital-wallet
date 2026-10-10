package com.digitalwallet.wallet.auth;

import java.util.Locale;

import com.digitalwallet.wallet.auth.AuthDtos.AuthResponse;
import com.digitalwallet.wallet.auth.AuthDtos.LoginRequest;
import com.digitalwallet.wallet.auth.AuthDtos.RegisterRequest;
import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.user.AppUser;
import com.digitalwallet.wallet.user.Role;
import com.digitalwallet.wallet.user.UserRepository;
import com.digitalwallet.wallet.wallet.WalletService;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository, WalletService walletService,
            PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.walletService = walletService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        AppUser user = createUser(request.email(), request.password(), request.fullName().trim(), Role.USER);
        return tokenService.issue(user);
    }

    /** Creates the user and opens their wallet in the same transaction. */
    @Transactional
    public AppUser createUser(String email, String rawPassword, String fullName, Role role) {
        String normalizedEmail = normalize(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        AppUser user = userRepository.save(
                new AppUser(normalizedEmail, passwordEncoder.encode(rawPassword), fullName, role));
        walletService.openWallet(user.getId());
        return user;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        AppUser user = userRepository.findByEmail(normalize(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password"));
        return tokenService.issue(user);
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
