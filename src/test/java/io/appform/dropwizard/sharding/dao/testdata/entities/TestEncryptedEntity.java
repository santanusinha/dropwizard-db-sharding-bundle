///*
// * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// * http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// *
// */
//
//package io.appform.dropwizard.sharding.dao.testdata.entities;
//
//import io.appform.dropwizard.sharding.sharding.LookupKey;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Getter;
//import lombok.NoArgsConstructor;
//import lombok.Setter;
//import org.hibernate.Hibernate;
//import org.hibernate.annotations.Type;
//import org.hibernate.annotations.TypeDef;
//import org.hibernate.annotations.TypeDefs;
//import org.hibernate.validator.constraints.NotEmpty;
//
//import jakarta.persistence.Column;
//import jakarta.persistence.Entity;
//import jakarta.persistence.Id;
//import jakarta.persistence.Table;
//import java.util.Objects;
//@TypeDefs({
//        @TypeDef(
//                name = "EncryptedString",
//                typeClass = org.jasypt.hibernate5.type.EncryptedStringType.class,
//                parameters = {
//                        @org.hibernate.annotations.Parameter(name="encryptorRegisteredName", value = "encryptedString")
//                }
//        )
//})
//
//@Entity
//@Table(name = "test_enc_entity")
//@Getter
//@Setter
//@Builder
//@NoArgsConstructor
//@AllArgsConstructor
//public class TestEncryptedEntity {
//
//    @Id
//    @NotEmpty
//    @LookupKey
//    @Column(name = "ext_id", unique = true)
//    private String externalId;
//
//    @Column(name = "enc_text", nullable = false)
//    @NotEmpty
//    @Type(type = "EncryptedString")
//    private String encText;
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) {
//            return true;
//        }
//        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
//            return false;
//        }
//        TestEncryptedEntity that = (TestEncryptedEntity) o;
//        return getExternalId() != null && Objects.equals(getExternalId(), that.getExternalId());
//    }
//
//    @Override
//    public int hashCode() {
//        return getClass().hashCode();
//    }
//}
