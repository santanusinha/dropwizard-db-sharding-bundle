package io.appform.dropwizard.sharding.dao.operations;

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.criterion.DetachedCriteria;

@Getter
public class ScrollParam<T> {

    public DetachedCriteria criteria;
    public QuerySpec<T, T> querySpec;

    @Builder
    public ScrollParam(DetachedCriteria criteria, QuerySpec<T, T> querySpec) {
        Preconditions.checkArgument(criteria != null || querySpec != null);
        this.criteria = criteria;
        this.querySpec = querySpec;
    }

}