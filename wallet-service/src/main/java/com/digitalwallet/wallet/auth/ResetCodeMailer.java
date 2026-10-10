package com.digitalwallet.wallet.auth;

import com.digitalwallet.wallet.config.WalletProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Emails the password reset code. Locally, Mailpit catches the mail (http://localhost:8025). */
@Component
public class ResetCodeMailer {

    private static final Logger log = LoggerFactory.getLogger(ResetCodeMailer.class);

    private final JavaMailSender mailSender;
    private final WalletProperties properties;

    public ResetCodeMailer(JavaMailSender mailSender, WalletProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void send(String email, String fullName, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.passwordReset().fromAddress());
        message.setTo(email);
        message.setSubject("Your Digital Wallet password reset code");
        message.setText("""
                Your Digital Wallet password reset code is %s

                Hi %s,

                Enter this code in the app to choose a new password. It expires in %d minutes
                and can only be used once.

                If you did not ask to reset your password, you can ignore this email:
                your password stays the same.
                """.formatted(code, fullName, properties.passwordReset().codeTtl().toMinutes()));
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // not rethrown: the API answers the same either way (no hint whether the account exists)
            log.error("Could not send password reset email: {}", e.getMessage());
        }
    }
}
