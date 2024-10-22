package io.appform.dropwizard.sharding.dao.operations;

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Getter;

@Getter
public class SelectParam<T> {

    public QuerySpec<T, T> querySpec;
    public Integer start;
    public Integer numRows;

    @Builder
    public SelectParam(QuerySpec<T, T> querySpec, Integer start, Integer numRows) {
        Preconditions.checkArgument(querySpec != null);
        this.querySpec = querySpec;
        this.start = start;
        this.numRows = numRows;
    }
}