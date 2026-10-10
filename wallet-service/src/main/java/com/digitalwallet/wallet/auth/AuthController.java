package com.digitalwallet.wallet.auth;

import com.digitalwallet.wallet.auth.AuthDtos.AuthResponse;
import com.digitalwallet.wallet.auth.AuthDtos.ForgotPasswordRequest;
import com.digitalwallet.wallet.auth.AuthDtos.LoginRequest;
import com.digitalwallet.wallet.auth.AuthDtos.MessageResponse;
import com.digitalwallet.wallet.auth.AuthDtos.RegisterRequest;
import com.digitalwallet.wallet.auth.AuthDtos.ResetPasswordRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final TokenRevocationService tokenRevocationService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, TokenRevocationService tokenRevocationService,
            PasswordResetService passwordResetService) {
        this.authService = authService;
        this.tokenRevocationService = tokenRevocationService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Register a user; a wallet is opened automatically")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange credentials for a JWT access token")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(summary = "Email a 6-digit reset code (same answer whether or not the account exists)")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestCode(request.email());
        return new MessageResponse("If an account exists for that email, a reset code has been sent.");
    }

    @PostMapping("/password/reset")
    @SecurityRequirements
    @Operation(summary = "Set a new password with the emailed code; logs the user out on all devices")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.email(), request.code(), request.newPassword());
        return new MessageResponse("Your password has been changed. Please log in with the new password.");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke the current access token (Redis denylist)")
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        tokenRevocationService.revoke(jwt);
    }
}
