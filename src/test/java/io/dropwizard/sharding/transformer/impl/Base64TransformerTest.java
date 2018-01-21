package io.dropwizard.sharding.transformer.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.sharding.dao.testdata.ModelHelper;
import io.dropwizard.sharding.dao.testdata.transformation.Identity;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author tushar.naik
 * @version 1.0  21/01/18 - 6:50 PM
 */
public class Base64TransformerTest {

    @Test
    public void testTransform() throws Exception {
        Base64Transformer<Identity> transformer = new Base64Transformer<>(Identity.class, new ObjectMapper());
        Assert.assertNotNull(transformer.transform(ModelHelper.sampleIdentity()).getTransformedData());
        Identity identity = transformer.retrieve("eyJuYW1lIjoiUmFtIiwiYWRkcmVzcyI6eyJzdHJlZXQiOiIzcmQgc3RyZWV0IiwibG9jYXRpb24iOiJiYW5nYWxvcmUifSwiYWdlIjoxMH0=", "");
        Assert.assertEquals("Ram", identity.getName());
    }
}