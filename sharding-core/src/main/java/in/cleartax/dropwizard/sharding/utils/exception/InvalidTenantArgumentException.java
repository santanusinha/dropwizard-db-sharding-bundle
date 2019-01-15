package in.cleartax.dropwizard.sharding.utils.exception;

/**
 * Created on 2019-01-15
 */
public class InvalidTenantArgumentException extends RuntimeException {
    public InvalidTenantArgumentException(String s) {
        super(s);
    }
}
