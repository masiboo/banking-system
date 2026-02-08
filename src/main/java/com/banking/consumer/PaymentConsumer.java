package com.banking.consumer;

import com.banking.config.KafkaConfig;
import com.banking.dto.PaymentEvent;
import com.banking.service.BankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentConsumer {

    private final BankingService bankingService;

    @KafkaListener(
            topics = KafkaConfig.PAYMENTS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "2" // Matching partition count for parallel processing
    )
    public void consume(@Payload PaymentEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset,
                        @Header(KafkaHeaders.RECEIVED_KEY) String key,
                        Acknowledgment ack) {
        
        log.info("Consumed event from partition {} offset {} with key {}: {}", partition, offset, key, event);
        
        try {
            // Business logic with idempotency check
            bankingService.processPayment(event);
            
            // Manual commit after successful processing
            // This ensures "at-least-once" delivery. Combined with idempotent processing in DB,
            // we achieve "exactly-once" semantics for the business state.
            ack.acknowledge();
            log.info("Successfully processed and acknowledged event: {}", event.getTransactionId());
            
        } catch (Exception e) {
            log.error("Error processing payment event {}: {}", event.getTransactionId(), e.getMessage());
            // We do NOT acknowledge here. 
            // The CommonErrorHandler will handle retries and eventual DLT routing.
            throw e; 
        }
    }
}
