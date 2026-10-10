package com.digitalwallet.wallet.auth;

import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.user.Role;
import com.digitalwallet.wallet.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** Creates the bootstrap admin (who manages reward rules) on first start. */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final WalletProperties properties;
    private final UserRepository userRepository;
    private final AuthService authService;

    public AdminSeeder(WalletProperties properties, UserRepository userRepository, AuthService authService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = AuthService.normalize(properties.admin().email());
        if (userRepository.existsByEmail(email)) {
            return;
        }
        try {
            authService.createUser(email, properties.admin().password(), "Digital Wallet Admin", Role.ADMIN);
            log.info("Bootstrap admin {} created", email);
        } catch (ApiException | DataIntegrityViolationException e) {
            // another instance created it at the same moment
            log.info("Bootstrap admin {} already exists", email);
        }
    }
}
