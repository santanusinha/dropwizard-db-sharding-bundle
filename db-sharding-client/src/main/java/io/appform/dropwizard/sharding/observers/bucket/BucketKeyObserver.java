package io.appform.dropwizard.sharding.observers.bucket;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

@Slf4j
public class BucketKeyObserver extends TransactionObserver {

    private final BucketKeyPersistor bucketKeyPersistor;

    public BucketKeyObserver(final BucketKeyPersistor bucketKeyPersistor) {
        super(null);
        this.bucketKeyPersistor = bucketKeyPersistor;
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        context.getOpContext().visit(this.bucketKeyPersistor);
        return proceed(context, supplier);
    }

}