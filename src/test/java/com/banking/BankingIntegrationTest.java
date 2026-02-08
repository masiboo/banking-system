package com.banking;

import com.banking.config.KafkaConfig;
import com.banking.domain.Account;
import com.banking.domain.Balance;
import com.banking.domain.Transaction;
import com.banking.dto.PaymentEvent;
import com.banking.producer.PaymentProducer;
import com.banking.repository.AccountRepository;
import com.banking.repository.BalanceRepository;
import com.banking.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class BankingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private PaymentProducer producer;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setup() {
        transactionRepository.deleteAll();
        balanceRepository.deleteAll();
        accountRepository.deleteAll();

        createAccount("ACC1", "User 1", new BigDecimal("1000.00"));
        createAccount("ACC2", "User 2", new BigDecimal("500.00"));
    }

    private void createAccount(String id, String owner, BigDecimal amount) {
        Account account = Account.builder()
                .id(id)
                .ownerName(owner)
                .currency("EUR")
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);

        Balance balance = Balance.builder()
                .accountId(id)
                .account(account)
                .amount(amount)
                .build();
        balanceRepository.save(balance);
    }

    @Test
    void shouldProcessSuccessfulPayment() {
        String txId = UUID.randomUUID().toString();
        PaymentEvent event = PaymentEvent.builder()
                .transactionId(txId)
                .fromAccountId("ACC1")
                .toAccountId("ACC2")
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .timestamp(LocalDateTime.now())
                .build();

        producer.sendPayment(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<Transaction> tx = transactionRepository.findByExternalId(txId);
            assertThat(tx).isPresent();
            assertThat(tx.get().getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);

            Balance fromBalance = balanceRepository.findById("ACC1").get();
            Balance toBalance = balanceRepository.findById("ACC2").get();
            assertThat(fromBalance.getAmount()).isEqualByComparingTo("900.00");
            assertThat(toBalance.getAmount()).isEqualByComparingTo("600.00");
        });
    }

    @Test
    void shouldHandleIdempotency() {
        String txId = "duplicate-tx-123";
        PaymentEvent event = PaymentEvent.builder()
                .transactionId(txId)
                .fromAccountId("ACC1")
                .toAccountId("ACC2")
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .timestamp(LocalDateTime.now())
                .build();

        // Send twice
        producer.sendPayment(event);
        producer.sendPayment(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long count = transactionRepository.findAll().stream()
                    .filter(t -> t.getExternalId().equals(txId))
                    .count();
            assertThat(count).isEqualTo(1);

            Balance fromBalance = balanceRepository.findById("ACC1").get();
            assertThat(fromBalance.getAmount()).isEqualByComparingTo("900.00");
        });
    }

    @Test
    void shouldFailWhenInsufficientFunds() {
        String txId = UUID.randomUUID().toString();
        PaymentEvent event = PaymentEvent.builder()
                .transactionId(txId)
                .fromAccountId("ACC1")
                .toAccountId("ACC2")
                .amount(new BigDecimal("2000.00")) // More than balance
                .currency("EUR")
                .timestamp(LocalDateTime.now())
                .build();

        producer.sendPayment(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<Transaction> tx = transactionRepository.findByExternalId(txId);
            assertThat(tx).isPresent();
            assertThat(tx.get().getStatus()).isEqualTo(Transaction.TransactionStatus.FAILED);
            assertThat(tx.get().getFailureReason()).contains("Insufficient funds");

            Balance fromBalance = balanceRepository.findById("ACC1").get();
            assertThat(fromBalance.getAmount()).isEqualByComparingTo("1000.00");
        });
    }
}
