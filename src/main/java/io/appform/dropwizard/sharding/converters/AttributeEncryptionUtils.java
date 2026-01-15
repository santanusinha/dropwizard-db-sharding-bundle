package io.appform.dropwizard.sharding.converters;

import lombok.experimental.UtilityClass;
import org.jasypt.encryption.BigDecimalEncryptor;
import org.jasypt.encryption.BigIntegerEncryptor;
import org.jasypt.encryption.ByteEncryptor;
import org.jasypt.encryption.pbe.PBEStringEncryptor;
import org.jasypt.hibernate6.encryptor.HibernatePBEEncryptorRegistry;

import java.util.Objects;

@UtilityClass
public class AttributeEncryptionUtils {

    public static final String STRING_ENCRYPTER_NAME = "encryptedString";
    public static final String CALENDER_ENCRYPTER_NAME = "encryptedCalendarAsString";
    public static final String BIG_INTEGER_ENCRYPTER_NAME = "encryptedBigInteger";
    public static final String BIG_DECIMAL_ENCRYPTER_NAME = "encryptedBigDecimal";
    public static final String BYTE_ENCRYPTER_NAME = "encryptedBinary";


    public PBEStringEncryptor stringEncryptor() {
        HibernatePBEEncryptorRegistry registry = HibernatePBEEncryptorRegistry.getInstance();
        PBEStringEncryptor encryptor = registry.getPBEStringEncryptor(STRING_ENCRYPTER_NAME);

        if (Objects.isNull(encryptor)) {
            throw new IllegalStateException("Encryptor " + STRING_ENCRYPTER_NAME + "was not found in the Jasypt Registry.");
        }
        return encryptor;
    }

    public PBEStringEncryptor calenderEncryptor() {
        HibernatePBEEncryptorRegistry registry = HibernatePBEEncryptorRegistry.getInstance();
        PBEStringEncryptor encryptor = registry.getPBEStringEncryptor(CALENDER_ENCRYPTER_NAME);

        if (Objects.isNull(encryptor)) {
            throw new IllegalStateException("Encryptor " + CALENDER_ENCRYPTER_NAME + "was not found in the Jasypt Registry.");
        }
        return encryptor;
    }

    public BigDecimalEncryptor bigDecimalEncryptor() {
        HibernatePBEEncryptorRegistry registry = HibernatePBEEncryptorRegistry.getInstance();
        BigDecimalEncryptor encryptor = registry.getPBEBigDecimalEncryptor(BIG_DECIMAL_ENCRYPTER_NAME);

        if (Objects.isNull(encryptor)) {
            throw new IllegalStateException("Encryptor " + BIG_DECIMAL_ENCRYPTER_NAME + "was not found in the Jasypt Registry.");
        }
        return encryptor;
    }

    public BigIntegerEncryptor bigIntegerEncryptor() {
        HibernatePBEEncryptorRegistry registry = HibernatePBEEncryptorRegistry.getInstance();
        BigIntegerEncryptor encryptor = registry.getPBEBigIntegerEncryptor(BIG_INTEGER_ENCRYPTER_NAME);

        if (Objects.isNull(encryptor)) {
            throw new IllegalStateException("Encryptor " + BIG_INTEGER_ENCRYPTER_NAME + "was not found in the Jasypt Registry.");
        }
        return encryptor;
    }

    public ByteEncryptor byteEncryptor() {
        HibernatePBEEncryptorRegistry registry = HibernatePBEEncryptorRegistry.getInstance();
        ByteEncryptor encryptor = registry.getPBEByteEncryptor(BYTE_ENCRYPTER_NAME);

        if (Objects.isNull(encryptor)) {
            throw new IllegalStateException("Encryptor " + BYTE_ENCRYPTER_NAME + "was not found in the Jasypt Registry.");
        }
        return encryptor;
    }
}
