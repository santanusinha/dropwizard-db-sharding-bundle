package io.appform.dropwizard.sharding.observers.bucket;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

@Slf4j
public class BucketIdObserver extends TransactionObserver {
    private BucketIdSaver bucketIdSaver;
    private volatile boolean initDone;

    public BucketIdObserver(BucketIdSaver bucketIdSaver) {
        super(null);
        this.bucketIdSaver = bucketIdSaver;
        log.info("BucketId observer constructor called ");
    }
    public void init(BucketIdSaver bucketIdSaver) {
        this.bucketIdSaver = bucketIdSaver;
        this.initDone = true;
        log.info("BucketId observer initialisation completed ");
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        context.getOpContext().visit(this.bucketIdSaver);
        return proceed(context, supplier);
    }

}