package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import javax.persistence.EntityNotFoundException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Data
@Builder
public class MandatorySelectAndUpdate<T> extends OpContext<T> {
    @NonNull
    private SelectParam<T> selectParam;
    @NonNull
    private Function<SelectParam<T>, List<T>> selector;
    @Builder.Default
    private Function<T, T> mutator = t -> t;
    @NonNull
    private BiConsumer<T, T> updater;

    @Override
    public T apply(Session session) {
        List<T> entityList = selector.apply(selectParam);
        if (entityList == null || entityList.isEmpty()) {
            throw new EntityNotFoundException("No entity found for the given selected parameters");
        }
        T oldEntity = entityList.get(0);
//        confirm from vidhya
//        if (null == oldEntity) {
//            return false;
//        }
        T newEntity = mutator.apply(oldEntity);
        if (null == newEntity) {
            throw new IllegalStateException("Mutation process failed to create a new entity based on the existing entity");
        }
        updater.accept(oldEntity, newEntity);
        return newEntity;
    }

    @Override
    public OpType getOpType() {
        return OpType.MANDATORY_SELECT_AND_UPDATE;
    }

    @Override
    public <R> R visit(OpContext.OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
