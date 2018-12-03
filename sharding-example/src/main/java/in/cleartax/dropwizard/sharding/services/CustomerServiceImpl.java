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

package in.cleartax.dropwizard.sharding.services;

import in.cleartax.dropwizard.sharding.dao.CustomerDao;
import in.cleartax.dropwizard.sharding.transactions.ReuseSession;
import in.cleartax.dropwizard.sharding.transactions.TenantIdentifier;
import io.dropwizard.hibernate.UnitOfWork;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;

@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class CustomerServiceImpl implements CustomerService {

    private final CustomerDao customerDao;

    @UnitOfWork
    @ReuseSession
    @TenantIdentifier(useDefault = true)
    @Override
    public boolean isValidUser(String userName) {
        return customerDao.getByUserName(userName) != null;
    }
}
