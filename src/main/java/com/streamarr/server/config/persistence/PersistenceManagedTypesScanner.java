package com.streamarr.server.config.persistence;

import lombok.Getter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;

import javax.persistence.Converter;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;
import javax.persistence.PersistenceException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public class PersistenceManagedTypesScanner {

    private static final String CLASS_RESOURCE_PATTERN = "/**/*.class";

    private static final Set<AnnotationTypeFilter> entityTypeFilters;

    static {
        entityTypeFilters = new LinkedHashSet<>(8);
        entityTypeFilters.add(new AnnotationTypeFilter(Entity.class, false));
        entityTypeFilters.add(new AnnotationTypeFilter(Embeddable.class, false));
        entityTypeFilters.add(new AnnotationTypeFilter(MappedSuperclass.class, false));
        entityTypeFilters.add(new AnnotationTypeFilter(Converter.class, false));
    }

    private final ResourcePatternResolver resourcePatternResolver;

    public PersistenceManagedTypesScanner(ResourceLoader resourceLoader) {
        this.resourcePatternResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
    }

    public ScanResult scan(String... packagesToScan) {
        ScanResult scanResult = new ScanResult();
        for (String pkg : packagesToScan) {
            scanPackage(pkg, scanResult);
        }
        return scanResult;
    }

    private void scanPackage(String pkg, ScanResult scanResult) {
        try {
            var pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
                ClassUtils.convertClassNameToResourcePath(pkg) + CLASS_RESOURCE_PATTERN;
            var resources = resourcePatternResolver.getResources(pattern);
            var readerFactory = new CachingMetadataReaderFactory(resourcePatternResolver);

            for (Resource resource : resources) {
                scanResource(readerFactory, resource, scanResult);
            }
        } catch (IOException ex) {
            throw new PersistenceException("Failed to get resources", ex);
        }
    }

    private void scanResource(CachingMetadataReaderFactory readerFactory, Resource resource, ScanResult scanResult) {
        try {
            var reader = readerFactory.getMetadataReader(resource);
            var className = reader.getClassMetadata().getClassName();

            if (matchesFilter(reader, readerFactory)) {
                scanResult.managedClassNames.add(className);
            }
        } catch (FileNotFoundException ex) {
            // Ignore non-readable resource
        } catch (IOException ex) {
            throw new PersistenceException("Failed to scan classpath for unlisted entity classes", ex);
        }
    }

    /**
     * Check whether any of the configured entity type filters matches
     * the current class descriptor contained in the metadata reader.
     */
    private boolean matchesFilter(MetadataReader reader, MetadataReaderFactory readerFactory) throws IOException {
        for (TypeFilter filter : entityTypeFilters) {
            if (filter.match(reader, readerFactory)) {
                return true;
            }
        }

        return false;
    }

    @Getter
    public static class ScanResult {
        private final List<String> managedClassNames = new ArrayList<>();
    }
}
