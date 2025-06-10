package io.appform.dropwizard.sharding.dao.operations;

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.criterion.DetachedCriteria;

@Getter
public class SelectParam<T> {

    public DetachedCriteria criteria;
    public QuerySpec<T, T> querySpec;

    public Integer start;
    public Integer numRows;

    @Builder
    public SelectParam(DetachedCriteria criteria, QuerySpec<T, T> querySpec, Integer start,
                       Integer numRows) {
        Preconditions.checkArgument(criteria != null || querySpec != null);
        this.criteria = criteria;
        this.querySpec = querySpec;
        this.start = start;
        this.numRows = numRows;
    }
}