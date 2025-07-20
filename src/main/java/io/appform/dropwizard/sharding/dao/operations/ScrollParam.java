package io.appform.dropwizard.sharding.dao.operations;

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Getter;

@Getter
public class ScrollParam<T> {

  public QuerySpec<T, T> querySpec;

  @Builder
  public ScrollParam(QuerySpec<T, T> querySpec) {
    Preconditions.checkArgument(criteria != null || querySpec != null);
    this.querySpec = querySpec;
  }

}