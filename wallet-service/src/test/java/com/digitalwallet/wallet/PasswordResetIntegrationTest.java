package com.digitalwallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    private static final Pattern CODE = Pattern.compile("reset code is (\\d{6})");

    @Autowired
    TestRestTemplate rest;

    private final RestTemplate mailpit = new RestTemplate();

    @Test
    void forgottenPasswordCanBeResetWithEmailedCode() throws Exception {
        String email = uniqueEmail();
        String oldToken = register(email, "OldPass123");
        Thread.sleep(1100); // tokens carry whole seconds; make sure the old token is strictly older than the reset

        assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String code = awaitCode(email);

        ResponseEntity<JsonNode> reset = reset(email, code, "NewPass456");
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

        // logged out everywhere: the token from before the reset no longer works
        assertThat(rest.exchange("/api/wallets/me", HttpMethod.GET, new HttpEntity<>(bearer(oldToken)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // the old password is gone, the new one works
        assertThat(login(email, "OldPass123").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> login = login(email, "NewPass456");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/wallets/me", HttpMethod.GET,
                new HttpEntity<>(bearer(login.getBody().get("accessToken").asText())), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // a code works only once
        assertError(reset(email, code, "Another789"), HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE");
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        String known = uniqueEmail();
        register(known, "Secret123");
        String unknown = uniqueEmail();

        ResponseEntity<JsonNode> forKnown = forgot(known);
        ResponseEntity<JsonNode> forUnknown = forgot(unknown);

        // identical answers, so the endpoint cannot be used to find out who has an account
        assertThat(forUnknown.getStatusCode()).isEqualTo(forKnown.getStatusCode());
        assertThat(forUnknown.getBody()).isEqualTo(forKnown.getBody());
        awaitCode(known);
        assertThat(messagesTo(unknown)).isZero();
        assertError(reset(unknown, "123456", "NewPass456"), HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE");
    }

    @Test
    void codeIsDestroyedAfterTooManyWrongGuesses() {
        String email = uniqueEmail();
        register(email, "Secret123");
        forgot(email);
        String code = awaitCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertError(reset(email, wrong, "NewPass456"), HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE");
        }
        assertError(reset(email, wrong, "NewPass456"), HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS");
        // even the right code is now useless: a new one must be requested
        assertError(reset(email, code, "NewPass456"), HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE");
        assertThat(login(email, "Secret123").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resetRequestsAreRateLimitedPerEmail() {
        String email = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertError(forgot(email), HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
    }

    @Test
    void sameEmailCannotRegisterTwiceWhateverTheLetterCase() {
        String email = uniqueEmail();
        register(email, "Secret123");

        ResponseEntity<JsonNode> again = rest.postForEntity("/api/auth/register",
                Map.of("email", email.toUpperCase(), "password", "Secret123", "fullName", "Copy"),
                JsonNode.class);

        assertError(again, HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void weakNewPasswordIsRejected() {
        assertError(reset(uniqueEmail(), "123456", "short"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(reset(uniqueEmail(), "12ab", "NewPass456"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    // ---------------------------------------------------------------- helpers

    private String register(String email, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register",
                Map.of("email", email, "password", password, "fullName", "Reset Tester"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> login(String email, String password) {
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", password), JsonNode.class);
    }

    private ResponseEntity<JsonNode> forgot(String email) {
        return rest.postForEntity("/api/auth/password/forgot", Map.of("email", email), JsonNode.class);
    }

    private ResponseEntity<JsonNode> reset(String email, String code, String newPassword) {
        return rest.postForEntity("/api/auth/password/reset",
                Map.of("email", email, "code", code, "newPassword", newPassword), JsonNode.class);
    }

    /** Reads the code from the email in Mailpit, like a user opening their inbox. */
    private String awaitCode(String email) {
        String[] code = new String[1];
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            JsonNode found = mailpit.getForObject(mailpitApi() + "/search?query=to:" + email, JsonNode.class);
            if (found == null || found.path("messages").isEmpty()) {
                return false;
            }
            String id = found.path("messages").get(0).path("ID").asText();
            JsonNode message = mailpit.getForObject(mailpitApi() + "/message/" + id, JsonNode.class);
            Matcher matcher = CODE.matcher(message.path("Text").asText());
            if (matcher.find()) {
                code[0] = matcher.group(1);
                return true;
            }
            return false;
        });
        return code[0];
    }

    private int messagesTo(String email) {
        JsonNode found = mailpit.getForObject(mailpitApi() + "/search?query=to:" + email, JsonNode.class);
        return found == null ? 0 : found.path("messages").size();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private static String uniqueEmail() {
        return "reset-" + UUID.randomUUID() + "@test.dev";
    }
}
