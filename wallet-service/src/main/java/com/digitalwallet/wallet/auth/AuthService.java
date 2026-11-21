package com.digitalwallet.wallet.auth;

import java.util.Locale;
import java.util.Optional;

import com.digitalwallet.wallet.auth.AuthDtos.AuthResponse;
import com.digitalwallet.wallet.auth.AuthDtos.LoginRequest;
import com.digitalwallet.wallet.auth.AuthDtos.RegisterRequest;
import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.user.AppUser;
import com.digitalwallet.wallet.user.Role;
import com.digitalwallet.wallet.user.UserRepository;
import com.digitalwallet.wallet.wallet.WalletService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptService loginAttempts;
    /** Compared against when the email is unknown, so every login costs one BCrypt check. */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, WalletService walletService,
            PasswordEncoder passwordEncoder, TokenService tokenService, LoginAttemptService loginAttempts) {
        this.userRepository = userRepository;
        this.walletService = walletService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginAttempts = loginAttempts;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equaliser-not-a-real-password-1");
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
            throw emailTaken();
        }
        AppUser user;
        try {
            user = userRepository.saveAndFlush(
                    new AppUser(normalizedEmail, passwordEncoder.encode(rawPassword), fullName, role));
        } catch (DataIntegrityViolationException e) {
            // two registrations of the same email at the same moment: the unique key decides
            throw emailTaken();
        }
        walletService.openWallet(user.getId());
        return user;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());
        loginAttempts.checkNotLocked(email);

        Optional<AppUser> user = userRepository.findByEmail(email);
        // always run BCrypt, so the response time does not reveal whether the email exists
        String hash = user.map(AppUser::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            loginAttempts.recordFailure(email);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        loginAttempts.reset(email);
        return tokenService.issue(user.get());
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
    }
}
