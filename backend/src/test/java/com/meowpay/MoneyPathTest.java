package com.meowpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MoneyPathTest {

    private static final TestPostgres POSTGRES = TestPostgres.start();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::jdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::username);
        registry.add("spring.datasource.password", POSTGRES::password);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.close();
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void registerCreditsOneHundredViaLedger() {
        Session cat = register(unique("bonus"));
        Map<String, Object> me = get(cat, "/api/me", Map.class).getBody();
        assertThat(me.get("balance")).isEqualTo(100);
        assertThat(me.get("username")).isEqualTo(cat.username);
        assertThat(me).doesNotContainKeys("password", "passwordHash");
    }

    @Test
    void happyPathDebitsAndCredits() {
        Session sender = register(unique("send"));
        Session recipient = register(unique("recv"));

        ResponseEntity<Map> created = transfer(sender, recipient.username, 10, UUID.randomUUID().toString());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(created.getBody().get("amount")).isEqualTo(10);
        assertThat(created.getBody().get("senderUsername")).isEqualTo(sender.username);
        assertThat(created.getBody().get("recipientUsername")).isEqualTo(recipient.username);

        assertThat(balance(sender)).isEqualTo(90);
        assertThat(balance(recipient)).isEqualTo(110);
    }

    @Test
    void insufficientFundsStoresRejectedAndDoesNotMoveMoney() {
        Session sender = register(unique("broke"));
        Session recipient = register(unique("rich"));
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = transfer(sender, recipient.username, 1000, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(first.getBody().get("error")).isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(balance(sender)).isEqualTo(100);
        assertThat(balance(recipient)).isEqualTo(100);

        List<Map<String, Object>> senderHistory = history(sender);
        assertThat(senderHistory).hasSize(1);
        assertThat(senderHistory.getFirst().get("status")).isEqualTo("REJECTED");
        assertThat(senderHistory.getFirst().get("direction")).isEqualTo("OUT");

        assertThat(history(recipient)).isEmpty();

        ResponseEntity<Map> replay = transfer(sender, recipient.username, 1000, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(replay.getBody().get("error")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(history(sender)).hasSize(1);
        assertThat(balance(sender)).isEqualTo(100);
    }

    @Test
    void completedReplayReturns200AndDoesNotMoveAgain() {
        Session sender = register(unique("idemp"));
        Session recipient = register(unique("idemp-r"));
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = transfer(sender, recipient.username, 10, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object id = first.getBody().get("id");

        ResponseEntity<Map> replay = transfer(sender, recipient.username, 10, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("id")).isEqualTo(id);
        assertThat(replay.getBody()).doesNotContainKey("error");

        assertThat(balance(sender)).isEqualTo(90);
        assertThat(balance(recipient)).isEqualTo(110);
    }

    @Test
    void sameKeyDifferentAmountConflictsWithoutSecondMovement() {
        Session sender = register(unique("conflict"));
        Session recipient = register(unique("conflict-r"));
        String key = UUID.randomUUID().toString();

        transfer(sender, recipient.username, 10, key);
        ResponseEntity<Map> conflict = transfer(sender, recipient.username, 20, key);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("error")).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(balance(sender)).isEqualTo(90);
    }

    @Test
    void sameKeyDifferentRecipientConflictsWithoutSecondMovement() {
        Session sender = register(unique("key-r2"));
        Session milo = register(unique("milo"));
        Session whiskers = register(unique("whisk"));
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = transfer(sender, milo.username, 10, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> conflict = transfer(sender, whiskers.username, 10, key);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("error")).isEqualTo("IDEMPOTENCY_CONFLICT");

        assertThat(balance(sender)).isEqualTo(90);
        assertThat(balance(milo)).isEqualTo(110);
        assertThat(balance(whiskers)).isEqualTo(100);
    }

    @Test
    void sameKeyDifferentCasingIsSamePayload() {
        Session sender = register(unique("case"));
        Session recipient = register("Milo-" + UUID.randomUUID().toString().substring(0, 8));
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = transfer(sender, recipient.username, 10, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> replay = transfer(sender, recipient.username.toUpperCase(), 10, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance(sender)).isEqualTo(90);
    }

    @Test
    void missingBlankAndOverlongFieldsAreValidation() {
        Session sender = register(unique("key"));
        Session recipient = register(unique("key-r"));

        HttpHeaders headers = bearer(sender);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> missing = rest.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>("{\"recipientUsername\":\"" + recipient.username + "\",\"amount\":5}", headers),
                Map.class
        );
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("error")).isEqualTo("VALIDATION");

        headers.set("Idempotency-Key", "   ");
        ResponseEntity<Map> blank = rest.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>("{\"recipientUsername\":\"" + recipient.username + "\",\"amount\":5}", headers),
                Map.class
        );
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().get("error")).isEqualTo("VALIDATION");

        headers.set("Idempotency-Key", "k".repeat(129));
        ResponseEntity<Map> longKey = rest.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>("{\"recipientUsername\":\"" + recipient.username + "\",\"amount\":5}", headers),
                Map.class
        );
        assertThat(longKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longKey.getBody().get("error")).isEqualTo("VALIDATION");

        ResponseEntity<Map> longUser = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", "u".repeat(65), "password", "treats123"),
                Map.class
        );
        assertThat(longUser.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longUser.getBody().get("error")).isEqualTo("VALIDATION");

        ResponseEntity<Map> longPass = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", unique("pw"), "password", "p".repeat(73)),
                Map.class
        );
        assertThat(longPass.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longPass.getBody().get("error")).isEqualTo("VALIDATION");
    }

    @Test
    void invalidAmountAndNonIntegerUseValidationCode() {
        Session sender = register(unique("amt"));
        Session recipient = register(unique("amt-r"));

        ResponseEntity<Map> zero = transfer(sender, recipient.username, 0, UUID.randomUUID().toString());
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(zero.getBody().get("error")).isEqualTo("VALIDATION");

        HttpHeaders headers = bearer(sender);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> half = rest.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(
                        "{\"recipientUsername\":\"" + recipient.username + "\",\"amount\":10.5}",
                        headers
                ),
                Map.class
        );
        assertThat(half.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(half.getBody().get("error")).isEqualTo("VALIDATION");
    }

    @Test
    void sameCatAndUnknownRecipient() {
        Session sender = register(unique("self"));
        ResponseEntity<Map> self = transfer(sender, sender.username, 5, UUID.randomUUID().toString());
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(self.getBody().get("error")).isEqualTo("SAME_CAT");

        ResponseEntity<Map> missing = transfer(sender, "no-such-cat", 5, UUID.randomUUID().toString());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("error")).isEqualTo("NOT_FOUND");
    }

    @Test
    void extraSenderUsernameCannotImpersonate() {
        Session realSender = register(unique("real"));
        Session decoy = register(unique("decoy"));
        Session recipient = register(unique("victim"));

        HttpHeaders headers = bearer(realSender);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
                {"recipientUsername":"%s","amount":15,"senderUsername":"%s"}
                """.formatted(recipient.username, decoy.username);

        ResponseEntity<Map> created = rest.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("senderUsername")).isEqualTo(realSender.username);
        assertThat(balance(realSender)).isEqualTo(85);
        assertThat(balance(decoy)).isEqualTo(100);
        assertThat(balance(recipient)).isEqualTo(115);
    }

    @Test
    void overlappingSendsDoNotCreateMoney() throws Exception {
        Session sender = register(unique("race"));
        Session recipient = register(unique("race-r"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<Map>> first = pool.submit(() -> {
            start.await();
            return transfer(sender, recipient.username, 80, UUID.randomUUID().toString());
        });
        Future<ResponseEntity<Map>> second = pool.submit(() -> {
            start.await();
            return transfer(sender, recipient.username, 80, UUID.randomUUID().toString());
        });
        start.countDown();

        ResponseEntity<Map> a = first.get(20, TimeUnit.SECONDS);
        ResponseEntity<Map> b = second.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(List.of(a.getStatusCode().value(), b.getStatusCode().value()))
                .containsExactlyInAnyOrder(201, 409);
        Map<String, Object> conflictBody = a.getStatusCode().value() == 409 ? a.getBody() : b.getBody();
        assertThat(conflictBody.get("error")).isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(balance(sender)).isEqualTo(20);
        assertThat(balance(recipient)).isEqualTo(180);
    }

    @Test
    void sameKeyParallelDoesNotFiveHundred() throws Exception {
        Session sender = register(unique("samekey"));
        Session recipient = register(unique("samekey-r"));
        String key = UUID.randomUUID().toString();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<Map>> first = pool.submit(() -> {
            start.await();
            return transfer(sender, recipient.username, 10, key);
        });
        Future<ResponseEntity<Map>> second = pool.submit(() -> {
            start.await();
            return transfer(sender, recipient.username, 10, key);
        });
        start.countDown();

        ResponseEntity<Map> a = first.get(20, TimeUnit.SECONDS);
        ResponseEntity<Map> b = second.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(List.of(a.getStatusCode().value(), b.getStatusCode().value()))
                .containsExactlyInAnyOrder(201, 200);
        assertThat(a.getStatusCode().value()).isNotEqualTo(500);
        assertThat(b.getStatusCode().value()).isNotEqualTo(500);
        assertThat(balance(sender)).isEqualTo(90);
        assertThat(balance(recipient)).isEqualTo(110);
    }

    @Test
    void historyIsCallerOnlyAndRecipientsDoNotLeakPassword() {
        Session luna = register(unique("luna"));
        Session milo = register(unique("milo"));
        Session whiskers = register(unique("whisk"));

        transfer(luna, whiskers.username, 10, UUID.randomUUID().toString());

        assertThat(history(milo)).isEmpty();
        List<Map<String, Object>> lunaHistory = history(luna);
        assertThat(lunaHistory).hasSize(1);
        assertThat(lunaHistory.getFirst().get("direction")).isEqualTo("OUT");
        assertThat(lunaHistory.getFirst().get("status")).isEqualTo("COMPLETED");
        assertThat(lunaHistory.getFirst().get("counterpartyUsername")).isEqualTo(whiskers.username);

        List<Map<String, Object>> whiskersHistory = history(whiskers);
        assertThat(whiskersHistory).hasSize(1);
        assertThat(whiskersHistory.getFirst().get("direction")).isEqualTo("IN");
        assertThat(whiskersHistory.getFirst().get("status")).isEqualTo("COMPLETED");
        assertThat(whiskersHistory.getFirst().get("counterpartyUsername")).isEqualTo(luna.username);
        assertThat(whiskersHistory.getFirst().get("amount")).isEqualTo(10);

        String raw = rest.exchange(
                "/api/recipients",
                HttpMethod.GET,
                new HttpEntity<>(bearer(milo)),
                String.class
        ).getBody();
        assertThat(raw).doesNotContain("password");
    }

    @Test
    void parallelRegisterHitsUniqueCatch() throws Exception {
        String name = unique("parreg");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<Map>> first = pool.submit(() -> {
            start.await();
            return rest.postForEntity(
                    "/api/auth/register",
                    Map.of("username", name, "password", "treats123"),
                    Map.class
            );
        });
        Future<ResponseEntity<Map>> second = pool.submit(() -> {
            start.await();
            return rest.postForEntity(
                    "/api/auth/register",
                    Map.of("username", name, "password", "treats123"),
                    Map.class
            );
        });
        start.countDown();

        ResponseEntity<Map> a = first.get(20, TimeUnit.SECONDS);
        ResponseEntity<Map> b = second.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(List.of(a.getStatusCode().value(), b.getStatusCode().value()))
                .containsExactlyInAnyOrder(201, 409);
        Map<String, Object> conflict = a.getStatusCode().value() == 409 ? a.getBody() : b.getBody();
        assertThat(conflict.get("error")).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void emptyLedgerCatBalanceIsZeroAfterLogin() {
        String username = unique("empty");
        jdbc.update(
                "INSERT INTO cats (id, username, password_hash, created_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(),
                username,
                passwordEncoder.encode("treats123")
        );

        ResponseEntity<Map> login = rest.postForEntity(
                "/api/auth/login",
                Map.of("username", username, "password", "treats123"),
                Map.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Session session = new Session((String) login.getBody().get("token"), username);
        assertThat(balance(session)).isEqualTo(0);
    }

    private Session register(String username) {
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "password", "treats123"),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Session((String) response.getBody().get("token"), (String) response.getBody().get("username"));
    }

    private ResponseEntity<Map> transfer(Session sender, String recipient, int amount, String key) {
        HttpHeaders headers = bearer(sender);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("recipientUsername", recipient, "amount", amount), headers),
                Map.class
        );
    }

    private int balance(Session session) {
        return ((Number) get(session, "/api/me", Map.class).getBody().get("balance")).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history(Session session) {
        return get(session, "/api/me/transfers", List.class).getBody();
    }

    private <T> ResponseEntity<T> get(Session session, String path, Class<T> type) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(session)), type);
    }

    private HttpHeaders bearer(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.token);
        return headers;
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Session(String token, String username) {
    }
}
