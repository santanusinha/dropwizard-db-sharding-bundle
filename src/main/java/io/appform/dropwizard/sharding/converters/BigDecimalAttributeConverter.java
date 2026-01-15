package io.appform.dropwizard.sharding.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

@Converter
public class BigDecimalAttributeConverter implements AttributeConverter<BigDecimal, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(BigDecimal attribute) {
        return (attribute == null) ? null : AttributeEncryptionUtils.bigDecimalEncryptor().encrypt(attribute);
    }

    @Override
    public BigDecimal convertToEntityAttribute(BigDecimal dbData) {
        return (dbData == null) ? null : AttributeEncryptionUtils.bigDecimalEncryptor().decrypt(dbData);
    }
}