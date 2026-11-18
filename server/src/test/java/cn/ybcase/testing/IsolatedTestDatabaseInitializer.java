package cn.ybcase.testing;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Test classpath only: install the guard before application beans can initialize. */
public final class IsolatedTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var definition = BeanDefinitionBuilder.genericBeanDefinition(TestDatabaseGuard.class,
                () -> new TestDatabaseGuard(context.getEnvironment())).getBeanDefinition();
        definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        ((BeanDefinitionRegistry) context.getBeanFactory())
                .registerBeanDefinition("isolatedTestDatabaseGuard", definition);
    }
}
