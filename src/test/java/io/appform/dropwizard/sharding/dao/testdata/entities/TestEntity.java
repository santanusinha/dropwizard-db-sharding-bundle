/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding.dao.testdata.entities;

import io.appform.dropwizard.sharding.sharding.LookupKey;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.validator.constraints.NotEmpty;

import javax.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "test_entity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@NamedQueries({
        @NamedQuery(name = "testTextUpdateQuery", query = "update TestEntity set text = :text where externalId =:externalId")})
public class TestEntity {

    @Id
    @NotEmpty
    @LookupKey
    @Column(name = "ext_id", unique = true)
    private String externalId;

    @Column(name = "text", nullable = false)
    @NotEmpty
    private String text;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        TestEntity that = (TestEntity) o;
        return getExternalId() != null && Objects.equals(getExternalId(), that.getExternalId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
