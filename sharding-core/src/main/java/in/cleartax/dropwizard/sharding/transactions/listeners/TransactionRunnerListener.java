package in.cleartax.dropwizard.sharding.transactions.listeners;

import io.dropwizard.hibernate.UnitOfWork;

import java.lang.reflect.Method;

/**
 * Created on 2019-01-17
 */
public interface TransactionRunnerListener {
    void onStart(UnitOfWork unitOfWork, Method methodOfInvocation);

    void onFinish(boolean success, UnitOfWork unitOfWork, Method methodOfInvocation);
}
