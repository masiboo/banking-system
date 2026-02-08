# Production-Grade Banking System

This project implements a resilient, event-driven banking backend using Spring Boot 3, Java 21, Kafka, and PostgreSQL.

## Core Architecture Decisions

### 1. Kafka Reliability Patterns
- **Idempotent Producer**: Enabled to prevent duplicate messages at the producer level.
- **Acks=all**: Ensures data is replicated to all in-sync replicas before acknowledging.
- **Exponential Backoff**: Configured in `KafkaConfig` to handle transient infrastructure issues.
- **Dead Letter Topic (DLT)**: Messages that fail after exhaustive retries are routed to `payments.DLT` for manual intervention or auditing.

### 2. Consistency & Idempotency
- **Exactly-Once Semantics**: Achieved by combining "at-least-once" Kafka delivery with "idempotent processing" in the database.
- **Idempotency Key**: Every `PaymentEvent` carries a `transactionId`. The consumer checks the `transactions` table before processing to ensure no duplicate processing occurs.
- **Database Transactions**: The business logic (balance update + transaction status update) is wrapped in a `@Transactional` block.
- **Optimistic Locking**: The `Balance` entity uses a `@Version` field to prevent lost updates in highly concurrent scenarios (double spending protection).

### 3. Why avoid XA Transactions?
In modern distributed systems, XA (Two-Phase Commit) is avoided due to:
- **Performance**: High latency and locking overhead.
- **Availability**: If the coordinator fails, resources remain locked.
- **Scalability**: Doesn't scale well across distributed brokers like Kafka.
Instead, we use **Eventual Consistency** with idempotent consumers and compensating transactions if needed.

### 4. Topic Strategy
- **Partitions**: Set to 2 as requested.
- **Ordering**: The `fromAccountId` is used as the Kafka message key. This ensures all transactions for a specific account are routed to the same partition and processed in order.

## How to Run

### Local Infrastructure
Start the required services using Docker Compose:
```bash
docker-compose up -d
```

### Running the Application
```bash
mvn spring-boot:run
```

### Running Tests
Integration tests use **Testcontainers** to spin up real Kafka and PostgreSQL instances.
```bash
mvn test
```

## Observability
- **Structured Logging**: Includes correlation IDs for tracking requests across the system.
- **Health Checks**: Available at `/actuator/health`, monitoring both Kafka and DB connectivity.
