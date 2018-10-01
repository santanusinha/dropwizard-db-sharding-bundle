/*
 * Copyright 2018 Saurabh Agrawal (Cleartax)
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

package io.dropwizard.sharding.hibernate;

import io.dropwizard.lifecycle.Managed;
import org.hibernate.SessionFactory;
import ru.vyarus.dropwizard.guice.module.installer.scanner.InvisibleForScanner;

// copied from SessionFactoryManager
@InvisibleForScanner
public class MultiTenantSessionFactoryManager implements Managed {
    private final SessionFactory factory;
    private final MultiTenantManagedDataSource dataSource;

    public MultiTenantSessionFactoryManager(SessionFactory factory, MultiTenantManagedDataSource dataSource) {
        this.factory = factory;
        this.dataSource = dataSource;
    }

    @Override
    public void start() throws Exception {
        dataSource.start();
    }

    @Override
    public void stop() throws Exception {
        factory.close();
        dataSource.stop();
    }
}
