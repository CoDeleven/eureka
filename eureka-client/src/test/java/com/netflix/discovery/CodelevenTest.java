package com.netflix.discovery;

import org.junit.Test;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

public class CodelevenTest {
    /**
     * 测试JNDI查询 DNS TXT记录
     * @throws NamingException
     */
    @Test
    public void testJndiDns() throws NamingException {
        String queryUrl = "txt.region1.codeleven.cn";

        Hashtable<String,String> env = new Hashtable<String,String>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
//        env.put("java.naming.provider.url",dnsURL);
        InitialDirContext context = new InitialDirContext(env);

        String[] queryAttribute = {"TXT"};
        Attributes attributes = context.getAttributes(queryUrl, queryAttribute);
        Attribute txtAttribute = attributes.get("TXT");
        System.out.println(txtAttribute.get().toString());
    }
}
