package in.cleartax.dropwizard.sharding.transactions.listeners;

import io.dropwizard.hibernate.UnitOfWork;

/**
 * Created on 2019-01-17
 */
public interface TransactionRunnerListener {
    void onStart(UnitOfWork unitOfWork);

    void onFinish(boolean success, UnitOfWork unitOfWork);
}
