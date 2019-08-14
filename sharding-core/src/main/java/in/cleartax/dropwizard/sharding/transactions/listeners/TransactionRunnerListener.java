package in.cleartax.dropwizard.sharding.transactions.listeners;

import in.cleartax.dropwizard.sharding.transactions.TransactionContext;
import io.dropwizard.hibernate.UnitOfWork;

import java.lang.reflect.Method;

/**
 * Created on 2019-01-17
 */
public interface TransactionRunnerListener {
    void onStart(UnitOfWork unitOfWork, TransactionContext transactionContext);

    void onFinish(boolean success, UnitOfWork unitOfWork, TransactionContext transactionContext);
}
