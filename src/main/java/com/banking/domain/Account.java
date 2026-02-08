package com.banking.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {
    @Id
    private String id; // IBAN or Account Number

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private String currency;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Balance balance;

    private LocalDateTime createdAt;
}
