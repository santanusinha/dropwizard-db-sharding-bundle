package io.appform.dropwizard.sharding.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.math.BigInteger;

@Converter
public class BigIntegerAttributeConverter implements AttributeConverter<BigInteger, BigInteger> {

    @Override
    public BigInteger convertToDatabaseColumn(BigInteger attribute) {
        return (attribute == null) ? null : AttributeEncryptionUtils.bigIntegerEncryptor().encrypt(attribute);
    }

    @Override
    public BigInteger convertToEntityAttribute(BigInteger dbData) {
        return (dbData == null) ? null : AttributeEncryptionUtils.bigIntegerEncryptor().decrypt(dbData);
    }
}