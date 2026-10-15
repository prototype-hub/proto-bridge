package com.gaiys.proto.system.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class TransactionConfiguration {
    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager manager) {
        return TransactionalOperator.create(manager);
    }
}
