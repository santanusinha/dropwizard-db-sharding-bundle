package in.cleartax.dropwizard.sharding.transactions;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;


@RequiredArgsConstructor(staticName = "create")
@Getter
public class TransactionContext {

    private final Method methodOfInvocation;

}
