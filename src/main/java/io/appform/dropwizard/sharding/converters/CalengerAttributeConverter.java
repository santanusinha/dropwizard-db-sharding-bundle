package io.appform.dropwizard.sharding.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class CalengerAttributeConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return (attribute == null) ? null : AttributeEncryptionUtils.calenderEncryptor().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return (dbData == null) ? null : AttributeEncryptionUtils.calenderEncryptor().decrypt(dbData);
    }
}