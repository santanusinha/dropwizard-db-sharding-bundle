/*
 * Copyright 2018 Cleartax
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package in.cleartax.dropwizard.sharding.transactions;

import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.UnitOfWork;
import org.hibernate.CacheMode;
import org.hibernate.FlushMode;

import java.lang.annotation.Annotation;

@SuppressWarnings("ClassExplicitlyAnnotation")
public class DefaultUnitOfWorkImpl implements UnitOfWork {
    @Override
    public Class<? extends Annotation> annotationType() {
        return UnitOfWork.class;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean transactional() {
        return true;
    }

    @Override
    public CacheMode cacheMode() {
        return CacheMode.NORMAL;
    }

    @Override
    public FlushMode flushMode() {
        return FlushMode.AUTO;
    }

    @Override
    public String value() {
        return HibernateBundle.DEFAULT_NAME;
    }
}
