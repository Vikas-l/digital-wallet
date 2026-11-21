package com.digitalwallet.wallet.auth;

import java.time.Duration;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the PUBLIC half of the token signing key (JSON Web Key Set, RFC 7517).
 * rewards-service (and any future service or API gateway) verifies tokens with it.
 */
@RestController
@Tag(name = "Authentication")
public class JwksController {

    private final Map<String, Object> publicJwks;

    public JwksController(RSAKey jwtSigningKey) {
        // toPublicJWK() drops every private parameter: nothing secret can leak here
        this.publicJwks = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    @SecurityRequirements
    @Operation(summary = "Public keys for verifying access tokens (JWKS)")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(publicJwks);
    }
}
