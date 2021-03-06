package com.proofpoint.http.client;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Scopes;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.proofpoint.configuration.ConfigurationModule.bindConfig;
import static java.util.concurrent.Executors.newFixedThreadPool;

public class HttpClientModule implements Module
{
    private final String name;
    private final Class<? extends Annotation> annotation;
    private final List<Class<? extends Annotation>> aliases;

    public HttpClientModule(Class<? extends Annotation> annotation, Class<? extends Annotation>... aliases)
    {
        this(checkNotNull(annotation, "annotation is null").getSimpleName(), annotation, aliases);
    }

    public HttpClientModule(String name, Class<? extends Annotation> annotation, Class<? extends Annotation>... aliases)
    {
        checkNotNull(name, "name is null");
        checkNotNull(annotation, "annotation is null");
        this.annotation = annotation;
        this.name = name;

        if (aliases != null) {
            this.aliases = ImmutableList.copyOf(aliases);
        }
        else {
            this.aliases = Collections.emptyList();
        }
    }

    @Override
    public void configure(Binder binder)
    {
        // bind the configuration
        bindConfig(binder).annotatedWith(annotation).prefixedWith(name).to(HttpClientConfig.class);

        // Bind the datasource
        binder.bind(HttpClient.class).annotatedWith(annotation).toProvider(new HttpClientProvider(name, annotation)).in(Scopes.SINGLETON);

        // Bind aliases
        Key<DataSource> key = Key.get(DataSource.class, annotation);
        for (Class<? extends Annotation> alias : aliases) {
            binder.bind(DataSource.class).annotatedWith(alias).to(key);
        }
    }

    private static class HttpClientProvider implements Provider<HttpClient>
    {
        private final String name;
        private final Class<? extends Annotation> annotation;
        private Injector injector;

        private HttpClientProvider(String name, Class<? extends Annotation> annotation)
        {
            this.name = name;
            this.annotation = annotation;
        }

        @Inject
        public void setInjector(Injector injector)
        {

            this.injector = injector;
        }

        @Override
        public HttpClient get()
        {
            HttpClientConfig config = injector.getInstance(Key.get(HttpClientConfig.class, annotation));
            return new HttpClient(newFixedThreadPool(config.getWorkerThreads(), new ThreadFactoryBuilder().setNameFormat(name + "-http-client-%s").build()));
        }
    }
}
