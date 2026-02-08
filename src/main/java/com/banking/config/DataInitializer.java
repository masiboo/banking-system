package com.banking.config;

import com.banking.domain.Account;
import com.banking.domain.Balance;
import com.banking.repository.AccountRepository;
import com.banking.repository.BalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;

    @Bean
    @Profile("!test") // Don't run this in tests
    public CommandLineRunner initData() {
        return args -> {
            if (accountRepository.count() == 0) {
                createAccount("DE123456789", "Alice", new BigDecimal("1000.00"));
                createAccount("FR987654321", "Bob", new BigDecimal("500.00"));
            }
        };
    }

    private void createAccount(String id, String owner, BigDecimal initialBalance) {
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
                .amount(initialBalance)
                .build();
        balanceRepository.save(balance);
    }
}
