package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.Repository;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 仓储接口扫描注册器。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public class WcdkR2dbcRepositoryRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware, BeanFactoryAware {

    private static final String AUTO_CONFIGURATION_PACKAGES_BEAN_NAME = AutoConfigurationPackages.class.getName();

    private Environment environment;

    private ResourceLoader resourceLoader;

    private BeanFactory beanFactory;

    private ClassLoader classLoader;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Set<String> packages = basePackages(importingClassMetadata, registry);
        for (String basePackage : packages) {
            scanPackage(registry, basePackage);
        }
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.classLoader = resourceLoader.getClassLoader();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    private Set<String> basePackages(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableWcdkR2dbcRepositories.class.getName(), false));
        if (attributes != null) {
            result.addAll(List.of(attributes.getStringArray("basePackages")));
            for (Class<?> basePackageClass : attributes.getClassArray("basePackageClasses")) {
                result.add(basePackageClass.getPackageName());
            }
            if (result.isEmpty()) {
                result.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
            }
        }
        if (environment != null) {
            result.addAll(List.of(environment.getProperty("wcdk.r2dbc.base-packages", String[].class, new String[0])));
        }
        if (registry.containsBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN_NAME)) {
            Object value = registry.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN_NAME)
                    .getConstructorArgumentValues()
                    .getIndexedArgumentValue(0, String[].class)
                    .getValue();
            if (value instanceof String[] packageNames) {
                result.addAll(List.of(packageNames));
            }
        }
        if (beanFactory != null && AutoConfigurationPackages.has(beanFactory)) {
            result.addAll(AutoConfigurationPackages.get(beanFactory));
        }
        return result;
    }

    private void scanPackage(BeanDefinitionRegistry registry, String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) ->
                metadataReader.getAnnotationMetadata().hasAnnotation(Repository.class.getName()));
        for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
            registerRepository(registry, candidate.getBeanClassName());
        }
    }

    private void registerRepository(BeanDefinitionRegistry registry, String className) {
        String beanName = StringUtils.uncapitalize(ClassUtils.getShortName(className));
        if (registry.containsBeanDefinition(beanName)) {
            return;
        }
        Class<?> repositoryInterface = resolveClass(className);
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setBeanClass(WcdkR2dbcRepositoryFactoryBean.class);
        beanDefinition.getConstructorArgumentValues().addGenericArgumentValue(repositoryInterface);
        beanDefinition.setAutowireMode(GenericBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        beanDefinition.setLazyInit(true);
        beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, repositoryInterface);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    private Class<?> resolveClass(String className) {
        try {
            return ClassUtils.forName(className, classLoader);
        }
        catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Repository interface class not found: " + className, ex);
        }
    }
}
