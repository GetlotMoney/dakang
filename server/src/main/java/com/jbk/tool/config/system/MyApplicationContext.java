package com.jbk.tool.config.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class MyApplicationContext {
    @Autowired
    private ApplicationContext applicationContext;

    public  <T> T getProxy(Class<T> t) {
        return applicationContext.getBean(t);
    }

}
