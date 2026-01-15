package io.appform.dropwizard.sharding.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class StringAttributeConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return (attribute == null) ? null : AttributeEncryptionUtils.stringEncryptor().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return (dbData == null) ? null : AttributeEncryptionUtils.stringEncryptor().decrypt(dbData);
    }
}