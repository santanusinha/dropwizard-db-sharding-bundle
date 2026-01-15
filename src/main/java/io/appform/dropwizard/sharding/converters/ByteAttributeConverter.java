package io.appform.dropwizard.sharding.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ByteAttributeConverter implements AttributeConverter<byte[], byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(byte[] attribute) {
        return (attribute == null) ? null : AttributeEncryptionUtils.byteEncryptor().encrypt(attribute);
    }

    @Override
    public byte[] convertToEntityAttribute(byte[] dbData) {
        return (dbData == null) ? null : AttributeEncryptionUtils.byteEncryptor().decrypt(dbData);
    }
}