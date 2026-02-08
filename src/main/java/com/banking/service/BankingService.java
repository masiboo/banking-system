package com.banking.service;

import com.banking.domain.Account;
import com.banking.domain.Balance;
import com.banking.domain.Transaction;
import com.banking.dto.PaymentEvent;
import com.banking.repository.AccountRepository;
import com.banking.repository.BalanceRepository;
import com.banking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class BankingService {

    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Processes a payment event.
     * This method is idempotent: if called multiple times with the same transactionId,
     * it will only process the payment once.
     */
    @Transactional
    public void processPayment(PaymentEvent event) {
        // 1. Idempotency Check: Check if transaction was already processed
        Optional<Transaction> existingTransaction = transactionRepository.findByExternalId(event.getTransactionId());
        if (existingTransaction.isPresent()) {
            log.info("Transaction {} already processed with status {}. Skipping.", 
                    event.getTransactionId(), existingTransaction.get().getStatus());
            return;
        }

        // 2. Persist initial transaction record as PENDING
        Transaction transaction = Transaction.builder()
                .externalId(event.getTransactionId())
                .fromAccountId(event.getFromAccountId())
                .toAccountId(event.getToAccountId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .status(Transaction.TransactionStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        transaction = transactionRepository.save(transaction);

        try {
            // 3. Business Validation & Balance Updates
            Balance fromBalance = balanceRepository.findById(event.getFromAccountId())
                    .orElseThrow(() -> new RuntimeException("Source account not found: " + event.getFromAccountId()));
            
            Balance toBalance = balanceRepository.findById(event.getToAccountId())
                    .orElseThrow(() -> new RuntimeException("Target account not found: " + event.getToAccountId()));

            if (fromBalance.getAmount().compareTo(event.getAmount()) < 0) {
                throw new RuntimeException("Insufficient funds in account: " + event.getFromAccountId());
            }

            // Update balances (Optimistic locking via @Version in Balance entity)
            fromBalance.setAmount(fromBalance.getAmount().subtract(event.getAmount()));
            toBalance.setAmount(toBalance.getAmount().add(event.getAmount()));

            balanceRepository.save(fromBalance);
            balanceRepository.save(toBalance);

            // 4. Update transaction status to COMPLETED
            transaction.setStatus(Transaction.TransactionStatus.COMPLETED);
            transaction.setProcessedAt(LocalDateTime.now());
            transactionRepository.save(transaction);

        } catch (Exception e) {
            log.error("Payment processing failed for {}: {}", event.getTransactionId(), e.getMessage());
            
            // 5. Update transaction status to FAILED
            transaction.setStatus(Transaction.TransactionStatus.FAILED);
            transaction.setFailureReason(e.getMessage());
            transaction.setProcessedAt(LocalDateTime.now());
            transactionRepository.save(transaction);
            
            // We rethrow if it's a transient issue that might benefit from Kafka retry.
            // For business failures (like insufficient funds), we might want to NOT retry.
            // In a real system, we'd distinguish between BusinessException and TechnicalException.
            throw e; 
        }
    }
}
